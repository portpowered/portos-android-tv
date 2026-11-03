package com.portoperatingsystem.tv.auth

import com.portoperatingsystem.tv.cloud.DeviceAuthorization
import com.portoperatingsystem.tv.cloud.PollResult
import com.portoperatingsystem.tv.cloud.PortOsClient
import com.portoperatingsystem.tv.cloud.Registration
import com.portoperatingsystem.tv.cloud.TokenResponse
import java.io.IOException
import kotlinx.coroutines.delay

/** Where pairing stands, for the UI. */
internal sealed interface PairingState {
    data object Starting : PairingState

    data class AwaitingApproval(val authorization: DeviceAuthorization) : PairingState

    data object Paired : PairingState

    data class Failed(val reason: String) : PairingState
}

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
        val authorization = client.authorizeDevice()
        onState(PairingState.AwaitingApproval(authorization))
        val deadline = clock() + authorization.expiresIn
        var interval = authorization.interval.toLong()
        while (clock() < deadline) {
            delay(interval * MILLIS)
            when (val result = client.pollToken(authorization.deviceCode)) {
                is PollResult.Approved -> {
                    establish(result.tokens, authLinkId = null)
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
        const val MILLIS = 1000L
        const val SLOW_DOWN_SECONDS = 5L
        const val EXPIRY_MARGIN_SECONDS = 300L
    }
}
