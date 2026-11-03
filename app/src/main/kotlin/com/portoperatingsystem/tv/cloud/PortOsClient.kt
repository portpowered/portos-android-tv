package com.portoperatingsystem.tv.cloud

import com.portoperatingsystem.tv.auth.DeviceSigner
import com.portoperatingsystem.tv.auth.Jose
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** RFC 8628 device authorization: what the TV shows while it waits for approval. */
@Serializable
internal data class DeviceAuthorization(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int = DEFAULT_INTERVAL_SECONDS
) {
    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 5
    }
}

/** A token endpoint success. */
@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long
)

@Serializable
private data class OAuthError(val error: String, @SerialName("error_description") val description: String? = null)

@Serializable
private data class ExchangeResponse(val authLinkId: String)

/** The TV's identity and FCM address, submitted when it establishes its Android TV plugin installation. */
internal data class Registration(val fcmToken: String, val deviceId: String, val deviceName: String, val model: String)

/** Outcome of one device-code poll. */
internal sealed interface PollResult {
    data class Approved(val tokens: TokenResponse) : PollResult

    data object Pending : PollResult

    data object SlowDown : PollResult

    data class Failed(val reason: String) : PollResult
}

/** A Port OS call that failed. [retryable] is true for network errors and 5xx. */
internal class PortOsException(message: String, val retryable: Boolean, cause: Throwable? = null) :
    IOException(message, cause)

/** The Port OS HTTP API, as the TV uses it. Every request is signed or DPoP-proven by [signer]. */
internal class PortOsClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val clientId: String,
    private val pluginId: String,
    private val signer: DeviceSigner,
    private val clock: () -> Long = { System.currentTimeMillis() / MILLIS }
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun authorizeDevice(): DeviceAuthorization {
        val url = "$baseUrl/auth/device-authorization"
        val body = FormBody.Builder().add(
            "client_id",
            clientId
        ).add("device_pubkey", signer.publicJwk.toString()).build()
        return json.decodeFromString(execute(dpopPost(url, body)))
    }

    fun pollToken(deviceCode: String): PollResult {
        val url = "$baseUrl/auth/token"
        val body = FormBody.Builder()
            .add("grant_type", DEVICE_CODE_GRANT)
            .add("device_code", deviceCode)
            .add("client_id", clientId)
            .build()
        return reaching {
            http.newCall(dpopPost(url, body)).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> PollResult.Approved(json.decodeFromString(text))
                    response.code >= SERVER_ERROR -> throw PortOsException(
                        serverMessage(response.code, text),
                        retryable = true
                    )
                    else -> pollError(response.code, text)
                }
            }
        }
    }

    fun refresh(refreshToken: String): TokenResponse {
        val url = "$baseUrl/auth/token"
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        return json.decodeFromString(execute(dpopPost(url, body)))
    }

    /** Establishes (or, with [authLinkId], renews) this TV's plugin installation. Returns the auth link id. */
    fun establish(accessToken: String, registration: Registration, authLinkId: String?): String {
        val payload = buildJsonObject {
            put("pluginId", pluginId)
            put("principalId", "~self")
            put("authorizationVariantId", "customer-two-factor")
            authLinkId?.let { put("authLinkId", it) }
            put(
                "pluginAuthenticationData",
                buildJsonObject {
                    put("fcmToken", registration.fcmToken)
                    put("deviceId", registration.deviceId)
                    put("deviceName", registration.deviceName)
                    put("model", registration.model)
                    put("publicKeyJwk", signer.publicJwk)
                }
            )
        }
        val request = Request.Builder()
            .url("$baseUrl/plugin-auth-code-exchanges")
            .header("Authorization", "Bearer $accessToken")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return json.decodeFromString<ExchangeResponse>(execute(request)).authLinkId
    }

    /** Posts signed state [claims] to this TV's installation webhook. */
    fun report(authLinkId: String, claims: JsonObject) {
        val jws = Jose.sign(signer, claims, buildJsonObject { put("typ", "JWT") })
        val request = Request.Builder()
            .url("$baseUrl/webhooks/android-tv/installations/$authLinkId")
            .post(jws.toRequestBody(JWS))
            .build()
        execute(request)
    }

    private fun dpopPost(url: String, body: RequestBody): Request = Request.Builder()
        .url(url)
        .header("DPoP", Jose.dpopProof(signer, "POST", url, clock()))
        .post(body)
        .build()

    private fun pollError(code: Int, text: String): PollResult {
        val error = runCatching { json.decodeFromString<OAuthError>(text) }.getOrNull()
        return when (error?.error) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            null -> PollResult.Failed(serverMessage(code, text))
            else -> PollResult.Failed(error.description ?: error.error)
        }
    }

    private fun execute(request: Request): String = reaching {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw PortOsException(serverMessage(response.code, text), response.code >= SERVER_ERROR)
            }
            text
        }
    }

    /** Turns a network failure into a [PortOsException] that says Port OS could not be reached. */
    private fun <T> reaching(call: () -> T): T = try {
        call()
    } catch (error: PortOsException) {
        throw error
    } catch (error: IOException) {
        throw PortOsException(
            "can't reach Port OS: ${error.message ?: error.javaClass.simpleName}",
            retryable = true,
            cause = error
        )
    }

    /**
     * The server's own words for a failed response: an OAuth `error_description`, else `error`, else a
     * Port OS error body's `message`, else `HTTP <code>`.
     */
    private fun serverMessage(code: Int, text: String): String {
        val body = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val fields = listOf("error_description", "error", "message")
        return fields.firstNotNullOfOrNull { field ->
            (body?.get(field) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        } ?: "HTTP $code"
    }

    private companion object {
        const val MILLIS = 1000L
        const val SERVER_ERROR = 500
        const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        val JSON = "application/json".toMediaType()
        val JWS = "application/jose".toMediaType()
    }
}
