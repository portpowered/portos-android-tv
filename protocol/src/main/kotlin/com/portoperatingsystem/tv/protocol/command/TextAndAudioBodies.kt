package com.portoperatingsystem.tv.protocol.command

import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

private const val DEFAULT_DISPLAY_MILLIS = 15_000L
private const val URGENT_DISPLAY_MILLIS = 30_000L

private val urgentPriorities = setOf("HIGH", "URGENT")
private val priorities = setOf("LOW", "NORMAL") + urgentPriorities

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * `notification/notification-trigger`: only `triggerType` IMMEDIATE is shown. HIGH and URGENT notifications stay on
 * screen twice as long as the rest.
 */
internal fun decodeNotification(body: JsonObject): Outcome {
    if (body.string("triggerType") != "IMMEDIATE") return invalid("triggerType must be IMMEDIATE")
    val payload = body["notification"] as? JsonObject ?: return invalid("notification must be an object")
    val title = payload.string("title")?.takeIf { it.isNotBlank() } ?: return invalid("title must be a string")
    val text = payload.string("body")?.takeIf { it.isNotBlank() } ?: return invalid("body must be a string")
    val priority = payload.string("priority")
    if (priority !in priorities) return invalid("priority must be one of $priorities")
    val millis = if (priority in urgentPriorities) URGENT_DISPLAY_MILLIS else DEFAULT_DISPLAY_MILLIS
    return Outcome.Accept(Command.ShowNotification(title, text, millis))
}

/** `media-player/play-uri`: a public HTTPS audio file, finite (sourceType FILE or absent). */
internal fun decodeAudioUri(body: JsonObject): Outcome {
    val uri = body.string("uri") ?: return invalid("uri must be a string")
    val sourceType = body.string("sourceType") ?: "FILE"
    val offset = (body["offsetInMilliseconds"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    return when {
        !isPublicHttps(uri) -> invalid("uri must be HTTPS with a host and no credentials")
        sourceType != "FILE" -> invalid("sourceType must be FILE")
        body.containsKey("offsetInMilliseconds") && (offset == null || offset < 0) ->
            invalid("offsetInMilliseconds must be a non-negative integer")
        else -> Outcome.Accept(Command.PlayUri(uri, body.string("title"), offset ?: 0L))
    }
}

private fun isPublicHttps(uri: String): Boolean = try {
    val parsed = URI(uri)
    parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrEmpty() && parsed.userInfo == null
} catch (_: URISyntaxException) {
    false
}

/**
 * `keypad-input/send-key`: the directional, select, back and home keys go to the accessibility service, and PLAY and
 * PAUSE are the media transports. Any other key is refused; the plugin refuses them first, this is the second guard.
 */
internal fun decodeKey(body: JsonObject): Outcome {
    val code = body.string("keyCode") ?: return invalid("keyCode must be a string")
    val key = NavigationKey.entries.firstOrNull { it.name == code }
    return when {
        key != null -> Outcome.Accept(Command.SendKey(key))
        code == "PLAY" -> Outcome.Accept(Command.Transport(TransportAction.PLAY))
        code == "PAUSE" -> Outcome.Accept(Command.Transport(TransportAction.PAUSE))
        else -> invalid("keyCode $code is not supported")
    }
}

/**
 * `touch-input/tap`: [x][y] are fractions of the screen, so each must be a number from 0 to 1. The plugin refuses
 * anything else first; this is the second guard.
 */
internal fun decodeTap(body: JsonObject): Outcome {
    val x = body.fraction("x") ?: return invalid("x must be a number from 0 to 1")
    val y = body.fraction("y") ?: return invalid("y must be a number from 0 to 1")
    return Outcome.Accept(Command.Tap(x, y))
}

private fun JsonObject.fraction(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it in 0.0..1.0 }
