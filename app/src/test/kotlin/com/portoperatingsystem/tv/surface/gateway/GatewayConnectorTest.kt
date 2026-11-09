package com.portoperatingsystem.tv.surface.gateway

import android.app.Application
import com.portoperatingsystem.tv.auth.PairAgainException
import com.portoperatingsystem.tv.cloud.PortOsException
import com.portoperatingsystem.tv.surface.SurfacePosition
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "app" rows of the design's connection failure modes, against a mocked gateway. Timings are shrunk; the
 * mock gateway closes with the contract's codes.
 */
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class GatewayConnectorTest {
    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshes = CopyOnWriteArrayList<Boolean>()
    private val received = CopyOnWriteArrayList<String>()
    private var position: SurfacePosition? = null
    private var refreshFails = false

    private val tokens = TokenSource { force ->
        refreshes += force
        if (force && refreshFails) throw PairAgainException()
        "token-$force"
    }

    private val quiet = GatewayTiming(
        pingMillis = 60_000,
        pongTimeoutMillis = 60_000,
        backoffBaseMillis = 10,
        backoffMaxMillis = 80,
        healthyMillis = 60_000
    )

    private fun connector(
        timing: GatewayTiming = quiet,
        link: String? = "link-1",
        onText: (String) -> Unit = { received += it }
    ) = GatewayConnector(
        http = OkHttpClient(),
        gatewayUrl = server.url("/").toString(),
        authLinkId = { link },
        tokens = tokens,
        position = { position },
        onText = onText,
        scope = scope,
        timing = timing,
        random = { 1.0 }
    )

    @AfterTest
    fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun closing(code: Int, reason: String, before: (WebSocket) -> Unit = {}) =
        MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    before(webSocket)
                    webSocket.close(code, reason)
                }
            }
        )

    private fun staying(before: (WebSocket) -> Unit = {}) = MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = before(webSocket)

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "ping") webSocket.send("pong")
            }
        }
    )

    private fun silent() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})

    private fun request(): RecordedRequest = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun connectsWithTheBearerTokenAndNoQueryWhenNoSurfaceIsHeld() {
        server.enqueue(staying())
        val connector = connector()

        connector.wake()

        val first = request()
        assertEquals("Bearer token-false", first.getHeader("Authorization"))
        assertEquals("/devices/link-1", first.requestUrl?.encodedPath)
        assertNull(first.requestUrl?.queryParameter("surfaceId"))
        assertNull(first.requestUrl?.queryParameter("showId"))
        assertNull(first.requestUrl?.queryParameter("lastSequence"))
        await("connected") { connector.state.value == ConnectionState.Connected }
    }

    @Test
    fun anAuthLinkIdWithSlashesIsOnePercentEncodedPathSegment() {
        server.enqueue(staying())

        connector(link = "port1/users/u/auth-links/x").wake()

        assertEquals("/devices/port1%2Fusers%2Fu%2Fauth-links%2Fx", request().path?.substringBefore('?'))
    }

    @Test
    fun deliversTextFramesToTheSurface() {
        server.enqueue(staying { it.send("""{"hello":1}""") })
        connector().wake()

        await("a frame") { received.isNotEmpty() }

        assertEquals("""{"hello":1}""", received.single())
    }

    @Test
    fun wakingTwiceKeepsOneConnection() {
        server.enqueue(staying())
        val connector = connector()

        connector.wake()
        connector.wake()
        await("connected") { connector.state.value == ConnectionState.Connected }
        connector.wake()

        assertEquals(1, server.requestCount)
    }

    // Token expired at connect.
    @Test
    fun anExpiredTokenAtTheHandshakeIsRefreshedAndTheConnectionRetried() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(staying())
        val connector = connector()

        connector.wake()

        await("connected") { connector.state.value == ConnectionState.Connected }
        assertEquals(listOf(false, true), refreshes.toList())
        request()
        assertEquals("Bearer token-true", request().getHeader("Authorization"))
    }

    @Test
    fun anUnauthorizedCloseRefreshesTheTokenAndReconnects() {
        server.enqueue(closing(4401, "UNAUTHORIZED"))
        server.enqueue(staying())
        val connector = connector()

        connector.wake()

        await("connected") { connector.state.value == ConnectionState.Connected }
        assertEquals(listOf(false, true), refreshes.toList())
    }

    // Token without the auth link claim (before the first refresh).
    @Test
    fun aForbiddenCloseRefreshesOnceAndConnectsWithTheClaim() {
        server.enqueue(closing(4403, "FORBIDDEN"))
        server.enqueue(staying())
        val connector = connector()

        connector.wake()

        await("connected") { connector.state.value == ConnectionState.Connected }
        assertEquals(listOf(false, true), refreshes.toList())
    }

    // Auth link claim names another link: no retry loop.
    @Test
    fun aSecondForbiddenStopsUntilTheNextWake() {
        server.enqueue(closing(4403, "FORBIDDEN"))
        server.enqueue(closing(4403, "FORBIDDEN"))
        server.enqueue(staying())
        val connector = connector()

        connector.wake()

        await("stopped") { connector.state.value is ConnectionState.Stopped }
        Thread.sleep(300)
        assertEquals(2, server.requestCount)
        assertEquals(listOf(false, true), refreshes.toList())

        // A later FCM wake tries again.
        connector.wake()
        await("connected") { connector.state.value == ConnectionState.Connected }
        assertEquals(3, server.requestCount)
    }

    // Refresh fails (link removed, refresh token expired).
    @Test
    fun aRefusedRefreshStopsReconnectingAndAsksToPairAgain() {
        refreshFails = true
        server.enqueue(closing(4401, "UNAUTHORIZED"))
        server.enqueue(staying())
        val connector = connector()

        connector.wake()

        await("pair again") { connector.state.value == ConnectionState.PairAgain }
        Thread.sleep(300)
        assertEquals(1, server.requestCount)
    }

    // No auth link (the TV was never paired, or its pairing was cleared): reconnecting cannot help.
    @Test
    fun aTvWithoutAnAuthLinkAsksToPairAgainInsteadOfBackingOff() {
        val connector = connector(link = null)

        connector.wake()

        await("pair again") { connector.state.value == ConnectionState.PairAgain }
        Thread.sleep(300)
        assertEquals(ConnectionState.PairAgain, connector.state.value)
        assertEquals(0, server.requestCount)
    }

    // JWKS unreachable: a retryable close.
    @Test
    fun aRetryableCloseBacksOffAndReconnects() {
        server.enqueue(closing(4503, "RETRY"))
        server.enqueue(staying())
        val connector = connector()
        val states = CopyOnWriteArrayList<ConnectionState>()
        scope.launch { connector.state.collect { states += it } }

        connector.wake()

        await("connected") { connector.state.value == ConnectionState.Connected }
        assertTrue(states.any { it is ConnectionState.Backoff }, "no backoff in $states")
        assertEquals(listOf(false), refreshes.toList().distinct())
    }

    // Gateway unreachable (5xx): 1 s doubling to 60 s with jitter, scaled down here.
    @Test
    fun anUnreachableGatewayBacksOffWithDoublingDelaysUpToTheCeiling() {
        repeat(5) { server.enqueue(MockResponse().setResponseCode(503)) }
        server.enqueue(staying())
        val timing = quiet.copy(backoffBaseMillis = 40, backoffMaxMillis = 160)
        val connector = connector(timing)
        val delays = CopyOnWriteArrayList<Long>()
        scope.launch { connector.state.collect { if (it is ConnectionState.Backoff) delays += it.delayMillis } }

        connector.wake()

        await("connected") { connector.state.value == ConnectionState.Connected }
        assertEquals(listOf(40L, 80L, 160L, 160L, 160L), delays.toList())
    }

    @Test
    fun jitterNeverShortensABackoffBelowHalf() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(staying())
        val low = GatewayConnector(
            OkHttpClient(),
            server.url("/").toString(),
            { "link-1" },
            tokens,
            { null },
            {},
            scope,
            quiet.copy(backoffBaseMillis = 100, backoffMaxMillis = 1_000),
            random = { 0.0 }
        )
        val delays = CopyOnWriteArrayList<Long>()
        scope.launch { low.state.collect { if (it is ConnectionState.Backoff) delays += it.delayMillis } }

        low.wake()

        await("connected") { low.state.value == ConnectionState.Connected }
        assertEquals(listOf(50L), delays.toList())
    }

    // 1-hour close during a stream of updates: reconnect, replay from the last applied sequence.
    @Test
    fun aCycleCloseReconnectsAtOnceAndResumesFromTheHeldSurface() {
        server.enqueue(
            closing(4000, "CYCLE") {
                it.send("frame")
            }
        )
        server.enqueue(staying())
        // A backoff would take 5 s: reconnecting inside one second shows there was none.
        val connector = connector(quiet.copy(backoffBaseMillis = 5_000, backoffMaxMillis = 5_000)) {
            position = SurfacePosition("kitchen", "show-9", 7)
        }

        connector.wake()

        val first = request()
        assertNull(first.requestUrl?.queryParameter("surfaceId"))
        val second = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("kitchen", second.requestUrl?.queryParameter("surfaceId"))
        assertEquals("show-9", second.requestUrl?.queryParameter("showId"))
        assertEquals("7", second.requestUrl?.queryParameter("lastSequence"))
        assertEquals("Bearer token-false", second.getHeader("Authorization"))
    }

    // Second connection: the old socket closes REPLACED and does not reconnect.
    @Test
    fun aReplacedSocketDoesNotReconnect() {
        server.enqueue(closing(4409, "REPLACED"))
        server.enqueue(staying())
        val connector = connector()

        connector.wake()

        await("stopped") { connector.state.value == ConnectionState.Stopped("replaced") }
        Thread.sleep(300)
        assertEquals(1, server.requestCount)
    }

    // Half-open connection: no pong within the timeout.
    @Test
    fun aMissingPongReconnects() {
        server.enqueue(silent())
        server.enqueue(staying())
        val connector = connector(quiet.copy(pingMillis = 50, pongTimeoutMillis = 100))

        connector.wake()

        await("a second connection") { server.requestCount == 2 }
        await("connected") { connector.state.value == ConnectionState.Connected }
    }

    @Test
    fun aHealthySocketKeepsPingingWithoutReconnecting() {
        server.enqueue(staying())
        val connector = connector(quiet.copy(pingMillis = 30, pongTimeoutMillis = 200))

        connector.wake()
        await("connected") { connector.state.value == ConnectionState.Connected }
        Thread.sleep(500)

        assertEquals(1, server.requestCount)
        assertEquals(ConnectionState.Connected, connector.state.value)
    }

    @Test
    fun aNetworkFailureThatIsNotRefusedBacksOffWithoutRefreshing() {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(staying())
        val connector = connector()

        connector.wake()

        await("connected") { connector.state.value == ConnectionState.Connected }
        assertEquals(listOf(false), refreshes.toList().distinct())
    }

    @Test
    fun reconnectDropsTheSocketAndResumes() {
        server.enqueue(staying())
        server.enqueue(staying())
        val connector = connector()
        connector.wake()
        await("connected") { connector.state.value == ConnectionState.Connected }

        connector.reconnect()

        await("a second connection") { server.requestCount == 2 }
        await("connected again") { connector.state.value == ConnectionState.Connected }
    }

    @Test
    fun stopEndsTheLoopAndALaterWakeStartsAgain() {
        server.enqueue(staying())
        server.enqueue(staying())
        val connector = connector()
        connector.wake()
        await("connected") { connector.state.value == ConnectionState.Connected }

        connector.stop()
        Thread.sleep(200)
        assertIs<ConnectionState.Idle>(connector.state.value)
        assertEquals(1, server.requestCount)

        connector.wake()
        await("connected again") { connector.state.value == ConnectionState.Connected }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun anUnpairedTvDoesNotConnect() {
        val unpaired = GatewayConnector(
            OkHttpClient(),
            server.url("/").toString(),
            { null },
            tokens,
            { null },
            {},
            scope,
            quiet
        )
        unpaired.wake()
        Thread.sleep(200)

        assertEquals(0, server.requestCount)
        assertFalse(unpaired.state.value == ConnectionState.Connected)
    }

    @Test
    fun aTokenNetworkFailureBacksOffAndTriesAgain() {
        var calls = 0
        server.enqueue(staying())
        val flaky = GatewayConnector(
            OkHttpClient(),
            server.url("/").toString(),
            { "link-1" },
            { if (calls++ == 0) throw PortOsException("offline", retryable = true) else "t" },
            { null },
            {},
            scope,
            quiet
        )

        flaky.wake()

        await("connected") { flaky.state.value == ConnectionState.Connected }
        assertEquals(2, calls)
    }
}
