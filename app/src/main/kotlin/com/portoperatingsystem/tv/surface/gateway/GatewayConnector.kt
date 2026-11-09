package com.portoperatingsystem.tv.surface.gateway

import android.util.Log
import com.portoperatingsystem.tv.auth.PairAgainException
import com.portoperatingsystem.tv.cloud.PortOsException
import com.portoperatingsystem.tv.surface.SurfacePosition
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Where the gateway connection stands. */
internal sealed interface ConnectionState {
    /** Not connected and not trying; a FCM wake starts it. */
    data object Idle : ConnectionState

    data object Connecting : ConnectionState

    data object Connected : ConnectionState

    /** Waiting [delayMillis] before the next attempt. */
    data class Backoff(val delayMillis: Long) : ConnectionState

    /** Refreshing the token failed for good: the TV must be paired again. */
    data object PairAgain : ConnectionState

    /** Stopped until the next FCM wake. */
    data class Stopped(val reason: String) : ConnectionState
}

/** Where the TV's access tokens come from. */
internal fun interface TokenSource {
    /** A current access token; [forceRefresh] refreshes it first. Throws [PairAgainException] if refresh is refused. */
    fun accessToken(forceRefresh: Boolean): String
}

/** Timings of the connection; the defaults are the contract's, tests shrink them. */
internal data class GatewayTiming(
    val pingMillis: Long = 30_000L,
    val pongTimeoutMillis: Long = 10_000L,
    val backoffBaseMillis: Long = 1_000L,
    val backoffMaxMillis: Long = 60_000L,
    /** A connection open this long was healthy: the backoff and the forbidden counter start over. */
    val healthyMillis: Long = 10_000L
)

/**
 * The TV's WebSocket to the device gateway (`GET /devices/{authLinkId}`, resuming with the held surface's
 * `surfaceId`, `showId` and `lastSequence`). Connects on [wake] and stays connected while the app runs: pings
 * every [GatewayTiming.pingMillis] and reconnects when no pong comes, reconnects with a jittered exponential
 * backoff after failures, and reads the gateway's close codes:
 * 4401 refresh and reconnect, 4403 refresh once then stop until the next wake, 4409 stop (this socket was
 * replaced), 4000 reconnect now, 4503 and anything else back off.
 */
