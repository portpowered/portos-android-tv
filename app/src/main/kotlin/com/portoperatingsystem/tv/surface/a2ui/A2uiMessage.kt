package com.portoperatingsystem.tv.surface.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A2UI messages are malformed. The TV skips the message; the plugin normally refuses these before dispatch. */
internal class A2uiException(message: String) : IllegalArgumentException(message)

/**
 * The four A2UI v1.0 server-to-client messages the TV applies (`createSurface`, `updateComponents`,
 * `updateDataModel`, `deleteSurface`). The library `androidx.a2ui` is not used: its alpha02 artifacts need
 * androidx.core 1.19 (compileSdk 37, AGP 9.1) and Kotlin 2.2 metadata, so the v1.0 shapes are parsed here with
 * kotlinx.serialization. The contract is the message shape, not the library.
 */
internal sealed interface A2uiMessage {
    val surfaceId: String

    data class CreateSurface(
        override val surfaceId: String,
        val catalogId: String?,
        val components: List<JsonObject>,
        val dataModel: JsonObject?
    ) : A2uiMessage

    data class UpdateComponents(override val surfaceId: String, val components: List<JsonObject>) : A2uiMessage

    data class UpdateDataModel(override val surfaceId: String, val path: String?, val value: JsonElement) :
        A2uiMessage

    data class DeleteSurface(override val surfaceId: String) : A2uiMessage
}

/** Parses A2UI v1.0 server-to-client messages. */
internal object A2uiParser {
    const val VERSION = "1.0"
    private const val ROOT_TYPE = "Surface"

    /** Parses every message of an `a2ui` list. */
    fun parseAll(messages: JsonElement?): List<A2uiMessage> {
        val array = messages as? JsonArray ?: throw A2uiException("a2ui must be a list of messages")
        return array.map(::parse)
    }

    fun parse(element: JsonElement): A2uiMessage {
        val message = element as? JsonObject ?: throw A2uiException("an A2UI message must be an object")
        if ((message["version"] as? JsonPrimitive)?.content != VERSION) {
            throw A2uiException("only A2UI version $VERSION is supported")
        }
        val bodies = BODIES.filter { it in message }
        if (bodies.size != 1) throw A2uiException("a message holds exactly one of ${BODIES.joinToString()}")
        val body = message.getValue(bodies.single()) as? JsonObject
            ?: throw A2uiException("${bodies.single()} must be an object")
        val surfaceId = (body["surfaceId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw A2uiException("surfaceId must be a string")
        return when (bodies.single()) {
            "createSurface" -> A2uiMessage.CreateSurface(
                surfaceId,
                (body["catalogId"] as? JsonPrimitive)?.content,
                components(body["components"], required = false),
                body["dataModel"]?.let { it as? JsonObject ?: throw A2uiException("dataModel must be an object") }
            )
            "updateComponents" -> A2uiMessage.UpdateComponents(surfaceId, components(body["components"], true))
            "updateDataModel" -> A2uiMessage.UpdateDataModel(
                surfaceId,
                (body["path"] as? JsonPrimitive)?.content,
                body["value"] ?: JsonNull
            )
            else -> A2uiMessage.DeleteSurface(surfaceId)
        }
    }

    private fun components(element: JsonElement?, required: Boolean): List<JsonObject> {
        if (element == null) {
            if (required) throw A2uiException("components is required")
            return emptyList()
        }
        val array = element as? JsonArray ?: throw A2uiException("components must be a list")
        return array.map { entry ->
            val component = entry as? JsonObject ?: throw A2uiException("a component must be an object")
            val id = (component["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val type = (component["component"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id.isNullOrEmpty()) throw A2uiException("a component needs an id")
            if (type.isNullOrEmpty() || type == ROOT_TYPE) throw A2uiException("component $id needs a component name")
            component
        }
    }

    private val BODIES = listOf("createSurface", "updateComponents", "updateDataModel", "deleteSurface")
}
