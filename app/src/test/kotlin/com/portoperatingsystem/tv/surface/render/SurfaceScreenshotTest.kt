package com.portoperatingsystem.tv.surface.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.portoperatingsystem.tv.surface.PortosTokens
import com.portoperatingsystem.tv.surface.a2ui.A2uiMessage
import com.portoperatingsystem.tv.surface.a2ui.A2uiParser
import com.portoperatingsystem.tv.surface.a2ui.SurfaceModel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of the renderer at the TV's size (a 1920 x 1080 panel laid out as 960 x 540 dp), one per component and
 * variant, plus Timer in both modes. `make test` verifies them against `src/test/screenshots`; after a deliberate
 * change to the renderer or the tokens, `make record-screenshots` rewrites them. The pixel checks at the bottom prove
 * the token colors reach the screen.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w960dp-h540dp-xhdpi")
@RunWith(RobolectricTestRunner::class)
class SurfaceScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-11-08T18:00:00Z").toEpochMilli()

    private val images = ImageSource { url ->
        when (url) {
            "slow" -> awaitCancellation()
            "missing" -> null
            else -> picture()
        }
    }

    /** A 4:3 picture with a dark left half and a light right half, so every fit mode looks different. */
    private fun picture(): ImageBitmap {
        val width = 400
        val height = 300
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                val ring = (x - width / 2) * (x - width / 2) + (y - height / 2) * (y - height / 2) < 90 * 90
                bitmap.setPixel(
                    x,
                    y,
                    if (ring) {
                        0xFFE76E50.toInt()
                    } else if (x <
                        width / 2
                    ) {
                        0xFF274754.toInt()
                    } else {
                        0xFFE8C468.toInt()
                    }
                )
            }
        }
        return bitmap.asImageBitmap()
    }

    private fun model(components: String, data: String = "{}"): SurfaceModel {
        val create = Json.parseToJsonElement(
            """{"version":"1.0","createSurface":{"surfaceId":"s","components":$components,"dataModel":$data}}"""
        )
        return SurfaceModel.create(A2uiParser.parse(create) as A2uiMessage.CreateSurface)
    }

    private fun render(components: String, data: String = "{}") {
        rule.setContent {
            PortosTheme(panelWidthPx = PANEL_WIDTH) {
                CompositionLocalProvider(
                    LocalImageSource provides images,
                    LocalTimerClock provides FrozenTimerClock(now)
                ) { SurfaceScreen(model(components, data)) }
            }
        }
        rule.waitForIdle()
    }

    private fun show(name: String, components: String, data: String = "{}") {
        render(components, data)
        rule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun emptySurface() = show("empty", """[{"id":"root","component":"Column","children":[]}]""")

    @Test
    fun textHeadingsBodyAndCaption() = show(
        "text",
        """[{"id":"root","component":"Column","children":["h1","h2","h3","h4","h5","b","c","m"]},
        {"id":"h1","component":"Text","text":"# Heading one"},
        {"id":"h2","component":"Text","text":"## Heading two"},
        {"id":"h3","component":"Text","text":"### Heading three"},
        {"id":"h4","component":"Text","text":"#### Heading four"},
        {"id":"h5","component":"Text","text":"##### Heading five"},
        {"id":"b","component":"Text","text":"Body text in the Port OS type scale."},
        {"id":"c","component":"Text","variant":"caption","text":"Caption text for the small print"},
        {"id":"m","component":"Text","text":"Some **bold**, *italic* and `code` words\nand a second line"}]"""
    )

    @Test
    fun imageVariantsFromIconToMediumFeature() = show(
        "image-variants",
        """[{"id":"root","component":"Row","align":"end","children":["icon","avatar","sf","mf"]},
        {"id":"icon","component":"Image","url":"a","variant":"icon"},
        {"id":"avatar","component":"Image","url":"a","variant":"avatar","fit":"cover"},
        {"id":"sf","component":"Image","url":"a","variant":"smallFeature"},
        {"id":"mf","component":"Image","url":"a","variant":"mediumFeature"}]"""
    )

    @Test
    fun imageLargeFeature() = show(
        "image-large",
        """[{"id":"root","component":"Image","url":"a","variant":"largeFeature","fit":"cover"}]"""
    )

    @Test
    fun imageHeader() = show(
        "image-header",
        """[{"id":"root","component":"Column","children":["h","t"]},
        {"id":"h","component":"Image","url":"a","variant":"header","fit":"cover"},
        {"id":"t","component":"Text","text":"## Below the header image"}]"""
    )

    @Test
    fun imageFits() = show(
        "image-fits",
        """[{"id":"root","component":"Row","children":["contain","cover","fill","none","down"]},
        {"id":"contain","component":"Image","url":"a","fit":"contain"},
        {"id":"cover","component":"Image","url":"a","fit":"cover"},
        {"id":"fill","component":"Image","url":"a","fit":"fill"},
        {"id":"none","component":"Image","url":"a","fit":"none"},
        {"id":"down","component":"Image","url":"a","fit":"scaleDown"}]"""
    )

    @Test
    fun imageLoadingAndFailed() = show(
        "image-states",
        """[{"id":"root","component":"Row","children":["loading","failed","nourl"]},
        {"id":"loading","component":"Image","url":"slow","variant":"smallFeature"},
        {"id":"failed","component":"Image","url":"missing","variant":"smallFeature"},
        {"id":"nourl","component":"Image","variant":"smallFeature"}]"""
    )

    @Test
    fun icons() = show(
        "icons",
        """[{"id":"root","component":"Row","children":["a","b","c","d","e","f","unknown"]},
        {"id":"a","component":"Icon","name":"home"},{"id":"b","component":"Icon","name":"star"},
        {"id":"c","component":"Icon","name":"play"},{"id":"d","component":"Icon","name":"volumeUp"},
        {"id":"e","component":"Icon","name":"settings"},{"id":"f","component":"Icon","name":"warning"},
        {"id":"unknown","component":"Icon","name":"nope"}]"""
    )

    @Test
    fun rowJustifyAndWeights() = show(
        "row",
        """[{"id":"root","component":"Column","children":["start","between","weights","center"]},
        {"id":"start","component":"Row","children":["t1","t2"]},
        {"id":"between","component":"Row","justify":"spaceBetween","children":["t1","t2"]},
        {"id":"weights","component":"Row","children":["left","right"]},
        {"id":"left","component":"Text","weight":1,"text":"Left, one share"},
        {"id":"right","component":"Text","weight":2,"text":"Right, two shares"},
        {"id":"center","component":"Row","justify":"center","align":"center","children":["t1","big"]},
        {"id":"t1","component":"Text","text":"First"},{"id":"t2","component":"Text","text":"Second"},
        {"id":"big","component":"Text","text":"## Tall"}]"""
    )

    @Test
    fun columnJustifyAlignAndWeights() = show(
        "column",
        """[{"id":"root","component":"Row","children":["a","b","c"]},
        {"id":"a","component":"Column","weight":1,"align":"center","justify":"center","children":["t1","t2"]},
        {"id":"b","component":"Column","weight":1,"justify":"spaceBetween","children":["t1","t2"]},
        {"id":"c","component":"Column","weight":1,"align":"end","children":["top","grow"]},
        {"id":"top","component":"Text","text":"Top"},
        {"id":"grow","component":"Text","weight":1,"text":"Takes the rest"},
        {"id":"t1","component":"Text","text":"One"},{"id":"t2","component":"Text","text":"Two"}]"""
    )

    @Test
    fun justifyStretchSharesTheMainAxisEquallyBetweenChildrenWithoutAWeight() = show(
        "justify-stretch",
        """[{"id":"root","component":"Column","align":"stretch","children":["row","column"]},
        {"id":"row","component":"Row","justify":"stretch","children":["a","b","c"]},
        {"id":"column","component":"Column","weight":1,"justify":"stretch","children":["c","d"]},
        {"id":"a","component":"Card","child":"ta"},{"id":"ta","component":"Text","text":"A, one share"},
        {"id":"b","component":"Card","weight":2,"child":"tb"},{"id":"tb","component":"Text","text":"B, weight 2"},
        {"id":"c","component":"Card","child":"tc"},{"id":"tc","component":"Text","text":"C, one share"},
        {"id":"d","component":"Card","weight":3,"child":"td"},{"id":"td","component":"Text","text":"D, weight 3"}]"""
    )

    @Test
    fun listsClipTheirOverflowAndDoNotScroll() {
        val rowIds = (1..26).joinToString(",") { "\"row$it\"" }
        val rows = (1..26).joinToString(",") { """{"id":"row$it","component":"Text","text":"Row $it"}""" }
        show(
            "list",
            """[{"id":"root","component":"Row","children":["vertical","horizontal"]},
        {"id":"vertical","component":"List","weight":1,"children":[$rowIds]},
        {"id":"horizontal","component":"List","weight":1,"direction":"horizontal","children":["w1","w2","w3"]},
        $rows,
        {"id":"w1","component":"Text","text":"### Wide item one"},
        {"id":"w2","component":"Text","text":"### Wide item two"},
        {"id":"w3","component":"Text","text":"### Wide item three"}]"""
        )
    }

    @Test
    fun cardAndDividers() = show(
        "card-divider",
        """[{"id":"root","component":"Column","children":["card","line","split"]},
        {"id":"card","component":"Card","child":"inner"},
        {"id":"inner","component":"Column","children":["t","c"]},
        {"id":"t","component":"Text","text":"## Inside a card"},
        {"id":"c","component":"Text","variant":"caption","text":"The card has the card radius, padding and shadow"},
        {"id":"line","component":"Divider"},
        {"id":"split","component":"Row","children":["a","bar","b"]},
        {"id":"a","component":"Text","text":"Left"},
        {"id":"bar","component":"Divider","axis":"vertical"},
        {"id":"b","component":"Text","text":"Right"}]"""
    )

    @Test
    fun timerCountdownRunning() = show(
        "timer-countdown",
        """[{"id":"root","component":"Timer","label":"Pasta","mode":"COUNTDOWN",
        "endsAt":"2026-11-08T18:07:30Z","doneText":"Done!"}]"""
    )

    @Test
    fun timerCountdownDone() = show(
        "timer-done",
        """[{"id":"root","component":"Timer","label":"Pasta","mode":"COUNTDOWN",
        "endsAt":"2026-11-08T17:59:00Z","doneText":"Done!"}]"""
    )

    @Test
    fun timerCountUp() = show(
        "timer-countup",
        """[{"id":"root","component":"Timer","label":"Elapsed","mode":"COUNT_UP","startedAt":"2026-11-08T16:45:10Z"}]"""
    )

    @Test
    fun aBoundSurfaceLikeTheKitchenTimer() = show(
        "kitchen-timer",
        """[{"id":"root","component":"Column","align":"stretch","children":["head","card"]},
        {"id":"head","component":"Row","align":"center","children":["logo","title"]},
        {"id":"logo","component":"Image","url":"a","variant":"avatar","fit":"cover"},
        {"id":"title","component":"Text","text":{"@path":"/title"}},
        {"id":"card","component":"Card","child":"body"},
        {"id":"body","component":"Row","children":["timer","side"]},
        {"id":"timer","component":"Timer","weight":1,"label":{"@path":"/label"},"mode":"COUNTDOWN",
        "endsAt":{"@path":"/endsAt"},"doneText":"Dinner is ready"},
        {"id":"side","component":"Column","weight":1,"children":["s1","s2"]},
        {"id":"s1","component":"Text","text":"Boil the water"},
        {"id":"s2","component":"Text","variant":"caption","text":"Then add salt and the pasta"}]""",
        """{"title":"# Kitchen","label":"Pasta","endsAt":"2026-11-08T18:09:00Z"}"""
    )

    /** The window as drawn, read pixel by pixel (the semantics capture is not usable under Robolectric's looper). */
    private fun window(): Bitmap {
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    @Test
    fun theBackgroundAndTheDividerUseTheTokenColors() {
        render("""[{"id":"root","component":"Divider"}]""")

        // The window is white (background) and the divider is the hairline color, inside the 24 dp safe area.
        val window = window()
        assertEquals(PortosTokens.Colors.background.toArgb(), window.getPixel(2, 2))
        val inset = PortosTokens.Display.SAFE_AREA_INSET_DP * 2
        assertEquals(PortosTokens.Colors.hairline.toArgb(), window.getPixel(inset + 100, inset + 1))
    }

    @Test
    fun aDoneTimerUsesTheBrandColor() {
        render(
            """[{"id":"root","component":"Timer","mode":"COUNTDOWN","endsAt":"2026-11-08T17:00:00Z","doneText":"DONE"}]"""
        )

        val image = window()
        val brand = PortosTokens.Colors.primaryEmphasis.toArgb()
        val found = (0 until image.width).any { x -> (0 until 200).any { y -> image.getPixel(x, y) == brand } }
        assertEquals(true, found, "no pixel in the brand color")
    }

    private companion object {
        const val PANEL_WIDTH = 1920
    }
}
