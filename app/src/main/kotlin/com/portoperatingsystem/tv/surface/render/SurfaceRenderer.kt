package com.portoperatingsystem.tv.surface.render

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.portoperatingsystem.tv.surface.PortosTokens
import com.portoperatingsystem.tv.surface.a2ui.SurfaceModel
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

private const val ROOT = "root"
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val MILLIS = 1000L

/** What the TV shows for a surface: the whole screen, inside the safe-area inset. Nothing here takes focus. */
@Composable
internal fun SurfaceScreen(model: SurfaceModel?, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize()
            .background(PortosTokens.Colors.background)
            .padding(PortosTokens.Display.safeAreaInset)
    ) {
        if (model != null) Node(Renderer(Bindings(model)), ROOT, Modifier)
    }
}

private class Renderer(val bindings: Bindings)

/** Renders one component, found by id, with [modifier] from its parent (which carries the weight). */
@Composable
private fun Node(renderer: Renderer, id: String, modifier: Modifier) {
    val component = renderer.bindings.component(id) ?: return
    when ((component["component"] as? JsonPrimitive)?.content) {
        "Text" -> TextNode(renderer, component, modifier)
        "Image" -> ImageNode(renderer, component, modifier)
        "Icon" -> IconNode(renderer, component, modifier)
        "Row" -> RowNode(renderer, component, modifier)
        "Column" -> ColumnNode(renderer, component, modifier)
        "List" -> ListNode(renderer, component, modifier)
        "Card" -> CardNode(renderer, component, modifier)
        "Divider" -> DividerNode(component, modifier)
        "Timer" -> TimerNode(renderer, component, modifier)
        else -> Unit
    }
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.content

/** The weight a parent gives this child's share of its free space, or null for none. */
private fun JsonObject.weight(): Float? = (get("weight") as? JsonPrimitive)?.doubleOrNull?.toFloat()?.takeIf { it > 0f }

@Suppress("MagicNumber") // The levels are Markdown's own heading depths.
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> PortosTokens.Type.headline
    2 -> PortosTokens.Type.pageTitle
    3 -> PortosTokens.Type.sectionHeading
    else -> PortosTokens.Type.subsectionHeading
}

@Composable
private fun TextNode(renderer: Renderer, component: JsonObject, modifier: Modifier) {
    val text = renderer.bindings.string(component["text"]).orEmpty()
    val caption = component.text("variant") == "caption"
    Column(modifier) {
        for (line in Markdown.lines(text)) {
            val style = when {
                line.level > 0 -> headingStyle(line.level)
                caption -> PortosTokens.Type.caption
                else -> PortosTokens.Type.body
            }
            Text(
                text = Markdown.styled(line.text),
                style = style,
                color = if (caption) PortosTokens.Colors.mutedForeground else PortosTokens.Colors.foreground
            )
        }
    }
}

private sealed interface Loaded {
    data object Loading : Loaded

    data object Failed : Loaded

    data class Pixels(val bitmap: ImageBitmap) : Loaded
}

private fun fit(name: String?): ContentScale = when (name) {
    "cover" -> ContentScale.Crop
    "fill" -> ContentScale.FillBounds
    "none" -> ContentScale.None
    "scaleDown" -> ContentScale.Inside
    else -> ContentScale.Fit
}

private data class ImageBox(val modifier: Modifier, val shape: Shape)

private fun imageBox(variant: String?): ImageBox = when (variant) {
    "icon" -> ImageBox(Modifier.size(PortosTokens.Sizes.imageIcon), PortosTokens.Shapes.card)
    "avatar" -> ImageBox(Modifier.size(PortosTokens.Sizes.imageAvatar), CircleShape)
    "smallFeature" -> ImageBox(Modifier.size(PortosTokens.Sizes.imageSmallFeature), PortosTokens.Shapes.card)
    "largeFeature" -> ImageBox(Modifier.size(PortosTokens.Sizes.imageLargeFeature), PortosTokens.Shapes.card)
    "header" -> ImageBox(Modifier.fillMaxWidth().height(PortosTokens.Sizes.imageHeaderHeight), PortosTokens.Shapes.card)
    else -> ImageBox(Modifier.size(PortosTokens.Sizes.imageMediumFeature), PortosTokens.Shapes.card)
}

