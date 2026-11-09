package com.portoperatingsystem.tv.auth

import android.util.Log
import com.portoperatingsystem.tv.cloud.DeviceAuthorization
import com.portoperatingsystem.tv.cloud.PollResult
import com.portoperatingsystem.tv.cloud.PortOsClient
import com.portoperatingsystem.tv.cloud.PortOsException
import com.portoperatingsystem.tv.cloud.Registration
import com.portoperatingsystem.tv.cloud.TokenResponse
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where pairing stands, for the UI. */
internal sealed interface PairingState {
    data object Starting : PairingState

    data class AwaitingApproval(val authorization: DeviceAuthorization) : PairingState

    data object Paired : PairingState

    data class Failed(val reason: String) : PairingState
}

/** Refreshing the access token was refused (link removed, refresh token expired): the TV must be paired again. */
internal class PairAgainException(cause: Throwable? = null) :
    PortOsException("the pairing is no longer valid; pair this TV again", retryable = false, cause = cause)

/**
 * Owns the TV's Port OS session: device-code pairing, the DPoP-bound refresh, and keeping the plugin
 * installation's FCM token current.
 */
internal class Session(
    private val client: PortOsClient,
    private val store: CredentialStore,
    private val registration: suspend () -> Registration,
    private val clock: () -> Long = { System.currentTimeMillis() / MILLIS },
    private val newIdentity: () -> Unit = {}
) {
    private val mutableNeedsPairing = MutableStateFlow(false)

    /** True once a refresh was refused: the TV shows "pair again" and stops reporting and reconnecting. */
    val needsPairing: StateFlow<Boolean> = mutableNeedsPairing

    val isPaired: Boolean
        get() = store.load()?.authLinkId != null

    val authLinkId: String?
        get() = store.load()?.authLinkId

    /**
     * Runs device-code pairing to completion, reporting progress through [onState]. Every attempt
     * starts from a new key: Port OS registers a device authorization once per key, so a key from
     * an abandoned or failed attempt can never pair again.
     */
    suspend fun pair(onState: (PairingState) -> Unit) {
        try {
            runPairing(onState)
        } catch (error: IOException) {
            onState(PairingState.Failed(error.message ?: error.javaClass.simpleName))
        }
    }

    private suspend fun runPairing(onState: (PairingState) -> Unit) {
        onState(PairingState.Starting)
        store.clear()
        newIdentity()
        mutableNeedsPairing.value = false
        val authorization = client.authorizeDevice()
        onState(PairingState.AwaitingApproval(authorization))
        val deadline = clock() + authorization.expiresIn
        var interval = authorization.interval.toLong()
        while (clock() < deadline) {
            delay(interval * MILLIS)
            when (val result = client.pollToken(authorization.deviceCode)) {
                is PollResult.Approved -> {
                    establish(result.tokens, authLinkId = null)
                    refreshForLinkClaim()
                    onState(PairingState.Paired)
                    return
                }
                PollResult.Pending -> Unit
                PollResult.SlowDown -> interval += SLOW_DOWN_SECONDS
                is PollResult.Failed -> {
                    onState(PairingState.Failed(result.reason))
                    return
                }
            }
        }
        onState(PairingState.Failed("the code expired"))
    }

    /** Re-submits the current FCM token (after rotation), keeping the same installation and route. */
    suspend fun renewInstallation() {
        val current = checkNotNull(store.load()) { "not paired" }
        establish(freshTokens(current), current.authLinkId)
    }

    /**
     * A current access token for `POST /messages` and the gateway; refreshes when it is about to expire or when
     * [forceRefresh]. A refusal from the auth server (not a network failure) throws [PairAgainException] and raises
     * [needsPairing].
     */
    @Synchronized
    fun accessToken(forceRefresh: Boolean = false): String {
        val current = store.load() ?: throw PairAgainException()
        if (!forceRefresh && current.accessTokenExpiresAtSeconds - clock() > EXPIRY_MARGIN_SECONDS) {
            return current.accessToken
        }
        val tokens = try {
            client.refresh(current.refreshToken)
        } catch (error: PortOsException) {
            if (error.retryable) throw error
            mutableNeedsPairing.value = true
            throw PairAgainException(error)
        }
        store.save(
            current.copy(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken ?: current.refreshToken,
                accessTokenExpiresAtSeconds = clock() + tokens.expiresIn
            )
        )
        return tokens.accessToken
    }

    // The token issued at pairing predates the auth link; one refresh gets the token that carries the link claim.
    private fun refreshForLinkClaim() {
        try {
            accessToken(forceRefresh = true)
        } catch (error: PortOsException) {
            Log.w(TAG, "refresh after pairing failed: ${error.message}")
        }
    }

    /** Forgets the pairing. The device id stays, so pairing again restores the same Port OS route. */
    fun unpair() = store.clear()

    private suspend fun establish(tokens: TokenResponse, authLinkId: String?) {
        val linkId = client.establish(tokens.accessToken, registration(), authLinkId)
        val previous = store.load()
        store.save(
            Credentials(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken ?: checkNotNull(previous?.refreshToken) {
                    "no refresh token issued"
                },
                accessTokenExpiresAtSeconds = clock() + tokens.expiresIn,
                authLinkId = linkId
            )
        )
    }

    private fun freshTokens(current: Credentials): TokenResponse {
        val remaining = current.accessTokenExpiresAtSeconds - clock()
        return if (remaining > EXPIRY_MARGIN_SECONDS) {
            TokenResponse(current.accessToken, current.refreshToken, remaining)
        } else {
            client.refresh(current.refreshToken)
        }
    }

    private companion object {
        const val TAG = "PortOsSession"
        const val MILLIS = 1000L
        const val SLOW_DOWN_SECONDS = 5L
        const val EXPIRY_MARGIN_SECONDS = 300L
    }
}
