package com.portoperatingsystem.tv.state

import com.portoperatingsystem.tv.control.InputCapabilities
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Canonical media-player playback states (port1 `PlaybackState`). */
internal enum class Playback { PLAYING, PAUSED, STOPPED, LOADING, ERROR }

/**
 * The foreground media session, as Android's MediaSessionManager reports it. [provider] and [mediaId] are Port OS
 * names for the app and the media, present only when the session's metadata proves them.
 */
internal data class NowPlaying(
    val appPackage: String,
    val title: String?,
    val artist: String?,
    val positionMs: Long?,
    val provider: String? = null,
    val mediaId: String? = null,
    val queue: QueuePlace? = null
)

/** Where the current item sits in the session's queue: [position] (1-based) of [length], as in "image 4 of 20". */
internal data class QueuePlace(val position: Int, val length: Int)

/** The place of the queue item [activeId] among [queueIds]; null when the session has no queue or names no item. */
internal fun queuePlace(queueIds: List<Long>?, activeId: Long?): QueuePlace? {
    val index = queueIds?.indexOf(activeId ?: return null) ?: return null
    return if (index < 0) null else QueuePlace(index + 1, queueIds.size)
}

/**
 * Where playback is now. Android stores the position at the session's last update, so a playing session's position
 * has moved on by the time it is read: advance it by the elapsed time at the playback speed.
 */
internal fun currentPosition(position: Long, updatedAt: Long, speed: Float, now: Long, playing: Boolean): Long? {
    if (position < 0) return null
    if (!playing || updatedAt <= 0) return position
    return position + ((now - updatedAt).coerceAtLeast(0) * speed).toLong()
}

/**
 * What the TV reports about itself. Null fields are unknown and omitted, never guessed. [sampledAtMs] is the wall
 * clock (Unix milliseconds) when the state was read: the host stamps every attribute with it and refuses a different
 * body at the same sample millisecond, so it must be millisecond precise and taken at the read, not at the post.
 */
internal data class StateSnapshot(
    val sampledAtMs: Long,
    val playback: Playback?,
    val nowPlaying: NowPlaying?,
    val volumePercent: Int,
    val muted: Boolean,
    val backgroundLaunchAllowed: Boolean,
    val mediaAccessAllowed: Boolean,
    val accessibilityEnabled: Boolean,
    val input: InputCapabilities,
    val applications: List<String>? = null
) {
    /** The signed claims for the installation webhook (protocol in the README). */
    fun claims(nowSeconds: Long, jti: String = UUID.randomUUID().toString(), requestId: String? = null): JsonObject =
        buildJsonObject {
            put("iat", nowSeconds)
            put("sampledAtMs", sampledAtMs)
            put("jti", jti)
            requestId?.let { put("requestId", it) }
            put(
                "reports",
                buildJsonObject {
                    playback?.let { put("playbackState", it.name) }
                    nowPlaying?.let { media ->
                        put(
                            "nowPlaying",
                            buildJsonObject {
                                put("active", playback == Playback.PLAYING || playback == Playback.PAUSED)
                                put("app", media.appPackage)
                                media.provider?.let { put("provider", it) }
                                media.mediaId?.let { put("mediaId", it) }
                                media.title?.let { put("name", it) }
                                media.artist?.let { put("artist", it) }
                                media.positionMs?.let { put("positionMs", it) }
                                media.queue?.let {
                                    put("queuePosition", it.position)
                                    put("queueLength", it.length)
                                }
                            }
                        )
                    }
                    applications?.let { put("applications", JsonArray(it.map(::JsonPrimitive))) }
                    put("volume", volumePercent)
                    put("muted", muted)
                    put(
                        "input",
                        buildJsonObject {
                            put("touch", input.touch)
                            put("directionalKeys", input.directionalKeys)
                        }
                    )
                    put(
                        "connectivity",
                        buildJsonObject {
                            put("backgroundLaunchAllowed", backgroundLaunchAllowed)
                            put("mediaAccessAllowed", mediaAccessAllowed)
                            put("accessibilityEnabled", accessibilityEnabled)
                        }
                    )
                }
            )
        }
}
