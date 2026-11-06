package com.maxi.glosa.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Gemini Nano writes light Markdown; this renders the bold, italics, lists and [n] citations it uses. */
@Composable
fun AnswerText(text: String, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Text(remember(text, accent) { renderMarkdown(text, accent) }, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
}

private val BULLET = Regex("^\\s*[*\\-•]\\s+")
private val HEADING = Regex("^\\s*#{1,4}\\s+")
private val INLINE = Regex("\\*\\*(.+?)\\*\\*|\\*(\\S(?:.*?\\S)?)\\*|(\\[\\d+(?:,\\s*\\d+)*])")

private fun renderMarkdown(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
    text.trim().lines().forEachIndexed { index, line ->
        if (index > 0) append('\n')
        val bullet = BULLET.find(line)
        val heading = HEADING.find(line)
        when {
            bullet != null -> {
                append("•  ")
                appendInline(line.substring(bullet.range.last + 1), accent)
            }
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInline(line.substring(heading.range.last + 1), accent)
            }
            else -> appendInline(line, accent)
        }
    }
}

private fun AnnotatedString.Builder.appendInline(line: String, accent: Color) {
    var cursor = 0
    INLINE.findAll(line).forEach { match ->
        append(line.substring(cursor, match.range.first))
        val (bold, italic, citation) = match.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
            else -> withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) { append(citation) }
        }
        cursor = match.range.last + 1
    }
    append(line.substring(cursor))
}
