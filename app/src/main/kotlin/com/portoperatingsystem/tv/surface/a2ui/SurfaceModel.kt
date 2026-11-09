package com.portoperatingsystem.tv.surface.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The flat component list and data model of one surface, as A2UI messages built them. Immutable. */
internal data class SurfaceModel(
    val surfaceId: String,
    val catalogId: String?,
    val components: Map<String, JsonObject>,
    val dataModel: JsonElement
) {
    /** The component list in the order it was first sent, as `get-surface` returns it. */
    fun componentList(): JsonArray = JsonArray(components.values.toList())

    /** Applies a message for this surface. A `createSurface` is the host's concern and is ignored here. */
    fun apply(message: A2uiMessage): SurfaceModel = when (message) {
        is A2uiMessage.UpdateComponents -> copy(components = components + message.components.map { it.id() to it })
        is A2uiMessage.UpdateDataModel -> copy(dataModel = DataPointer.set(dataModel, message.path, message.value))
        is A2uiMessage.CreateSurface, is A2uiMessage.DeleteSurface -> this
    }

    companion object {
        /** The model a `createSurface` builds. */
        fun create(message: A2uiMessage.CreateSurface): SurfaceModel = SurfaceModel(
            message.surfaceId,
            message.catalogId,
            message.components.associateBy { it.id() },
            message.dataModel ?: JsonObject(emptyMap())
        )
    }
}

internal fun JsonObject.id(): String = (getValue("id") as JsonPrimitive).content

/** JSON Pointer (RFC 6901) reads and copy-on-write updates over a JSON tree: the A2UI data model. */
internal object DataPointer {
    fun get(root: JsonElement, pointer: String): JsonElement? {
        var node: JsonElement = root
        for (token in tokens(pointer)) {
            node = when (node) {
                is JsonObject -> node[token] ?: return null
                is JsonArray -> token.toIntOrNull()?.let { node.getOrNull(it) } ?: return null
                else -> return null
            }
        }
        return node
    }

    /** Sets [value] at [pointer] (null or "/" is the whole model); a JSON null deletes the key. */
    fun set(root: JsonElement, pointer: String?, value: JsonElement): JsonElement {
        val path = tokens(pointer.orEmpty())
        return if (path.isEmpty()) value else write(root, path, value)
    }

    private fun write(node: JsonElement, path: List<String>, value: JsonElement): JsonElement {
        val head = path.first()
        val rest = path.drop(1)
        return when (node) {
            is JsonArray -> writeArray(node, head, rest, value)
            else -> {
                val map = ((node as? JsonObject) ?: JsonObject(emptyMap())).toMutableMap()
                val updated = if (rest.isEmpty()) value else write(map[head] ?: JsonObject(emptyMap()), rest, value)
                if (rest.isEmpty() && value is JsonNull) map.remove(head) else map[head] = updated
                JsonObject(map)
            }
        }
    }

    private fun writeArray(array: JsonArray, head: String, rest: List<String>, value: JsonElement): JsonElement {
        val index = if (head == "-") array.size else head.toIntOrNull() ?: return array
        if (index < 0 || index > array.size) return array
        val list = array.toMutableList()
        val child = if (rest.isEmpty()) value else write(list.getOrNull(index) ?: JsonObject(emptyMap()), rest, value)
        when {
            rest.isEmpty() && value is JsonNull && index < list.size -> list.removeAt(index)
            index == list.size -> list.add(child)
            else -> list[index] = child
        }
        return JsonArray(list)
    }

    private fun tokens(pointer: String): List<String> = if (pointer.isEmpty() || pointer == "/") {
        emptyList()
    } else {
        pointer.removePrefix("/").split('/').map { it.replace("~1", "/").replace("~0", "~") }
    }
}
