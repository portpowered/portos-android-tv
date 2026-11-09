package com.portoperatingsystem.tv.surface.render

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import com.portoperatingsystem.tv.surface.PortosTokens

/** One line of a Text component: its heading [level] (1 to 5 for `#` to `#####`, 0 for body text) and its text. */
internal data class MarkdownLine(val level: Int, val text: String)

/** The simple Markdown A2UI Text allows: headings per line, and bold, italic and code inline. No links or images. */
internal object Markdown {
    private const val MAX_LEVEL = 5
    private val heading = Regex("^(#{1,$MAX_LEVEL}) +(.*)$")
    private val inline = Regex("""\*\*(.+?)\*\*|\*(.+?)\*|_(.+?)_|`(.+?)`""")

    fun lines(text: String): List<MarkdownLine> = text.split('\n').map { line ->
        val match = heading.matchEntire(line.trimEnd())
        if (match == null) MarkdownLine(0, line) else MarkdownLine(match.groupValues[1].length, match.groupValues[2])
    }

    fun styled(text: String): AnnotatedString = buildAnnotatedString {
        var cursor = 0
        for (match in inline.findAll(text)) {
            append(text.substring(cursor, match.range.first))
            val (bold, star, underscore, code) = match.destructured
            when {
                bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = PortosTokens.Weights.bold)) { append(bold) }
                star.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(star) }
                underscore.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(underscore) }
                else -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            }
            cursor = match.range.last + 1
        }
        append(text.substring(cursor))
    }
}
