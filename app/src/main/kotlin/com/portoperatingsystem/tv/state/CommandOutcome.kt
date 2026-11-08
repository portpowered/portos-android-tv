package com.portoperatingsystem.tv.state

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Why the app could not act on a command that is answered with an outcome; the name is the wire code. */
internal enum class FailureCode {
    /** add-images or remove-images with no slideshow showing. */
    NO_SLIDESHOW,

    /** add-images that would take the slideshow past 30 images. */
    TOO_MANY_IMAGES,

    /** remove-images naming a position past the end. */
    POSITION_OUT_OF_RANGE,

    /** A file or image could not be downloaded. */
    DOWNLOAD_FAILED,

    /** The player failed on the first item before it showed. */
    PLAYBACK_FAILED,

    /** A newer command replaced this one before it took effect. */
    REPLACED
}

/** What the app tells Port OS about a command it was asked to carry out; sent as the report's `outcome` claim. */
internal sealed interface CommandOutcome {
    /** The `outcome` claim. */
    fun claim(): JsonObject

    /**
     * The command took effect. [secondsPerImage] is set for slideshows and [loop] for slideshows and files.
     * [queueLength] is the queue the media session should now show: the report waits for it, and is not sent.
     */
    data class Acted(val secondsPerImage: Int?, val loop: Boolean?, val queueLength: Int) : CommandOutcome {
        override fun claim(): JsonObject = buildJsonObject {
            secondsPerImage?.let { put("secondsPerImage", it) }
            loop?.let { put("loop", it) }
        }
    }

    /** The command could not take effect, for [code], told in [message]. */
    data class Failed(val code: FailureCode, val message: String) : CommandOutcome {
        override fun claim(): JsonObject = buildJsonObject {
            put(
                "failure",
                buildJsonObject {
                    put("code", code.name)
                    put("message", message)
                }
            )
        }
    }
}

/** Where outcomes go: the answer to the command with FCM message id [commandId]. */
internal fun interface OutcomeSink {
    fun answer(commandId: String, outcome: CommandOutcome)
}

/**
 * Makes sure each command id is answered once, and tracks the one command still on its way (downloading or waiting
 * to show) so a command that replaces it answers it first with [FailureCode.REPLACED]. Main thread only.
 */
internal class Answers(private val sink: OutcomeSink) {
    private val answered = LinkedHashSet<String>()
    private var inFlight: String? = null
    private var armed: CommandOutcome.Acted? = null

    /** A command that replaces what plays begins, with [commandId] when it is answered. */
    fun begin(commandId: String?) {
        inFlight?.let { fail(it, FailureCode.REPLACED, "a newer command replaced this one before it took effect") }
        inFlight = commandId
        armed = null
    }

    /** Answers [commandId] with [outcome], once; no id means the command is not answered. */
    fun answer(commandId: String?, outcome: CommandOutcome) {
        if (commandId == null || !answered.add(commandId)) return
        if (answered.size > KEEP) answered.remove(answered.first())
        if (inFlight == commandId) inFlight = null
        sink.answer(commandId, outcome)
    }

    /** Answers [commandId] with a failure. */
    fun fail(commandId: String?, code: FailureCode, message: String) =
        answer(commandId, CommandOutcome.Failed(code, message))

    /** The in-flight command's items are set and playing; it succeeds with [acted] once the first item shows. */
    fun starting(acted: CommandOutcome.Acted) {
        armed = acted
    }

    /** The first item showed. */
    fun shown() {
        val acted = armed ?: return
        armed = null
        answer(inFlight, acted)
    }

    /** The player failed before the first item showed. */
    fun playerFailed(detail: String) {
        if (armed == null) return
        armed = null
        fail(inFlight, FailureCode.PLAYBACK_FAILED, "the player failed before anything showed ($detail)")
    }

    private companion object {
        const val KEEP = 100
    }
}
