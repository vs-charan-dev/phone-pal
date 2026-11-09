package com.phonepal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** One block of a chat answer. */
private sealed interface Block {
    data class Heading(val text: String) : Block
    data class Paragraph(val text: String) : Block
    /** [marker] is "•" or "3." */
    data class ListItem(val marker: String, val level: Int, val text: String) : Block
    data class Quote(val text: String) : Block
    data class Code(val text: String) : Block
}

/**
 * Renders the markdown Gemini writes in chat answers: headings, bullet / numbered lists (nested),
 * quotes, code, **bold**, *italic*, `code`. Blank lines and --- rules only separate blocks, and
 * markers that don't pair up are dropped instead of being shown.
 */
@Composable
fun ChatMarkdown(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parse(markdown) }
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val codeBg = colors.surfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { i, b ->
            when (b) {
                is Block.Heading -> Text(
                    inline(b.text, codeBg),
                    style = type.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp),
                )
                is Block.Paragraph -> Text(inline(b.text, codeBg), style = type.bodyLarge)
                is Block.ListItem -> Row(Modifier.padding(start = (b.level * 18).dp)) {
                    Text(
                        b.marker, style = type.bodyLarge, color = colors.onSurfaceVariant,
                        modifier = Modifier.widthIn(min = if (b.marker == "•") 18.dp else 26.dp),
                    )
                    Text(inline(b.text, codeBg), style = type.bodyLarge)
                }
                is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(colors.outlineVariant))
                    Text(
                        inline(b.text, codeBg), style = type.bodyLarge, fontStyle = FontStyle.Italic,
                        color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp),
                    )
                }
                is Block.Code -> Box(
                    Modifier.fillMaxWidth().background(codeBg, RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState()).padding(10.dp)
                ) { Text(b.text, style = type.bodyMedium, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

private val HEADING = Regex("""^\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$""")
private val BULLET = Regex("""^(\s*)[-*+•]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
private val QUOTE = Regex("""^\s*>\s?(.*)$""")
private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val FENCE = Regex("""^\s*```""")

private fun parse(md: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += Block.Paragraph(para.toString().trim())
        para.clear()
    }
    val lines = md.replace("\r", "").lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            FENCE.containsMatchIn(line) -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !FENCE.containsMatchIn(lines[i])) code.appendLine(lines[i++])
                if (code.isNotBlank()) out += Block.Code(code.toString().trimEnd())
            }
            line.isBlank() || RULE.matches(line) -> flush()
            HEADING.matches(line) -> { flush(); out += Block.Heading(HEADING.find(line)!!.groupValues[1]) }
            BULLET.matches(line) -> {
                flush()
                val m = BULLET.find(line)!!
                out += Block.ListItem("•", level(m.groupValues[1]), m.groupValues[2])
            }
            NUMBERED.matches(line) -> {
                flush()
                val m = NUMBERED.find(line)!!
                out += Block.ListItem("${m.groupValues[2]}.", level(m.groupValues[1]), m.groupValues[3])
            }
            QUOTE.matches(line) -> {
                flush()
                val text = QUOTE.find(line)!!.groupValues[1]
                val last = out.lastOrNull()
                if (last is Block.Quote) out[out.lastIndex] = Block.Quote(last.text + " " + text)
                else if (text.isNotBlank()) out += Block.Quote(text)
            }
            else -> {
                // An indented line right after a list item continues that item.
                val last = out.lastOrNull()
                if (para.isEmpty() && last is Block.ListItem && line.startsWith(" ")) {
                    out[out.lastIndex] = last.copy(text = last.text + " " + line.trim())
                } else {
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(line.trim())
                }
            }
        }
        i++
    }
    flush()
    return out
}

private fun level(indent: String): Int = (indent.replace("\t", "    ").length / 2).coerceAtMost(4)

/**
 * Inline styles by toggling: ** / __ bold, * / _ italic, ` code. A marker only opens when text
 * follows it and only closes when text precedes it, so "2 * 3" and snake_case stay as they are.
 * Whatever is left open at the end simply ends; the marker characters are never printed.
 */
private fun inline(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var bold = false
    var italic = false
    var code = false
    val buf = StringBuilder()
    fun emit() {
        if (buf.isEmpty()) return
        val style = SpanStyle(
            fontWeight = if (bold) FontWeight.SemiBold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            fontFamily = if (code) FontFamily.Monospace else null,
            background = if (code) codeBg else Color.Unspecified,
        )
        withStyle(style) { append(buf.toString()) }
        buf.clear()
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '`') { emit(); code = !code; i++; continue }
        if (code) { buf.append(c); i++; continue }
        if (c == '\\' && i + 1 < text.length && text[i + 1] in "*_`#") { buf.append(text[i + 1]); i += 2; continue }
        if (c == '*' || c == '_') {
            var run = 1
            while (i + run < text.length && text[i + run] == c) run++
            val prev = text.getOrNull(i - 1)
            val next = text.getOrNull(i + run)
            val canOpen = next != null && !next.isWhitespace()
            val canClose = prev != null && !prev.isWhitespace()
            // Underscores inside words (snake_case, file_name) are not emphasis.
            val inWord = c == '_' && prev?.isLetterOrDigit() == true && next?.isLetterOrDigit() == true
            if (!inWord && (canOpen || canClose)) {
                emit()
                var left = run
                if (left >= 2 && (if (bold) canClose else canOpen)) { bold = !bold; left -= 2 }
                if (left >= 1 && (if (italic) canClose else canOpen)) { italic = !italic; left -= 1 }
                // Any extra stars (e.g. "****") are dropped.
                i += run
                continue
            }
            if (run >= 2 && !canOpen && !canClose) { i += run; continue } // stray "**" between spaces
        }
        buf.append(c)
        i++
    }
    emit()
}
