package com.portoperatingsystem.tv.protocol.command

import com.portoperatingsystem.tv.protocol.media.DeepLinks
import com.portoperatingsystem.tv.protocol.media.MediaUri
import kotlin.math.roundToInt
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Decodes FCM data messages (protocol v1, see README) into [Command]s. */
public object CommandDecoder {
    /** The protocol version this build speaks. */
    public const val PROTOCOL_VERSION: String = "1"

    private const val MEDIA_PLAYER = "port1/systems/zero/capability-interfaces/media-player/"
    private const val NOTIFICATION = "port1/systems/zero/capability-interfaces/notification/"
    private const val SPEAKER = "port1/systems/zero/capability-interfaces/speaker/"
    private const val KEYPAD_INPUT = "port1/systems/zero/capability-interfaces/keypad-input/"
    private const val TOUCH_INPUT = "port1/systems/zero/capability-interfaces/touch-input/"
    private const val LAUNCHER = "port1/systems/zero/capability-interfaces/application-launcher/"
    private const val SNAPSHOT = "port1/systems/zero/capability-interfaces/snapshot/"
    private const val CONTROL_ROUTES = "port1/systems/zero/capability-interfaces/control-routes-provider/"
    private const val MAX_VOLUME = 100

    private val transports = mapOf(
        MEDIA_PLAYER + "play" to TransportAction.PLAY,
        MEDIA_PLAYER + "pause" to TransportAction.PAUSE,
        MEDIA_PLAYER + "stop" to TransportAction.STOP,
        MEDIA_PLAYER + "next" to TransportAction.NEXT,
        MEDIA_PLAYER + "previous" to TransportAction.PREVIOUS
    )

    /** Decodes [data], the FCM data payload. */
    public fun decode(data: Map<String, String>): Decoded {
        val id = data["id"]?.takeIf { it.isNotBlank() }
        val version = data["v"]
        val outcome = when {
            version != PROTOCOL_VERSION -> Outcome.Reject(Rejection.UnsupportedVersion(version))
            id == null -> Outcome.Reject(Rejection.InvalidMessage("message id is missing"))
            else -> decodeCommand(id, data["type"], data["body"])
        }
        return when (outcome) {
            is Outcome.Accept -> Decoded.Accepted(Delivery(checkNotNull(id), outcome.command))
            is Outcome.Reject -> Decoded.Rejected(id, outcome.rejection)
        }
    }

    // Transports, snapshots and state reads carry nothing the app reads from the body, so they are decoded without one.
    private fun decodeCommand(id: String, type: String?, rawBody: String?): Outcome {
        val transport = transports[type]
        return when {
            transport != null -> Outcome.Accept(Command.Transport(transport))
            type == MEDIA_PLAYER + "start-over" -> Outcome.Accept(Command.Seek(0))
            type == SNAPSHOT + "get-snapshot" -> Outcome.Accept(Command.CaptureScreen(id))
            type == CONTROL_ROUTES + "report-attributes" -> Outcome.Accept(Command.ReportState(id))
            else -> decodeWithBody(type, rawBody)
        }
    }

    private fun decodeWithBody(type: String?, rawBody: String?): Outcome {
        val body = parseBody(rawBody) ?: return invalid("body is not a JSON object")
        return when (type) {
            MEDIA_PLAYER + "play-media" -> playMedia(body)
            MEDIA_PLAYER + "play-uri" -> decodeAudioUri(body)
            MEDIA_PLAYER + "play-video" -> decodePlayVideo(body)
            MEDIA_PLAYER + "play-audio" -> decodePlayAudio(body)
            MEDIA_PLAYER + "seek" -> decodeSeek(body)
            MEDIA_PLAYER + "skip-forward" -> decodeSkip(body, 1L)
            MEDIA_PLAYER + "skip-backward" -> decodeSkip(body, -1L)
            LAUNCHER + "launch-application" -> decodeLaunchApplication(body)
            NOTIFICATION + "notification-trigger" -> decodeNotification(body)
            else -> decodeDeviceControl(type, body)
        }
    }

    private fun decodeDeviceControl(type: String?, body: JsonObject): Outcome = when (type) {
        SPEAKER + "set-volume" -> number(body, "volume", 0..MAX_VOLUME) { Command.SetVolume(it) }
        SPEAKER + "adjust-volume" -> number(body, "volumeDelta", -MAX_VOLUME..MAX_VOLUME) {
            Command.AdjustVolume(it)
        }
        SPEAKER + "set-mute" -> mute(body)
        KEYPAD_INPUT + "send-key" -> decodeKey(body)
        TOUCH_INPUT + "tap" -> decodeTap(body)
        else -> Outcome.Reject(Rejection.UnsupportedType(type))
    }

    private fun parseBody(rawBody: String?): JsonObject? = try {
        rawBody?.let { Json.parseToJsonElement(it) as? JsonObject }
    } catch (_: SerializationException) {
        null
    }

    private fun playMedia(body: JsonObject): Outcome {
        val mediaId = (body["mediaId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return invalid("mediaId must be a string")
        val media = MediaUri.parse(mediaId) ?: return invalid("mediaId is not a portos-media identity")
        val target = DeepLinks.resolve(media) ?: return Outcome.Reject(Rejection.UnsupportedMedia(mediaId))
        return Outcome.Accept(Command.PlayMedia(mediaId, target))
    }

    private fun number(body: JsonObject, key: String, range: IntRange, build: (Int) -> Command): Outcome {
        val value = (body[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            ?: return invalid("$key must be a number")
        val rounded = value.roundToInt()
        return if (rounded in range) Outcome.Accept(build(rounded)) else invalid("$key must be within $range")
    }

    private fun mute(body: JsonObject): Outcome {
        val muted = (body["mute"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: return invalid("mute must be a boolean")
        return Outcome.Accept(Command.SetMute(muted))
    }
}
