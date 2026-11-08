package com.portoperatingsystem.tv.protocol.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** The most images a slideshow holds, and so the most any one message names. */
public const val MAX_SLIDESHOW_IMAGES: Int = 30

private val IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
private const val DEFAULT_SECONDS_PER_IMAGE = 8
private const val MIN_SECONDS_PER_IMAGE = 2
private const val MAX_SECONDS_PER_IMAGE = 60
private val SECONDS_PER_IMAGE = MIN_SECONDS_PER_IMAGE..MAX_SECONDS_PER_IMAGE
private val COUNTS = 1..MAX_SLIDESHOW_IMAGES

/** Decodes the slideshow messages, or returns null for any other [type] under media-player. */
internal fun decodeSlideshow(type: String, body: JsonObject): Outcome? = when (type) {
    "show-images" -> decodeShowImages(body)
    "add-images" -> decodeAddImages(body)
    "remove-images" -> decodeRemoveImages(body)
    else -> null
}

/**
 * `media-player/show-images`, one part: `part` is `{set, index, count}`, `images` a list of `{url, mimeType,
 * expiresAt}` (public HTTPS, `image/jpeg`, `image/png` or `image/webp`), optional `title`, `secondsPerImage` (2–60,
 * default 8) and `loop` (default false).
 */
private fun decodeShowImages(body: JsonObject): Outcome {
    val part = body.part() ?: return invalid("part must be {set, index, count} with 0 <= index < count <= 30")
    val images = body.images() ?: return invalid(IMAGES_RULE)
    val title = body.title() ?: return invalid("title must be a string")
    val loop = body.loop() ?: return invalid("loop must be a boolean")
    val seconds = if (body.containsKey("secondsPerImage")) {
        body.int("secondsPerImage")?.takeIf { it in SECONDS_PER_IMAGE }
            ?: return invalid("secondsPerImage must be an integer within $SECONDS_PER_IMAGE")
    } else {
        DEFAULT_SECONDS_PER_IMAGE
    }
    return Outcome.Accept(Command.ShowImages(part, images, title.value, seconds, loop))
}

/** `media-player/add-images`, one part: `part`, `images` as for show-images, and an optional 1-based `position`. */
private fun decodeAddImages(body: JsonObject): Outcome {
    val part = body.part() ?: return invalid("part must be {set, index, count} with 0 <= index < count <= 30")
    val images = body.images() ?: return invalid(IMAGES_RULE)
    val position = if (body.containsKey("position")) {
        body.int("position")?.takeIf { it in COUNTS } ?: return invalid("position must be an integer within $COUNTS")
    } else {
        null
    }
    return Outcome.Accept(Command.AddImages(part, images, position))
}

/** `media-player/remove-images`: `positions`, 1–30 distinct 1-based positions. */
private fun decodeRemoveImages(body: JsonObject): Outcome {
    val values = (body["positions"] as? JsonArray)?.map { (it as? JsonPrimitive)?.integer() }
    val positions = values?.filterNotNull()?.takeIf { list ->
        list.size == values.size && list.size in COUNTS && list.all { it in COUNTS } && list.toSet().size == list.size
    } ?: return invalid("positions must be 1 to 30 distinct integers within $COUNTS")
    return Outcome.Accept(Command.RemoveImages(positions))
}

private const val IMAGES_RULE =
    "images must be 1 to 30 objects with an HTTPS url and a mimeType of image/jpeg, image/png or image/webp"

private fun JsonObject.part(): Part? {
    val part = this["part"] as? JsonObject ?: return null
    val set = part.string("set")?.takeIf { it.isNotBlank() } ?: return null
    val count = part.int("count")?.takeIf { it in COUNTS } ?: return null
    val index = part.int("index")?.takeIf { it in 0 until count } ?: return null
    return Part(set, index, count)
}

private fun JsonObject.images(): List<ImageFile>? {
    val list = (this["images"] as? JsonArray)?.takeIf { it.size in COUNTS } ?: return null
    val images = list.mapNotNull { element ->
        val image = element as? JsonObject
        val mimeType = image?.string("mimeType")?.takeIf { it in IMAGE_MIME_TYPES }
        val url = image?.publicUrl()
        if (mimeType != null && url != null) ImageFile(url, mimeType) else null
    }
    return images.takeIf { it.size == list.size }
}

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.integer()

// A JSON integer: not a string, and no fraction.
private fun JsonPrimitive.integer(): Int? = takeUnless { it.isString }?.intOrNull
