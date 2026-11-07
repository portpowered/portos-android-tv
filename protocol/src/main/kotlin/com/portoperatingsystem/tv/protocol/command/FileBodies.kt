package com.portoperatingsystem.tv.protocol.command

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

private const val VIDEO_MIME_TYPE = "video/mp4"
private const val AUDIO_MIME_PREFIX = "audio/"

/**
 * `media-player/play-video`: `video` is `{url, mimeType, expiresAt}` where `url` is public HTTPS and `mimeType` is
 * `video/mp4`; `loop` is an optional boolean, false when absent. `expiresAt` is the plugin's own bookkeeping: the app
 * downloads at once and never reads it.
 */
internal fun decodePlayVideo(body: JsonObject): Outcome {
    val loop = body.loop() ?: return invalid("loop must be a boolean")
    val file = body["video"] as? JsonObject ?: return invalid("video must be an object")
    val mimeType = file.string("mimeType")?.takeIf { it == VIDEO_MIME_TYPE }
        ?: return invalid("video mimeType must be $VIDEO_MIME_TYPE")
    val url = file.publicUrl() ?: return invalid("video url must be HTTPS with a host and no credentials")
    return Outcome.Accept(Command.PlayFile(url, mimeType, video = true, loop = loop))
}

/** `media-player/play-audio`: `audio` is `{url, mimeType, expiresAt}`, `mimeType` starting `audio/`; it never loops. */
internal fun decodePlayAudio(body: JsonObject): Outcome {
    val file = body["audio"] as? JsonObject ?: return invalid("audio must be an object")
    val mimeType = file.string("mimeType")?.takeIf { it.startsWith(AUDIO_MIME_PREFIX) }
        ?: return invalid("audio mimeType must start with $AUDIO_MIME_PREFIX")
    val url = file.publicUrl() ?: return invalid("audio url must be HTTPS with a host and no credentials")
    return Outcome.Accept(Command.PlayFile(url, mimeType, video = false, loop = false))
}

private fun JsonObject.publicUrl(): String? = string("url")?.takeIf(::isPublicHttps)

// Absent means false; present must be a JSON boolean, so null here means "invalid".
private fun JsonObject.loop(): Boolean? =
    if (containsKey("loop")) (this["loop"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull else false
