package com.sovereignedge.pocketpantry.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A small Markdown renderer covering what recipe replies use: headings, bullet and numbered lists, bold and italic.
 * Anything else is shown as plain text, so an unexpected construct degrades gracefully instead of disappearing.
 */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val typography = MaterialTheme.typography
    Column(modifier) {
        var pendingBlank = false
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                pendingBlank = true
                continue
            }
            if (pendingBlank) {
                Spacer(Modifier.height(6.dp))
                pendingBlank = false
            }
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("### ") -> Text(inline(trimmed.removePrefix("### ")), style = typography.titleMedium, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                trimmed.startsWith("## ") -> Text(inline(trimmed.removePrefix("## ")), style = typography.titleLarge, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                trimmed.startsWith("# ") -> Text(inline(trimmed.removePrefix("# ")), style = typography.headlineSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") ->
                    ListLine(marker = "•", body = trimmed.drop(2), indent = (line.length - trimmed.length) / 2)
                NUMBERED.matches(trimmed) -> {
                    val match = NUMBERED.find(trimmed)!!
                    ListLine(marker = match.groupValues[1] + ".", body = match.groupValues[2], indent = (line.length - trimmed.length) / 2)
                }
                trimmed.startsWith("---") && trimmed.all { it == '-' } -> Spacer(Modifier.height(8.dp))
                else -> Text(inline(trimmed.removePrefix("> ")), style = typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ListLine(marker: String, body: String, indent: Int) {
    Row(Modifier.padding(start = (16 * indent).dp, top = 1.dp, bottom = 1.dp)) {
        Text(marker, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(24.dp))
        Text(inline(body), style = MaterialTheme.typography.bodyLarge)
    }
}

private val NUMBERED = Regex("""^(\d{1,3})[.)]\s+(.*)$""")
private val INLINE = Regex("""(\*\*(.+?)\*\*)|(__(.+?)__)|(\*(.+?)\*)|(_(.+?)_)|(`(.+?)`)""")

/** Bold, italic and code spans; the Markdown markers themselves are dropped. */
fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    for (match in INLINE.findAll(text)) {
        append(text.substring(index, match.range.first))
        val (style, inner) = when {
            match.groups[2] != null -> SpanStyle(fontWeight = FontWeight.Bold) to match.groupValues[2]
            match.groups[4] != null -> SpanStyle(fontWeight = FontWeight.Bold) to match.groupValues[4]
            match.groups[6] != null -> SpanStyle(fontStyle = FontStyle.Italic) to match.groupValues[6]
            match.groups[8] != null -> SpanStyle(fontStyle = FontStyle.Italic) to match.groupValues[8]
            else -> SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) to match.groupValues[10]
        }
        pushStyle(style)
        append(inner)
        pop()
        index = match.range.last + 1
    }
    append(text.substring(index))
}

/** The first heading in a reply, or its first non-empty line, as a default title for saving. */
fun firstTitle(markdown: String): String {
    val heading = markdown.lines().firstOrNull { it.trimStart().startsWith("#") }?.trimStart('#', ' ')
    val fallback = markdown.lines().firstOrNull { it.isNotBlank() }?.trim()?.trimStart('*', '-', ' ')?.trimEnd('*')
    return (heading ?: fallback ?: "Recipe").take(80)
}
