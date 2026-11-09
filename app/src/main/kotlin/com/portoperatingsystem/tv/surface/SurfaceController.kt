package com.portoperatingsystem.tv.surface

import android.util.Log
import com.portoperatingsystem.tv.surface.a2ui.A2uiException
import com.portoperatingsystem.tv.surface.a2ui.A2uiMessage
import com.portoperatingsystem.tv.surface.a2ui.A2uiParser
import com.portoperatingsystem.tv.surface.a2ui.SurfaceModel
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** One gateway delivery (the design's message shape): its name, ordering context and payload. */
internal data class GatewayMessage(
    val name: String,
    val id: String,
    val sequence: Long?,
    val correlationToken: String?,
    val payload: JsonObject
) {
    companion object {
        /** Parses a text frame; null when it is not a `generative-ui` message. */
        fun parse(text: String): GatewayMessage? = try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
            val header = root.getValue("header").jsonObject
            val context = root["context"] as? JsonObject
            val name = header.getValue("name").stringOrNull()
            val namespace = header["namespace"].stringOrNull()
            if (name == null || namespace?.endsWith("/generative-ui") != true) {
                null
            } else {
                GatewayMessage(
                    name = name,
                    id = header["id"].stringOrNull().orEmpty(),
                    sequence = (context?.get("sequence") as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
                    correlationToken = context?.get("correlationToken").stringOrNull(),
                    payload = root["payload"] as? JsonObject ?: JsonObject(emptyMap())
                )
            }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: NoSuchElementException) {
            null
        }

        private fun kotlinx.serialization.json.JsonElement?.stringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** The surface the TV holds: its model, the last applied sequence and whether it is on screen. */
internal data class SurfaceState(
    val model: SurfaceModel,
    /** The `header.id` of the `show-surface` that started this surface. */
    val showId: String,
    val sequence: Long,
    val visible: Boolean
)

/** What the TV sends on reconnect so the gateway replays only what it missed: all three, or none. */
internal data class SurfacePosition(val surfaceId: String, val showId: String, val sequence: Long)

/** What the controller asks of the rest of the app. */
internal interface SurfaceEffects {
    /** A surface was shown: bring the surface activity to the foreground. */
    fun showForeground()

    /** The surface was closed: remove it and return the TV to the home screen. */
    fun closeForeground()

    /** Answers a `get-surface`: posts [payload] as `get-surface-response` with [correlationToken]. */
    fun reply(correlationToken: String, payload: JsonObject)
}

/**
 * Applies the gateway's `show-surface`, `update-surface`, `close-surface` and `get-surface` messages to the one
 * surface the TV holds. The gateway sequences each surface; this applies `sequence = last + 1` only: a higher
 * number waits up to [holdMillis] for its predecessors and then asks [onGap] for a reconnect (which replays from
 * the last applied sequence), a lower or equal number is a duplicate and is ignored. `show-surface` starts a new
 * sequence, and updates for any other surface are dropped.
 */
internal class SurfaceController(
    private val scope: CoroutineScope,
    private val effects: SurfaceEffects,
    private val display: () -> JsonObject,
    private val onGap: () -> Unit,
    private val holdMillis: Long = HOLD_MILLIS,
    private val timeZone: () -> String = { ZoneId.systemDefault().id }
) {
    private val lock = Any()
    private val held = sortedMapOf<Long, GatewayMessage>()
    private var holdTimer: Job? = null
    private var shownMessageId: String? = null
    private val mutableState = MutableStateFlow<SurfaceState?>(null)

    val state: StateFlow<SurfaceState?> = mutableState

    /** The surface id, show id and last applied sequence to resume from, or null when the TV holds no surface. */
    fun position(): SurfacePosition? =
        mutableState.value?.let { SurfacePosition(it.model.surfaceId, it.showId, it.sequence) }

    /** The surface activity reports whether it is on screen. */
    fun setVisible(visible: Boolean) {
        mutableState.value = mutableState.value?.copy(visible = visible)
    }

    fun receive(message: GatewayMessage) = synchronized(lock) {
        when (message.name) {
            "show-surface" -> show(message)
            "update-surface", "close-surface" -> sequenced(message)
            "get-surface" -> getSurface(message)
            else -> Log.w(TAG, "ignored ${message.name}")
        }
    }

    private fun show(message: GatewayMessage) {
        if (message.id.isNotEmpty() && message.id == shownMessageId) return
        val messages = try {
            A2uiParser.parseAll(message.payload["a2ui"])
        } catch (error: A2uiException) {
            // The gateway holds this show-surface as the surface's log head, so the TV cannot keep the surface it had:
            // the old one would be reported by get-surface although the log no longer describes it.
            Log.w(TAG, "show-surface unparseable, held surface cleared: ${error.message}")
            if (mutableState.value != null) close()
            return
        }
        val first = messages.indexOfFirst { it is A2uiMessage.CreateSurface }
        if (first < 0) return
        val create = messages[first] as A2uiMessage.CreateSurface
        // The messages after the createSurface (updateComponents, updateDataModel) build on it, in order.
        val model = messages.drop(first + 1).fold(SurfaceModel.create(create)) { model, next ->
            if (next.surfaceId == model.surfaceId) model.apply(next) else model
        }
        shownMessageId = message.id
        held.clear()
        holdTimer?.cancel()
        // A replacement keeps the screen's visibility: an activity that is already showing is not started again.
        val visible = mutableState.value?.visible ?: false
        mutableState.value = SurfaceState(model, message.id, message.sequence ?: 1L, visible)
        effects.showForeground()
    }

    private fun sequenced(message: GatewayMessage) {
        val current = mutableState.value ?: return
        val sequence = message.sequence ?: return
        if (message.payload["surfaceId"].asString() != current.model.surfaceId) return
        when {
            sequence <= current.sequence -> Unit
            sequence == current.sequence + 1 -> {
                apply(message, sequence)
                drain()
            }
            else -> {
                held[sequence] = message
                startHoldTimer()
            }
        }
    }

    private fun drain() {
        while (true) {
            val next = mutableState.value?.let { held.remove(it.sequence + 1) } ?: break
            apply(next, checkNotNull(next.sequence))
        }
        holdTimer?.cancel()
        if (held.isNotEmpty()) startHoldTimer()
    }

    private fun apply(message: GatewayMessage, sequence: Long) {
        val current = mutableState.value ?: return
        if (message.name == "close-surface") {
            close()
            return
        }
        val model = try {
            A2uiParser.parseAll(message.payload["a2ui"]).fold(current.model) { model, update ->
                if (update is A2uiMessage.DeleteSurface) return close() else model.apply(update)
            }
        } catch (error: A2uiException) {
            Log.w(TAG, "update-surface $sequence skipped: ${error.message}")
            current.model
        }
        mutableState.value = current.copy(model = model, sequence = sequence)
    }

    private fun close() {
        held.clear()
        holdTimer?.cancel()
        shownMessageId = null
        mutableState.value = null
        effects.closeForeground()
    }

    private fun startHoldTimer() {
        if (holdTimer?.isActive == true) return
        holdTimer = scope.launch {
            delay(holdMillis)
            val gap = synchronized(lock) { held.isNotEmpty().also { if (it) held.clear() } }
            if (gap) onGap()
        }
    }

    private fun getSurface(message: GatewayMessage) {
        val token = message.correlationToken ?: return
        val current = mutableState.value
        val wanted = message.payload["surfaceId"].asString()
        val shown = current?.takeIf { wanted == null || wanted == it.model.surfaceId }
        effects.reply(
            token,
            buildJsonObject {
                if (shown == null) put("surfaceId", JsonNull) else put("surfaceId", shown.model.surfaceId)
                put("visible", shown?.visible ?: false)
                put("sequence", shown?.sequence ?: 0L)
                put("components", shown?.model?.componentList() ?: kotlinx.serialization.json.JsonArray(emptyList()))
                // The schema types dataModel as an object; a model replaced by a scalar is reported empty.
                put("dataModel", (shown?.model?.dataModel as? JsonObject) ?: JsonObject(emptyMap()))
                put("display", display())
                // The TV's IANA zone, so an agent can turn "at 7 pm" into the UTC endsAt of a Timer.
                put("timeZone", timeZone())
            }
        )
    }

    private fun kotlinx.serialization.json.JsonElement?.asString(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val TAG = "PortOsSurface"
        const val HOLD_MILLIS = 5_000L
    }
}
