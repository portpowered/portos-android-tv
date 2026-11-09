package com.portoperatingsystem.tv.surface.render

import com.portoperatingsystem.tv.surface.a2ui.DataPointer
import com.portoperatingsystem.tv.surface.a2ui.SurfaceModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val PATH_KEY = "@path"

/**
 * Reads component properties against the surface's data model. A property is a literal or a `{"@path": "/x"}`
 * binding; a path that does not start with `/` is relative to the [scope] of the template item being rendered.
 */
internal class Bindings(private val model: SurfaceModel) {
    fun component(id: String): JsonObject? = model.components[id]

    /** The property as text; null when it is absent, null in the data model or not a primitive. */
    fun string(value: JsonElement?, scope: String): String? {
        val resolved = resolve(value, scope)
        return (resolved as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    }

    /** The children to render: static ids, or one per item of a template's list, each with its own scope. */
    fun children(value: JsonElement?, scope: String): List<Child> = when (value) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content?.let { id -> Child(id, scope) } }
        is JsonObject -> templateChildren(value, scope)
        else -> emptyList()
    }

    private fun templateChildren(template: JsonObject, scope: String): List<Child> {
        val id = (template["componentId"] as? JsonPrimitive)?.content ?: return emptyList()
        val path = absolute((template["path"] as? JsonPrimitive)?.content ?: return emptyList(), scope)
        val items = DataPointer.get(model.dataModel, path) as? JsonArray ?: return emptyList()
        return items.indices.map { Child(id, "$path/$it") }
    }

    private fun resolve(value: JsonElement?, scope: String): JsonElement? {
        if (value !is JsonObject) return value
        val path = (value[PATH_KEY] as? JsonPrimitive)?.content ?: return null
        return DataPointer.get(model.dataModel, absolute(path, scope))
    }

    private fun absolute(path: String, scope: String): String =
        if (path.startsWith("/") || scope.isEmpty()) path else "$scope/$path"
}

/** A component to render and the data-model scope its relative bindings resolve against. */
internal data class Child(val id: String, val scope: String)
