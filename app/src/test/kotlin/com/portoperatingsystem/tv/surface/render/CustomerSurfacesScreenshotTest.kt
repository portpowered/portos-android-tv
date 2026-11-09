package com.portoperatingsystem.tv.surface.render

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.portoperatingsystem.tv.surface.GatewayMessage
import com.portoperatingsystem.tv.surface.SurfaceController
import com.portoperatingsystem.tv.surface.SurfaceDisplay
import com.portoperatingsystem.tv.surface.SurfaceEffects
import com.portoperatingsystem.tv.surface.a2ui.id
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The customer surfaces of `customer-surfaces.json`, the same payloads the backend plugin validates (its
 * `testdata/customer-surfaces.json` is a verbatim copy). Each runs through the controller as the gateway delivers it,
 * is read back with `get-surface` as an agent reads it, and is rendered at the TV's size into
 * `src/test/screenshots/customer-<name>.png`.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w960dp-h540dp-xhdpi")
@RunWith(RobolectricTestRunner::class)
class CustomerSurfacesScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-11-08T18:00:00Z").toEpochMilli()

    private val images = ImageSource { picture() }

    private fun picture(): ImageBitmap {
        val bitmap = Bitmap.createBitmap(PICTURE_WIDTH, PICTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(PICTURE_COLOR)
        return bitmap.asImageBitmap()
    }

    private val scenarios: Map<String, JsonObject> by lazy {
        val text = requireNotNull(javaClass.getResource("/customer-surfaces.json")).readText()
        Json.parseToJsonElement(text).jsonObject.getValue("scenarios").jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("name").jsonPrimitive.content }
    }

    private class RecordingEffects : SurfaceEffects {
        val sent = mutableListOf<JsonObject>()

        override fun showForeground() = Unit

        override fun closeForeground() = Unit

        override fun reply(correlationToken: String, payload: JsonObject) {
            sent += payload
        }
    }

    private fun customer(name: String) {
        val calls = requireNotNull(scenarios[name]) { "no scenario $name" }.getValue("calls").jsonArray
        val effects = RecordingEffects()
        val controller = SurfaceController(
            TestScope(),
            effects,
            { SurfaceDisplay.info(PANEL_WIDTH, PANEL_HEIGHT) },
            { error("$name: a sequence gap") }
        )
        val sent = linkedMapOf<String, JsonObject>()
        calls.forEachIndexed { index, call ->
            val payload = call.jsonObject.getValue("payload").jsonObject
            controller.receive(
                GatewayMessage(
                    call.jsonObject.getValue("message").jsonPrimitive.content,
                    "$name-$index",
                    index + 1L,
                    null,
                    payload
                )
            )
            for (message in payload.getValue("a2ui").jsonArray) {
                val body = message.jsonObject["createSurface"] ?: message.jsonObject["updateComponents"] ?: continue
                for (component in body.jsonObject["components"]?.jsonArray.orEmpty()) {
                    sent[component.jsonObject.id()] = component.jsonObject
                }
            }
        }

        controller.receive(GatewayMessage("get-surface", "$name-read", null, "token", JsonObject(emptyMap())))
        val reply = effects.sent.single()
        assertEquals(calls.size.toLong(), reply.getValue("sequence").jsonPrimitive.content.toLong(), name)
        val components = reply.getValue("components").jsonArray.associateBy { it.jsonObject.id() }
        assertEquals(sent.keys, components.keys, "$name: the components read back")
        for ((id, component) in sent) assertEquals(component, components[id], "$name: $id is what was sent last")
        val display = reply.getValue("display").jsonObject
        assertEquals(LAYOUT_WIDTH_DP, display.getValue("widthDp").jsonPrimitive.int)
        assertEquals(LAYOUT_HEIGHT_DP, display.getValue("heightDp").jsonPrimitive.int)
        assertEquals(JsonPrimitive("TEN_FOOT"), display["viewingDistance"])

        val model = assertNotNull(controller.state.value).model
        rule.setContent {
            PortosTheme(panelWidthPx = PANEL_WIDTH) {
                CompositionLocalProvider(
                    LocalImageSource provides images,
                    LocalTimerClock provides FrozenTimerClock(now)
                ) { SurfaceScreen(model) }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/customer-$name.png")
    }

    @Test
    fun aListOfItems() = customer("list")

    @Test
    fun aListOfTimers() = customer("timers")

    @Test
    fun productCards() = customer("product-cards")

    @Test
    fun leftAndRightColumns() = customer("columns")

    @Test
    fun aBoundStatusLine() = customer("bound-status")

    private companion object {
        const val PANEL_WIDTH = 1920
        const val PANEL_HEIGHT = 1080
        const val LAYOUT_WIDTH_DP = 960
        const val LAYOUT_HEIGHT_DP = 540
        const val PICTURE_WIDTH = 400
        const val PICTURE_HEIGHT = 300
        const val PICTURE_COLOR = 0xFF274754.toInt()
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
