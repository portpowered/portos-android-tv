package com.portoperatingsystem.tv.protocol.command

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** `media-player/seek`: jump to `positionMilliseconds`, a non-negative integer. */
internal fun decodeSeek(body: JsonObject): Outcome {
    val position = body.wholeNumber("positionMilliseconds")
        ?: return invalid("positionMilliseconds must be an integer")
    return if (position >= 0) Outcome.Accept(Command.Seek(position)) else invalid("positionMilliseconds must be >= 0")
}

/**
 * `media-player/skip-forward` and `skip-backward`: move by `deltaPositionMilliseconds`, a positive integer. The schema
 * makes it optional, but the TV guesses no default distance, so a skip without one is refused (the plugin refuses it
 * first; this is the second guard).
 */
internal fun decodeSkip(body: JsonObject, direction: Long): Outcome {
    val delta = body.wholeNumber("deltaPositionMilliseconds")
        ?: return invalid("deltaPositionMilliseconds must be an integer")
    return if (delta > 0) {
        Outcome.Accept(Command.Skip(direction * delta))
    } else {
        invalid("deltaPositionMilliseconds must be > 0")
    }
}

internal fun JsonObject.wholeNumber(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
