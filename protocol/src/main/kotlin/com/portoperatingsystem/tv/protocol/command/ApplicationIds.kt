package com.portoperatingsystem.tv.protocol.command

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Application ids on the TV. Port OS names an app by a catalog vendor id plus that catalog's id; the TV has one
 * catalog, the Android package names, which Port OS calls catalog vendor id [CATALOG_VENDOR_ID].
 */
public object ApplicationIds {
    /** The catalog vendor id that means "Android package names" in `launch-application` and `application-list`. */
    public const val CATALOG_VENDOR_ID: Int = 0

    private const val MAX_LENGTH = 255
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*([.][A-Za-z][A-Za-z0-9_]*)+$")

    /** True when [id] is a well-formed Android package name (two or more dotted segments, at most 255 characters). */
    public fun isValid(id: String): Boolean = id.length <= MAX_LENGTH && packagePattern.matches(id)
}

/**
 * `application-launcher/launch-application`: open the app whose package is `applicationId`. Only catalog vendor id
 * [ApplicationIds.CATALOG_VENDOR_ID] is spoken, and the TV passes no launch `data`, so a non-empty one is refused
 * rather than silently dropped.
 */
internal fun decodeLaunchApplication(body: JsonObject): Outcome {
    val catalog = body.wholeNumber("catalogVendorId")
        ?: return invalid("catalogVendorId must be an integer")
    val id = (body["applicationId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: return invalid("applicationId must be a string")
    val data = (body["data"] as? JsonPrimitive)?.content
    return when {
        catalog != ApplicationIds.CATALOG_VENDOR_ID.toLong() -> invalid("catalogVendorId must be 0 (Android packages)")
        !ApplicationIds.isValid(id) -> invalid("applicationId is not an Android package name")
        !data.isNullOrEmpty() -> invalid("data is not supported")
        else -> Outcome.Accept(Command.LaunchApp(id))
    }
}
