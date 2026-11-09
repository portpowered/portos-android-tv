package com.portoperatingsystem.tv.auth

import android.util.Log
import com.portoperatingsystem.tv.cloud.DeviceAuthorization
import com.portoperatingsystem.tv.cloud.PollResult
import com.portoperatingsystem.tv.cloud.PortOsClient
import com.portoperatingsystem.tv.cloud.PortOsException
import com.portoperatingsystem.tv.cloud.Registration
import com.portoperatingsystem.tv.cloud.TokenResponse
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
     * Gives a TV paired before Port OS put the auth link in device tokens a token that carries it: `POST /messages`
     * and the gateway refuse a token without the claim. Renewing the installation binds this device session to its
     * link on the Port OS side, and one refresh then issues a token with the claim. Does nothing when the stored
     * token already carries it, so it is cheap to call before every report.
     */
    suspend fun ensureLinkClaim() {
        val current = store.load() ?: return
        val linkId = current.authLinkId ?: return
        if (carriesLinkClaim(current.accessToken)) return
        establish(freshTokens(current), linkId)
        accessToken(forceRefresh = true)
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

    internal companion object {
        /** The access token claim naming the auth link the token may push for. */
        const val LINK_CLAIM = "https://portpowered.com/auth-link"

        private const val TAG = "PortOsSession"
        private const val MILLIS = 1000L
        private const val SLOW_DOWN_SECONDS = 5L
        private const val EXPIRY_MARGIN_SECONDS = 300L

        /** Whether the JWT [accessToken] names an auth link. The TV only reads its own token; it verifies nothing. */
        fun carriesLinkClaim(accessToken: String): Boolean {
            val payload = accessToken.split('.').getOrNull(1) ?: return false
            return try {
                val claims = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload))).jsonObject
                !claims[LINK_CLAIM]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }
}
