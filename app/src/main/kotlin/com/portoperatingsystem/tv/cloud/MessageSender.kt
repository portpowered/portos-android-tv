package com.portoperatingsystem.tv.cloud

import com.portoperatingsystem.tv.auth.Session
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The design's message shape for what the TV pushes to `POST /messages`. */
internal object Messages {
    const val ANDROID_TV_NAMESPACE = "port1/systems/zero/plugins/android-tv"
    const val GENERATIVE_UI_NAMESPACE = "port1/systems/zero/capability-interfaces/generative-ui"
    private const val VERSION = "1.0"

    /** A state report or capability registration; [payload] is the report claims (no `iat`/`jti`). */
    fun reportState(deviceId: String, payload: JsonObject): JsonObject =
        message(ANDROID_TV_NAMESPACE, "report-state", deviceId, payload, correlationToken = null)

    /** The reply to a `get-surface`: the request's [correlationToken] rides in `context`. */
    fun getSurfaceResponse(deviceId: String, correlationToken: String, payload: JsonObject): JsonObject =
        message(GENERATIVE_UI_NAMESPACE, "get-surface-response", deviceId, payload, correlationToken)

    private fun message(
        namespace: String,
        name: String,
        deviceId: String,
        payload: JsonObject,
        correlationToken: String?
    ): JsonObject = buildJsonObject {
        put(
            "header",
            buildJsonObject {
                put("namespace", namespace)
                put("name", name)
                put("version", VERSION)
                put("id", UUID.randomUUID().toString())
            }
        )
        put(
            "target",
            buildJsonObject {
                put("type", "ROUTE")
                put("value", deviceId)
            }
        )
        correlationToken?.let { put("context", buildJsonObject { put("correlationToken", it) }) }
        put("payload", payload)
    }
}

/** Pushes the TV's messages to Port OS with the session's access token. */
internal class MessageSender(
    private val client: PortOsClient,
    private val session: Session,
    private val deviceId: () -> String
) {
    /** Pushes a state report (or the capability registration it carries). */
    fun reportState(payload: JsonObject) = post(Messages.reportState(deviceId(), payload))

    /** Answers a `get-surface`. */
    fun replyToGetSurface(correlationToken: String, payload: JsonObject) =
        post(Messages.getSurfaceResponse(deviceId(), correlationToken, payload))

    // A retry of one message reuses its Idempotency-Key, so the server delivers it once.
    private fun post(message: JsonObject) {
        val key = UUID.randomUUID().toString()
        try {
            client.postMessage(session.accessToken(), message, key)
        } catch (error: PortOsException) {
            if (!error.retryable) throw error
            client.postMessage(session.accessToken(), message, key)
        }
    }
}
