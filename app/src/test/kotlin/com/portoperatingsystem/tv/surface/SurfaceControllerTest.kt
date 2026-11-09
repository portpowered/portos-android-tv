package com.portoperatingsystem.tv.surface

import android.app.Application
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A plain Application: the controller only needs android.util.Log.
@OptIn(ExperimentalCoroutinesApi::class)
@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class SurfaceControllerTest {
    private class Effects : SurfaceEffects {
        var shown = 0
        var closed = 0
        val replies = mutableListOf<Pair<String, JsonObject>>()

        override fun showForeground() {
            shown++
        }

        override fun closeForeground() {
            closed++
        }

        override fun reply(correlationToken: String, payload: JsonObject) {
            replies += correlationToken to payload
        }
    }

    private val scope = TestScope()
    private val effects = Effects()
    private var gaps = 0
    private val display = buildJsonObject { put("widthPx", 1920) }
    private val controller = SurfaceController(
        scope = scope,
        effects = effects,
        display = { display },
        onGap = { gaps++ },
        holdMillis = 5_000,
        timeZone = { "America/Los_Angeles" }
    )

    private fun message(name: String, id: String, sequence: Long?, payload: String, token: String? = null) =
        GatewayMessage(
            name,
            id,
            sequence,
            token,
            Json.parseToJsonElement(payload).jsonObject
        )

    private fun show(id: String = "show-1", surface: String = "s", text: String = "one") = message(
        "show-surface",
        id,
        1,
        """{"surfaceId":"$surface","a2ui":[{"version":"1.0","createSurface":{"surfaceId":"$surface","components":[
        {"id":"root","component":"Text","text":"$text"}]}}]}"""
    )

    private fun update(sequence: Long, text: String, surface: String = "s") = message(
        "update-surface",
        "u$sequence",
        sequence,
        """{"surfaceId":"$surface","a2ui":[{"version":"1.0","updateComponents":{"surfaceId":"$surface","components":[
        {"id":"root","component":"Text","text":"$text"}]}}]}"""
    )

    private fun text() = controller.state.value?.model?.components?.get("root")?.get("text")?.jsonPrimitive?.content

    @Test
    fun showSurfaceHoldsTheSurfaceAndTakesTheForeground() {
        controller.receive(show())

        assertEquals(1, effects.shown)
        assertEquals("one", text())
        assertEquals(SurfacePosition("s", "show-1", 1), controller.position())
        assertEquals(false, controller.state.value?.visible)
    }

    @Test
    fun showSurfaceAppliesEveryMessageInOrder() {
        controller.receive(
            message(
                "show-surface",
                "show-1",
                1,
                """{"surfaceId":"s","a2ui":[
                {"version":"1.0","createSurface":{"surfaceId":"s"}},
                {"version":"1.0","updateComponents":{"surfaceId":"s","components":[
                  {"id":"root","component":"Text","text":{"@path":"/greeting"}}]}},
                {"version":"1.0","updateDataModel":{"surfaceId":"s","path":"/greeting","value":"hi"}}]}"""
            )
        )

        val model = controller.state.value?.model
        assertEquals(1, effects.shown)
        assertNotNull(model?.components?.get("root"))
        assertEquals("hi", model?.dataModel?.jsonObject?.get("greeting")?.jsonPrimitive?.content)
    }

    @Test
    fun aClosedSurfaceCanBeShownAgainUnderTheSameMessageId() {
        controller.receive(show(id = "same"))
        controller.receive(message("close-surface", "c", 2, """{"surfaceId":"s"}"""))
        assertNull(controller.state.value)

        controller.receive(show(id = "same", text = "again"))

        assertEquals("again", text())
        assertEquals(2, effects.shown)
    }

    @Test
    fun noSurfaceMeansNoPosition() {
        assertNull(controller.position())
    }

    @Test
    fun appliesUpdatesInSequence() {
        controller.receive(show())
        controller.receive(update(2, "two"))
        controller.receive(update(3, "three"))

        assertEquals("three", text())
        assertEquals(3, controller.state.value?.sequence)
    }

    @Test
    fun holdsAnOutOfOrderUpdateUntilItsPredecessorArrives() {
        controller.receive(show())
        controller.receive(update(3, "three"))
        assertEquals("one", text())

        controller.receive(update(2, "two"))

        assertEquals("three", text())
        assertEquals(3, controller.state.value?.sequence)
        scope.advanceTimeBy(10_000)
        scope.runCurrent()
        assertEquals(0, gaps)
    }

    @Test
    fun aGapThatNeverFillsReconnectsAfterFiveSeconds() {
        controller.receive(show())
        controller.receive(update(3, "three"))

        scope.advanceTimeBy(4_999)
        scope.runCurrent()
        assertEquals(0, gaps)
        scope.advanceTimeBy(2)
        scope.runCurrent()

        assertEquals(1, gaps)
        assertEquals("one", text())
        // The reconnect resumes from the last applied sequence, so the gateway replays 2 and 3.
        assertEquals(1, controller.position()?.sequence)
    }

    @Test
    fun ignoresADuplicateAfterReplay() {
        controller.receive(show())
        controller.receive(update(2, "two"))
        controller.receive(update(2, "stale"))
        controller.receive(update(1, "stale"))

        assertEquals("two", text())
        assertEquals(2, controller.state.value?.sequence)
    }

    @Test
    fun aReplayedShowIsNotShownTwice() {
        controller.receive(show())
        controller.receive(update(2, "two"))
        controller.receive(show())

        assertEquals(1, effects.shown)
        assertEquals("two", text())
    }

    @Test
    fun aNewShowResetsTheSequenceAndDropsUpdatesForTheOldSurface() {
        controller.receive(show())
        controller.receive(update(2, "two"))

        controller.receive(show(id = "show-2", surface = "t", text = "fresh"))
        controller.receive(update(3, "late", surface = "s"))

        assertEquals("fresh", text())
        assertEquals(SurfacePosition("t", "show-2", 1), controller.position())
        assertEquals(2, effects.shown)
    }

    @Test
    fun aReShowOfTheSameSurfaceIdStartsOverFromOne() {
        controller.receive(show())
        controller.receive(update(2, "two"))

        controller.receive(show(id = "show-2", text = "again"))

        assertEquals("again", text())
        assertEquals(SurfacePosition("s", "show-2", 1), controller.position())
    }

    @Test
    fun aNewShowDropsUpdatesHeldForTheOldSurface() {
        controller.receive(show())
        controller.receive(update(3, "held"))

        controller.receive(show(id = "show-2", text = "fresh"))
        scope.advanceTimeBy(10_000)
        scope.runCurrent()

        assertEquals(0, gaps)
        assertEquals("fresh", text())
    }

    @Test
    fun closeSurfaceRemovesTheSurfaceAndReturnsHome() {
        controller.receive(show())
        controller.receive(message("close-surface", "c", 2, """{"surfaceId":"s"}"""))

        assertNull(controller.state.value)
        assertEquals(1, effects.closed)
    }

    @Test
    fun aDeleteSurfaceMessageClosesTheSurfaceToo() {
        controller.receive(show())
        controller.receive(
            message(
                "update-surface",
                "u2",
                2,
                """{"surfaceId":"s","a2ui":[{"version":"1.0","deleteSurface":{"surfaceId":"s"}}]}"""
            )
        )

        assertNull(controller.state.value)
        assertEquals(1, effects.closed)
    }

    @Test
    fun updatesToTheDataModelReachTheSurface() {
        controller.receive(show())
        controller.receive(
            message(
                "update-surface",
                "u2",
                2,
                """{"surfaceId":"s","a2ui":[{"version":"1.0","updateDataModel":{"surfaceId":"s","path":"/x","value":4}}]}"""
            )
        )

        assertEquals(JsonPrimitive(4), controller.state.value?.model?.dataModel?.jsonObject?.get("x"))
    }

    @Test
    fun aMalformedUpdateIsSkippedButTheSequenceAdvances() {
        controller.receive(show())
        controller.receive(message("update-surface", "u2", 2, """{"surfaceId":"s","a2ui":"nope"}"""))
        controller.receive(update(3, "three"))

        assertEquals("three", text())
    }

    @Test
    fun aMalformedShowIsRefused() {
        controller.receive(message("show-surface", "x", 1, """{"surfaceId":"s","a2ui":[]}"""))

        assertNull(controller.state.value)
        assertEquals(0, effects.shown)
    }

    @Test
    fun anUnparseableShowClearsTheHeldSurfaceSoGetSurfaceReportsTheTruth() {
        controller.receive(show())
        controller.setVisible(true)
        assertEquals(1, effects.shown)

        controller.receive(message("show-surface", "show-2", 1, """{"surfaceId":"s","a2ui":"nope"}"""))

        assertNull(controller.state.value)
        assertNull(controller.position())
        assertEquals(1, effects.closed)
        controller.receive(message("get-surface", "g", null, """{"surfaceId":"s"}""", token = "tok-1"))
        val payload = effects.replies.single().second
        assertEquals(JsonNull, payload.getValue("surfaceId"))
        assertEquals(false, payload.getValue("visible").jsonPrimitive.boolean)
        assertEquals(0, payload.getValue("sequence").jsonPrimitive.long)
        // The refused show's id is not remembered: the same id can be shown once it parses.
        controller.receive(show(id = "show-2", text = "fixed"))
        assertEquals("fixed", text())
    }

    @Test
    fun getSurfaceRepliesWithTheSurfaceAndTheCorrelationToken() {
        controller.receive(show())
        controller.setVisible(true)
        controller.receive(update(2, "two"))

        controller.receive(message("get-surface", "g", null, """{"surfaceId":"s"}""", token = "tok-1"))

        val (token, payload) = effects.replies.single()
        assertEquals("tok-1", token)
        assertEquals("s", payload.getValue("surfaceId").jsonPrimitive.content)
        assertTrue(payload.getValue("visible").jsonPrimitive.boolean)
        assertEquals(2, payload.getValue("sequence").jsonPrimitive.long)
        assertEquals(
            "two",
            payload.getValue("components").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content
        )
        assertEquals(display, payload.getValue("display"))
        assertEquals("America/Los_Angeles", payload.getValue("timeZone").jsonPrimitive.content)
    }

    @Test
    fun getSurfaceForAnotherSurfaceOrNoneReportsItAbsent() {
        controller.receive(message("get-surface", "g1", null, """{}""", token = "a"))
        controller.receive(show())
        controller.receive(message("get-surface", "g2", null, """{"surfaceId":"other"}""", token = "b"))

        assertEquals(2, effects.replies.size)
        for ((_, payload) in effects.replies) {
            assertEquals(kotlinx.serialization.json.JsonNull, payload.getValue("surfaceId"))
            assertEquals(false, payload.getValue("visible").jsonPrimitive.boolean)
            assertEquals(0, payload.getValue("components").jsonArray.size)
        }
    }

    @Test
    fun getSurfaceWithoutATokenIsIgnored() {
        controller.receive(message("get-surface", "g", null, """{}"""))

        assertTrue(effects.replies.isEmpty())
    }

    @Test
    fun hidingTheScreenKeepsTheSurfaceHeld() {
        controller.receive(show())
        controller.setVisible(true)
        controller.setVisible(false)

        assertNotNull(controller.state.value)
        assertEquals(false, controller.state.value?.visible)
    }

    @Test
    fun aReShowKeepsTheScreensVisibility() {
        controller.receive(show())
        controller.setVisible(true)
        controller.receive(show(id = "show-2"))

        assertEquals(true, controller.state.value?.visible)
    }

    @Test
    fun parsesGatewayFramesAndIgnoresOtherNamespaces() {
        val frame = """{"header":{"namespace":"port1/systems/zero/capability-interfaces/generative-ui",
            "name":"update-surface","version":"1.0","id":"m1"},"context":{"sequence":7,"correlationToken":"c"},
            "payload":{"surfaceId":"s"}}"""
        val parsed = assertNotNull(GatewayMessage.parse(frame))
        assertEquals("update-surface", parsed.name)
        assertEquals("m1", parsed.id)
        assertEquals(7L, parsed.sequence)
        assertEquals("c", parsed.correlationToken)

        assertNull(GatewayMessage.parse(frame.replace("capability-interfaces/generative-ui", "plugins/x")))
        assertNull(GatewayMessage.parse("not json"))
        assertNull(GatewayMessage.parse("""{"payload":{}}"""))
    }
}