@Composable
private fun ImageNode(renderer: Renderer, component: JsonObject, modifier: Modifier) {
    val url = renderer.bindings.string(component["url"])
    val description = renderer.bindings.string(component["description"])
    val source = LocalImageSource.current
    val loaded by produceState<Loaded>(Loaded.Loading, url, source) {
        value = Loaded.Loading
        val bitmap = url?.let { source.load(it) }
        value = if (bitmap == null) Loaded.Failed else Loaded.Pixels(bitmap)
    }
    val box = imageBox(component.text("variant"))
    Box(modifier.then(box.modifier).clip(box.shape).background(PortosTokens.Colors.card)) {
        when (val state = loaded) {
            Loaded.Loading -> Unit
            Loaded.Failed -> Placeholder()
            is Loaded.Pixels -> Image(
                bitmap = state.bitmap,
                contentDescription = description,
                contentScale = fit(component.text("fit")),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun Placeholder() {
    Box(Modifier.fillMaxSize().background(PortosTokens.Colors.muted), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Filled.BrokenImage,
            contentDescription = null,
            tint = PortosTokens.Colors.tertiaryForeground,
            modifier = Modifier.size(PortosTokens.Sizes.iconMd)
        )
    }
}

@Composable
private fun IconNode(renderer: Renderer, component: JsonObject, modifier: Modifier) {
    val vector = IconNames.vector(renderer.bindings.string(component["name"]))
    if (vector == null) {
        Box(modifier.size(PortosTokens.Sizes.iconMd))
    } else {
        Icon(
            imageVector = vector,
            contentDescription = null,
            tint = PortosTokens.Colors.foreground,
            modifier = modifier.size(PortosTokens.Sizes.iconMd)
        )
    }
}

private fun horizontal(justify: String?): Arrangement.Horizontal = when (justify) {
    "center" -> Arrangement.spacedBy(PortosTokens.Spacing.space3, Alignment.CenterHorizontally)
    "end" -> Arrangement.spacedBy(PortosTokens.Spacing.space3, Alignment.End)
    "spaceBetween" -> Arrangement.SpaceBetween
    "spaceAround" -> Arrangement.SpaceAround
    "spaceEvenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.spacedBy(PortosTokens.Spacing.space3, Alignment.Start)
}

private fun vertical(justify: String?): Arrangement.Vertical = when (justify) {
    "center" -> Arrangement.spacedBy(PortosTokens.Spacing.space3, Alignment.CenterVertically)
    "end" -> Arrangement.spacedBy(PortosTokens.Spacing.space3, Alignment.Bottom)
    "spaceBetween" -> Arrangement.SpaceBetween
    "spaceAround" -> Arrangement.SpaceAround
    "spaceEvenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.spacedBy(PortosTokens.Spacing.space3, Alignment.Top)
}

private fun crossVertical(align: String?): Alignment.Vertical = when (align) {
    "center" -> Alignment.CenterVertically
    "end" -> Alignment.Bottom
    else -> Alignment.Top
}

private fun crossHorizontal(align: String?): Alignment.Horizontal = when (align) {
    "center" -> Alignment.CenterHorizontally
    "end" -> Alignment.End
    else -> Alignment.Start
}

/** A child's own weight in a Row or Column; with `justify: stretch`, a child without one takes an equal share. */
@Composable
private fun RowScope.Weighted(renderer: Renderer, id: String, stretch: Boolean) {
    val weight = renderer.bindings.component(id)?.weight() ?: if (stretch) 1f else null
    Node(renderer, id, if (weight == null) Modifier else Modifier.weight(weight))
}

@Composable
private fun ColumnScope.Weighted(renderer: Renderer, id: String, stretch: Boolean) {
    val weight = renderer.bindings.component(id)?.weight() ?: if (stretch) 1f else null
    Node(renderer, id, if (weight == null) Modifier else Modifier.weight(weight))
}

@Composable
private fun RowNode(renderer: Renderer, component: JsonObject, modifier: Modifier) {
    val crossStretch = component.text("align") == "stretch"
    val stretch = component.text("justify") == "stretch"
    Row(
        modifier = modifier.fillMaxWidth().then(if (crossStretch) Modifier.fillMaxHeight() else Modifier),
        horizontalArrangement = horizontal(component.text("justify")),
        verticalAlignment = crossVertical(component.text("align"))
    ) {
        for (item in renderer.bindings.children(component["children"])) Weighted(renderer, item, stretch)
    }
}

@Composable
private fun ColumnNode(renderer: Renderer, component: JsonObject, modifier: Modifier) {
    val crossStretch = component.text("align") == "stretch"
    val stretch = component.text("justify") == "stretch"
    Column(
        modifier = modifier.then(if (crossStretch) Modifier.fillMaxWidth() else Modifier),
        verticalArrangement = vertical(component.text("justify")),
        horizontalAlignment = crossHorizontal(component.text("align"))
    ) {
        for (item in renderer.bindings.children(component["children"])) Weighted(renderer, item, stretch)
    }
}

/** A List never scrolls: nothing on the TV can be focused. Children lay out at full size; the overflow is cut. */
@Composable
private fun ListNode(renderer: Renderer, component: JsonObject, modifier: Modifier) {
    val items = renderer.bindings.children(component["children"])
    if (component.text("direction") == "horizontal") {
        Box(modifier.fillMaxWidth().clipToBounds()) {
            Row(
                Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true),
                horizontalArrangement = Arrangement.spacedBy(PortosTokens.Spacing.space3),
                verticalAlignment = crossVertical(component.text("align"))
            ) { for (item in items) Node(renderer, item, Modifier) }
        }
    } else {
        Box(modifier.clipToBounds()) {
            Column(
                Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true),
                verticalArrangement = Arrangement.spacedBy(PortosTokens.Spacing.space3),
                horizontalAlignment = crossHorizontal(component.text("align"))
            ) { for (item in items) Node(renderer, item, Modifier) }
        }
    }
}

@Composable
private fun CardNode(renderer: Renderer, component: JsonObject, modifier: Modifier) {
    val shape = PortosTokens.Shapes.card
    Box(
        modifier.shadow(PortosTokens.Elevation.raised, shape)
            .clip(shape)
            .background(PortosTokens.Colors.card)
            .padding(PortosTokens.Sizes.cardPadding)
    ) {
        component.text("child")?.let { Node(renderer, it, Modifier) }
    }
}

@Composable
private fun DividerNode(component: JsonObject, modifier: Modifier) {
    val line = if (component.text("axis") == "vertical") {
        Modifier.fillMaxHeight().width(PortosTokens.Sizes.dividerThickness)
    } else {
        Modifier.fillMaxWidth().height(PortosTokens.Sizes.dividerThickness)
    }
    Box(modifier.then(line).background(PortosTokens.Colors.hairline))
}

/** The text a Timer shows: `H:MM:SS` from an hour up, `MM:SS` below. */
internal fun formatDuration(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%02d:%02d".format(minutes, rest)
}

private fun parseMillis(value: String?): Long? =
    value?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** The display of a Timer at [now], and whether a countdown has finished. A timer with no usable time shows nothing. */
internal data class TimerReading(val text: String, val done: Boolean)

internal fun readTimer(mode: String?, endsAt: Long?, startedAt: Long?, doneText: String?, now: Long): TimerReading? =
    when {
        mode == "COUNT_UP" && startedAt != null -> TimerReading(formatDuration((now - startedAt) / MILLIS), false)
        mode == "COUNTDOWN" && endsAt != null ->
            if (endsAt <= now) {
                TimerReading(doneText.orEmpty(), true)
            } else {
                // Round up so the last second reads 00:01 and the timer is done exactly at endsAt.
                TimerReading(formatDuration((endsAt - now + MILLIS - 1) / MILLIS), false)
            }
        else -> null
    }

@Composable
private fun TimerNode(renderer: Renderer, component: JsonObject, modifier: Modifier) {
    val clock = LocalTimerClock.current
    var now by remember(clock) { mutableLongStateOf(clock.nowMillis()) }
    LaunchedEffect(clock) {
        while (true) {
            clock.awaitTick()
            now = clock.nowMillis()
        }
    }
    val label = renderer.bindings.string(component["label"])
    val reading = readTimer(
        component.text("mode"),
        parseMillis(renderer.bindings.string(component["endsAt"])),
        parseMillis(renderer.bindings.string(component["startedAt"])),
        renderer.bindings.string(component["doneText"]),
        now
    ) ?: return
    val color = if (reading.done) PortosTokens.Colors.primaryEmphasis else PortosTokens.Colors.foreground
    Column(modifier) {
        label?.let {
            Text(it, style = PortosTokens.Type.label, color = PortosTokens.Colors.mutedForeground)
        }
        Text(reading.text, style = PortosTokens.Type.headline, color = color)
    }
}
