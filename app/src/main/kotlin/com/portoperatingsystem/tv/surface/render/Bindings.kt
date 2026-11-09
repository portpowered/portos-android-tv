package com.portoperatingsystem.tv.surface.render

import com.portoperatingsystem.tv.surface.a2ui.DataPointer
import com.portoperatingsystem.tv.surface.a2ui.SurfaceModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val PATH_KEY = "@path"

/** Reads component properties against the surface's data model. A property is a literal or a `{"@path": "/x"}` binding. */
internal class Bindings(private val model: SurfaceModel) {
    fun component(id: String): JsonObject? = model.components[id]

    /** The property as text; null when it is absent, null in the data model or not a primitive. */
    fun string(value: JsonElement?): String? {
        val resolved = if (value is JsonObject) resolve(value) else value
        return (resolved as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    }

    /** The ids of the children to render; anything that is not a list of ids renders nothing. */
    fun children(value: JsonElement?): List<String> =
        (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    private fun resolve(value: JsonObject): JsonElement? {
        val path = (value[PATH_KEY] as? JsonPrimitive)?.content ?: return null
        return DataPointer.get(model.dataModel, path)
    }
}