@Suppress("LongParameterList") // Each parameter is a distinct collaborator, wired once in AppGraph.
internal class GatewayConnector(
    private val http: OkHttpClient,
    private val gatewayUrl: String,
    private val authLinkId: () -> String?,
    private val tokens: TokenSource,
    private val position: () -> SurfacePosition?,
    private val onText: (String) -> Unit,
    private val scope: CoroutineScope,
    private val timing: GatewayTiming = GatewayTiming(),
    private val random: () -> Double = Random.Default::nextDouble
) {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val lock = Any()
    private var loop: Job? = null
    private var socket: WebSocket? = null

    val state: StateFlow<ConnectionState> = mutableState

    /** Connects unless a connection loop is already running (an FCM `generative-ui/connect` or the app starting). */
    fun wake() = synchronized(lock) {
        if (loop?.isActive == true) return
        loop = scope.launch { run() }
    }

    /** Drops the current socket and reconnects at once, resuming from the last applied sequence. */
    fun reconnect() {
        localClose = true
        synchronized(lock) { socket }?.cancel()
    }

    fun stop() {
        synchronized(lock) { loop?.cancel() }
        reconnect()
        mutableState.value = ConnectionState.Idle
    }

    private sealed interface Ended {
        data class Closed(val code: Int) : Ended

        /** A failure before or after the upgrade; [httpCode] is the handshake's status when there was one. */
        data class Failed(val httpCode: Int?) : Ended

        data object Local : Ended

        /** The TV has no auth link: nothing to connect to until it is paired again. */
        data object Unpaired : Ended
    }

    private suspend fun run() {
        var attempt = 0
        var refresh = false
        var forbidden = false
        while (scope.isActive) {
            val token = try {
                mutableState.value = ConnectionState.Connecting
                tokens.accessToken(refresh)
            } catch (_: PairAgainException) {
                mutableState.value = ConnectionState.PairAgain
                return
            } catch (error: PortOsException) {
                Log.w(TAG, "token unavailable: ${error.message}")
                attempt = backoff(attempt)
                continue
            }
            refresh = false
            val openedFor = LongArray(1)
            val ended = connectOnce(token, openedFor)
            if (openedFor[0] >= timing.healthyMillis) {
                attempt = 0
                forbidden = false
            }
            when (classify(ended)) {
                Next.NOW -> Unit
                Next.REFRESH_BACKOFF -> {
                    refresh = true
                    attempt = backoff(attempt)
                }
                Next.REFRESH_ONCE -> {
                    if (forbidden) return stopped("forbidden")
                    forbidden = true
                    refresh = true
                }
                Next.STOP -> return stopped("replaced")
                Next.PAIR_AGAIN -> {
                    mutableState.value = ConnectionState.PairAgain
                    return
                }
                Next.BACKOFF -> attempt = backoff(attempt)
            }
        }
    }

    private enum class Next { NOW, REFRESH_BACKOFF, REFRESH_ONCE, STOP, BACKOFF, PAIR_AGAIN }

    private fun classify(ended: Ended): Next = when (ended) {
        Ended.Local -> Next.NOW
        Ended.Unpaired -> Next.PAIR_AGAIN
        is Ended.Closed -> byCode(ended.code)
        is Ended.Failed -> byCode(ended.httpCode ?: 0)
    }

    private fun byCode(code: Int): Next = when (code) {
        CLOSE_CYCLE -> Next.NOW
        CLOSE_UNAUTHORIZED, HTTP_UNAUTHORIZED -> Next.REFRESH_BACKOFF
        CLOSE_FORBIDDEN, HTTP_FORBIDDEN -> Next.REFRESH_ONCE
        CLOSE_REPLACED -> Next.STOP
        else -> Next.BACKOFF
    }

    private fun stopped(reason: String) {
        Log.w(TAG, "gateway connection stopped: $reason")
        mutableState.value = ConnectionState.Stopped(reason)
    }

    /** Waits the backoff for [attempt] (1 s doubling to 60 s, with jitter) and returns the next attempt number. */
    private suspend fun backoff(attempt: Int): Int {
        val ceiling = min(timing.backoffMaxMillis.toDouble(), timing.backoffBaseMillis * 2.0.pow(attempt))
        val wait = (ceiling * (JITTER_FLOOR + (1 - JITTER_FLOOR) * random())).toLong()
        mutableState.value = ConnectionState.Backoff(wait)
        delay(wait)
        return attempt + 1
    }

    private suspend fun connectOnce(token: String, openedFor: LongArray): Ended {
        val id = authLinkId() ?: return Ended.Unpaired.also { Log.w(TAG, "no auth link: pair the TV again") }
        localClose = false
        val ended = CompletableDeferred<Ended>()
        var openedAt = 0L
        var pingJob: Job? = null
        val pong = java.util.concurrent.atomic.AtomicLong(0)
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openedAt = System.currentTimeMillis()
                mutableState.value = ConnectionState.Connected
                pingJob = scope.launch { keepAlive(webSocket, pong) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == PONG) pong.incrementAndGet() else onText(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                ended.complete(Ended.Closed(code))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ended.complete(Ended.Closed(code))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "gateway socket failed: ${t.message}")
                ended.complete(if (localClose) Ended.Local else Ended.Failed(response?.code))
            }
        }
        val request = Request.Builder()
            .url(url(id))
            .header("Authorization", "Bearer $token")
            .build()
        val opened = http.newWebSocket(request, listener)
        synchronized(lock) { socket = opened }
        try {
            return ended.await()
        } finally {
            pingJob?.cancel()
            synchronized(lock) { socket = null }
            if (openedAt > 0) openedFor[0] = System.currentTimeMillis() - openedAt
            opened.cancel()
        }
    }

    @Volatile private var localClose = false

    internal suspend fun keepAlive(webSocket: WebSocket, pong: java.util.concurrent.atomic.AtomicLong) {
        while (true) {
            delay(timing.pingMillis)
            val before = pong.get()
            if (!webSocket.send(PING)) return webSocket.cancel()
            delay(timing.pongTimeoutMillis)
            if (pong.get() == before) {
                Log.w(TAG, "no pong within ${timing.pongTimeoutMillis} ms; reconnecting")
                return webSocket.cancel()
            }
        }
    }

    private fun url(authLink: String): okhttp3.HttpUrl {
        // An auth link id contains slashes (`port1/<owner>/auth-links/<uuid>`): it is one path segment, so `/` is
        // sent as `%2F` and the gateway decodes it back.
        val builder = gatewayUrl.toHttpUrl().newBuilder()
            .addPathSegment("devices")
            .addPathSegment(authLink)
        position()?.let { held ->
            builder.addQueryParameter("surfaceId", held.surfaceId)
                .addQueryParameter("showId", held.showId)
                .addQueryParameter("lastSequence", held.sequence.toString())
        }
        return builder.build()
    }

    private companion object {
        const val TAG = "PortOsGateway"
        const val PING = "ping"
        const val PONG = "pong"
        const val CLOSE_CYCLE = 4000
        const val CLOSE_UNAUTHORIZED = 4401
        const val CLOSE_FORBIDDEN = 4403
        const val CLOSE_REPLACED = 4409
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val JITTER_FLOOR = 0.5
    }
}
