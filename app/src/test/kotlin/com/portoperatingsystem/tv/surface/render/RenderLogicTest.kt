package com.portoperatingsystem.tv.surface.render

import androidx.compose.ui.text.font.FontStyle
import com.portoperatingsystem.tv.surface.PortosTokens
import com.portoperatingsystem.tv.surface.a2ui.A2uiMessage
import com.portoperatingsystem.tv.surface.a2ui.A2uiParser
import com.portoperatingsystem.tv.surface.a2ui.SurfaceModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class RenderLogicTest {
    @Test
    fun markdownHeadingsSetTheLevelPerLine() {
        val lines = Markdown.lines("# Big\n## Next\n###### seven\nplain\n#nospace\n##### five")

        assertEquals(
            listOf(
                MarkdownLine(1, "Big"),
                MarkdownLine(2, "Next"),
                MarkdownLine(0, "###### seven"),
                MarkdownLine(0, "plain"),
                MarkdownLine(0, "#nospace"),
                MarkdownLine(5, "five")
            ),
            lines
        )
    }

    @Test
    fun markdownInlineStylesBecomeSpansAndNothingElseSurvives() {
        val styled = Markdown.styled("a **bold** and *it* and _under_ and `code` z")

        assertEquals("a bold and it and under and code z", styled.text)
        val spans = styled.spanStyles
        assertEquals(4, spans.size)
        assertEquals(PortosTokens.Weights.bold, spans[0].item.fontWeight)
        assertEquals(FontStyle.Italic, spans[1].item.fontStyle)
        assertEquals(FontStyle.Italic, spans[2].item.fontStyle)
        assertEquals(androidx.compose.ui.text.font.FontFamily.Monospace, spans[3].item.fontFamily)
    }

    @Test
    fun textWithoutMarkdownIsLeftAlone() {
        assertEquals("1 * 2 = 2", Markdown.styled("1 * 2 = 2").text)
    }

    @Test
    fun durationsAreMinutesAndSecondsUntilAnHour() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:59", formatDuration(59))
        assertEquals("10:05", formatDuration(605))
        assertEquals("59:59", formatDuration(3_599))
        assertEquals("1:00:00", formatDuration(3_600))
        assertEquals("27:46:40", formatDuration(100_000))
        assertEquals("00:00", formatDuration(-5))
    }

    @Test
    fun aCountdownRoundsUpAndIsDoneAtItsEnd() {
        val end = 100_000L

        assertEquals(TimerReading("00:10", false), readTimer("COUNTDOWN", end, null, "Done!", end - 10_000))
        assertEquals(TimerReading("00:10", false), readTimer("COUNTDOWN", end, null, "Done!", end - 9_001))
        assertEquals(TimerReading("00:01", false), readTimer("COUNTDOWN", end, null, "Done!", end - 1))
        assertEquals(TimerReading("Done!", true), readTimer("COUNTDOWN", end, null, "Done!", end))
        assertEquals(TimerReading("Done!", true), readTimer("COUNTDOWN", end, null, "Done!", end + 5_000))
        assertEquals(TimerReading("", true), readTimer("COUNTDOWN", end, null, null, end))
    }

    @Test
    fun aCountUpRunsFromItsStart() {
        assertEquals(TimerReading("00:00", false), readTimer("COUNT_UP", null, 5_000, null, 5_000))
        assertEquals(TimerReading("01:05", false), readTimer("COUNT_UP", null, 5_000, null, 70_999))
        assertEquals(TimerReading("00:00", false), readTimer("COUNT_UP", null, 5_000, null, 1_000))
    }

    @Test
    fun aTimerWithoutItsTimeShowsNothing() {
        assertNull(readTimer("COUNTDOWN", null, 1, "x", 5))
        assertNull(readTimer("COUNT_UP", 1, null, "x", 5))
        assertNull(readTimer("SOMETHING", 1, 1, "x", 5))
        assertNull(readTimer(null, 1, 1, "x", 5))
    }

    private fun bindings(components: String, data: String): Bindings {
        val create = Json.parseToJsonElement(
            """{"version":"1.0","createSurface":{"surfaceId":"s","components":$components,"dataModel":$data}}"""
        )
        return Bindings(SurfaceModel.create(A2uiParser.parse(create) as A2uiMessage.CreateSurface))
    }

    private fun element(text: String) = Json.parseToJsonElement(text)

    @Test
    fun aBindingReadsTheDataModelAndALiteralPassesThrough() {
        val bindings = bindings("""[{"id":"root","component":"Text"}]""", """{"name":"Ada","n":3,"gone":null}""")

        assertEquals("Ada", bindings.string(element("""{"@path":"/name"}"""), ""))
        assertEquals("3", bindings.string(element("""{"@path":"/n"}"""), ""))
        assertEquals("literal", bindings.string(element("\"literal\""), ""))
        assertNull(bindings.string(element("""{"@path":"/missing"}"""), ""))
        assertNull(bindings.string(element("""{"@path":"/gone"}"""), ""))
        assertNull(bindings.string(element("""{"@path":"/"}"""), ""))
        assertNull(bindings.string(null, ""))
    }

    @Test
    fun aTemplateRendersOneChildPerItemWithRelativeBindings() {
        val bindings = bindings(
            """[{"id":"root","component":"Column"}]""",
            """{"rows":[{"label":"a"},{"label":"b"}]}"""
        )

        val children = bindings.children(element("""{"componentId":"row","path":"/rows"}"""), "")

        assertEquals(listOf(Child("row", "/rows/0"), Child("row", "/rows/1")), children)
        assertEquals("b", bindings.string(element("""{"@path":"label"}"""), children[1].scope))
        assertEquals("a", bindings.string(element("""{"@path":"/rows/0/label"}"""), children[1].scope))
    }

    @Test
    fun staticChildrenKeepTheirScopeAndBadChildrenAreSkipped() {
        val bindings = bindings("""[{"id":"root","component":"Column"}]""", "{}")

        assertEquals(
            listOf(Child("a", "/x/0"), Child("b", "/x/0")),
            bindings.children(element("""["a","b"]"""), "/x/0")
        )
        assertEquals(emptyList(), bindings.children(element("""{"componentId":"r","path":"/none"}"""), ""))
        assertEquals(emptyList(), bindings.children(element("\"text\""), ""))
        assertEquals(emptyList(), bindings.children(null, ""))
    }

    @Test
    fun theScaleFactorIsAppliedToTheTokens() {
        assertEquals(1.5f, PortosTokens.TV_SCALE_FACTOR)
        assertEquals(PortosTokens.Spacing.space4, PortosTokens.Spacing.space2 * 2f)
        assertEquals(21f, PortosTokens.Type.body.fontSize.value)
    }
}
