package com.foundryos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foundryos.app.ui.theme.FoundryColors

sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class BulletItem(val text: String, val marker: String = "•") : MdBlock()
    data class Blockquote(val text: String) : MdBlock()
    data class CodeBlock(val lang: String, val code: String) : MdBlock()
    data class Table(val rows: List<List<String>>) : MdBlock()
    object Divider : MdBlock()
}

object MarkdownParser {
    private val headingRegex = Regex("""^\s*(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val dividerRegex = Regex("""^\s*([-*_])\1{2,}\s*$""")
    private val bulletRegex = Regex("""^\s*[-*•]\s+(.+)$""")
    private val numberedRegex = Regex("""^\s*(\d+[.)])\s+(.+)$""")
    private val checkboxRegex = Regex("""^\s*[-*]\s+\[([ xX]?)\]\s+(.+)$""")

    fun parseBlocks(raw: String): List<MdBlock> {
        val lines = raw.replace("\r\n", "\n").lines()
        val blocks = mutableListOf<MdBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isBlank()) {
                i++
                continue
            }

            // 1. Fenced code block ```
            if (trimmed.startsWith("```")) {
                val lang = trimmed.removePrefix("```").trim()
                val sb = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(lines[i])
                    i++
                }
                if (i < lines.size) i++ // consume closing ```
                blocks.add(MdBlock.CodeBlock(lang = lang, code = sb.toString()))
                continue
            }

            // 2. Horizontal rule --- / ***
            if (dividerRegex.matches(trimmed)) {
                blocks.add(MdBlock.Divider)
                i++
                continue
            }

            // 3. Heading # .. ######
            headingRegex.matchEntire(trimmed)?.let { m ->
                blocks.add(MdBlock.Heading(level = m.groupValues[1].length, text = m.groupValues[2].trim()))
                i++
                continue
            }

            // 4. Markdown Table
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                val rows = mutableListOf<List<String>>()
                while (i < lines.size) {
                    val tl = lines[i].trim()
                    if (!tl.startsWith("|")) break
                    val cells = tl.trim('|').split("|").map { it.trim() }
                    val isSep = cells.all { it.replace("-", "").replace(":", "").isBlank() }
                    if (!isSep && cells.isNotEmpty()) rows.add(cells)
                    i++
                }
                if (rows.isNotEmpty()) blocks.add(MdBlock.Table(rows))
                continue
            }

            // 5. Blockquote >
            if (trimmed.startsWith(">")) {
                val qLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    qLines.add(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks.add(MdBlock.Blockquote(qLines.joinToString("\n")))
                continue
            }

            // 6. Checkbox - [ ] / - [x]
            checkboxRegex.matchEntire(line)?.let { m ->
                val done = m.groupValues[1].equals("x", ignoreCase = true)
                blocks.add(MdBlock.BulletItem(text = m.groupValues[2].trim(), marker = if (done) "☑" else "☐"))
                i++
                continue
            }

            // 7. Bullet list - / * / •
            bulletRegex.matchEntire(line)?.let { m ->
                blocks.add(MdBlock.BulletItem(text = m.groupValues[1].trim(), marker = "•"))
                i++
                continue
            }

            // 8. Numbered list 1. / 1)
            numberedRegex.matchEntire(line)?.let { m ->
                blocks.add(MdBlock.BulletItem(text = m.groupValues[2].trim(), marker = m.groupValues[1]))
                i++
                continue
            }

            // 9. Normal paragraph (merge consecutive plain lines)
            val para = StringBuilder(trimmed)
            i++
            while (i < lines.size) {
                val next = lines[i].trim()
                if (next.isBlank() ||
                    next.startsWith("```") ||
                    dividerRegex.matches(next) ||
                    headingRegex.matches(next) ||
                    (next.startsWith("|") && next.endsWith("|")) ||
                    next.startsWith(">") ||
                    checkboxRegex.matches(lines[i]) ||
                    bulletRegex.matches(lines[i]) ||
                    numberedRegex.matches(lines[i])
                ) break
                para.append('\n').append(next)
                i++
            }
            blocks.add(MdBlock.Paragraph(para.toString()))
        }
        return blocks
    }

    /**
     * Parses inline Markdown (`**bold**`, `*italic*`, `***both***`, `__bold__`, `_italic_`,
     * `~~strike~~`, `` `code` ``, `[label](url)` and escaped `\#` / `\*`) into a rich [AnnotatedString].
     */
    fun parseInline(
        text: String,
        linkColor: Color = FoundryColors.BlueInk,
        codeBg: Color = Color(0xFFEAECEF),
        codeTextColor: Color = FoundryColors.InkPrimary,
    ): AnnotatedString = buildAnnotatedString {
        var idx = 0
        val len = text.length

        while (idx < len) {
            // Escaped markdown char: \* \# \_ \` \[ \] \\
            if (text[idx] == '\\' && idx + 1 < len && text[idx + 1] in charArrayOf('*', '#', '_', '`', '[', ']', '\\', '~', '-')) {
                append(text[idx + 1])
                idx += 2
                continue
            }

            // Link: [label](target)
            if (text[idx] == '[') {
                val closeBracket = text.indexOf(']', idx + 1)
                if (closeBracket > idx + 1 && closeBracket + 1 < len && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket + 2) {
                        val label = text.substring(idx + 1, closeBracket)
                        val target = text.substring(closeBracket + 2, closeParen).trim()
                        val start = length
                        withStyle(
                            SpanStyle(
                                color = linkColor,
                                fontWeight = FontWeight.Medium,
                                textDecoration = TextDecoration.Underline,
                            )
                        ) {
                            append(label)
                        }
                        addStringAnnotation(tag = "LINK", annotation = target, start = start, end = length)
                        idx = closeParen + 1
                        continue
                    }
                }
            }

            // Inline code: `code`
            if (text[idx] == '`') {
                val endTick = text.indexOf('`', idx + 1)
                if (endTick > idx + 1) {
                    val code = text.substring(idx + 1, endTick)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = codeTextColor,
                            background = codeBg,
                        )
                    ) {
                        append(" $code ")
                    }
                    idx = endTick + 1
                    continue
                }
            }

            // Bold + Italic: ***text***
            if (text.startsWith("***", idx)) {
                val end = text.indexOf("***", idx + 3)
                if (end > idx + 3) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(parseInline(text.substring(idx + 3, end), linkColor, codeBg, codeTextColor))
                    }
                    idx = end + 3
                    continue
                }
            }

            // Bold: **text** or __text__
            if (text.startsWith("**", idx) || text.startsWith("__", idx)) {
                val delim = text.substring(idx, idx + 2)
                val end = text.indexOf(delim, idx + 2)
                if (end > idx + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(parseInline(text.substring(idx + 2, end), linkColor, codeBg, codeTextColor))
                    }
                    idx = end + 2
                    continue
                }
            }

            // Strikethrough: ~~text~~
            if (text.startsWith("~~", idx)) {
                val end = text.indexOf("~~", idx + 2)
                if (end > idx + 2) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(parseInline(text.substring(idx + 2, end), linkColor, codeBg, codeTextColor))
                    }
                    idx = end + 2
                    continue
                }
            }

            // Italic: *text* (only when not followed by space so math/bullets aren't broken)
            if (text[idx] == '*' && idx + 1 < len && !text[idx + 1].isWhitespace()) {
                val end = text.indexOf('*', idx + 1)
                if (end > idx + 1 && !text[end - 1].isWhitespace()) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(parseInline(text.substring(idx + 1, end), linkColor, codeBg, codeTextColor))
                    }
                    idx = end + 1
                    continue
                }
            }

            // Italic: _text_ (word-boundary safe so snake_case_names stay intact!)
            if (text[idx] == '_' &&
                (idx == 0 || !text[idx - 1].isLetterOrDigit()) &&
                idx + 1 < len && !text[idx + 1].isWhitespace()
            ) {
                val end = text.indexOf('_', idx + 1)
                if (end > idx + 1 &&
                    !text[end - 1].isWhitespace() &&
                    (end + 1 >= len || !text[end + 1].isLetterOrDigit())
                ) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(parseInline(text.substring(idx + 1, end), linkColor, codeBg, codeTextColor))
                    }
                    idx = end + 1
                    continue
                }
            }

            append(text[idx])
            idx++
        }
    }
}

/**
 * Native Jetpack Compose Markdown renderer for chat bubbles (no raw #, **, *, `, or file:/// clutter).
 */
@Suppress("DEPRECATION")
@Composable
fun MarkdownChatText(
    markdown: String,
    textColor: Color = FoundryColors.InkPrimary,
    linkColor: Color = FoundryColors.BlueInk,
    codeBg: Color = Color(0xFFE6E8EC),
    onLinkClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { MarkdownParser.parseBlocks(markdown) }
    val baseStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor, lineHeight = 22.sp)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val (fontSize, topPad) = when (block.level) {
                        1 -> 18.sp to 4.dp
                        2 -> 16.5.sp to 4.dp
                        3 -> 15.sp to 3.dp
                        else -> 14.5.sp to 2.dp
                    }
                    val annotated = remember(block.text, linkColor, codeBg, textColor) {
                        MarkdownParser.parseInline(block.text, linkColor, codeBg, textColor)
                    }
                    RichLineText(
                        annotated = annotated,
                        style = TextStyle(
                            fontSize = fontSize,
                            lineHeight = (fontSize.value + 6f).sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                        ),
                        onLinkClick = onLinkClick,
                        modifier = Modifier.padding(top = topPad),
                    )
                }

                is MdBlock.Paragraph -> {
                    val annotated = remember(block.text, linkColor, codeBg, textColor) {
                        MarkdownParser.parseInline(block.text, linkColor, codeBg, textColor)
                    }
                    RichLineText(
                        annotated = annotated,
                        style = baseStyle,
                        onLinkClick = onLinkClick,
                    )
                }

                is MdBlock.BulletItem -> {
                    val annotated = remember(block.text, linkColor, codeBg, textColor) {
                        MarkdownParser.parseInline(block.text, linkColor, codeBg, textColor)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 2.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (block.marker == "•") {
                            Box(
                                modifier = Modifier
                                    .padding(top = 8.dp, end = 8.dp)
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(textColor.copy(alpha = 0.7f)),
                            )
                        } else {
                            Text(
                                text = block.marker,
                                style = baseStyle.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = textColor.copy(alpha = 0.85f),
                                ),
                                modifier = Modifier.padding(end = 7.dp),
                            )
                        }
                        RichLineText(
                            annotated = annotated,
                            style = baseStyle,
                            onLinkClick = onLinkClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.Blockquote -> {
                    val annotated = remember(block.text, linkColor, codeBg, textColor) {
                        MarkdownParser.parseInline(block.text, linkColor, codeBg, textColor)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.65f))
                            .padding(end = 10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.5.dp)
                                .fillMaxHeight()
                                .background(FoundryColors.InkPrimary.copy(alpha = 0.55f)),
                        )
                        Spacer(Modifier.width(10.dp))
                        RichLineText(
                            annotated = annotated,
                            style = baseStyle.copy(
                                fontStyle = FontStyle.Italic,
                                color = textColor.copy(alpha = 0.88f),
                            ),
                            onLinkClick = onLinkClick,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }

                is MdBlock.CodeBlock -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF18181B))
                            .padding(11.dp),
                    ) {
                        if (block.lang.isNotBlank()) {
                            Text(
                                text = block.lang.uppercase(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFA1A1AA),
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            text = block.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = Color(0xFFF4F4F5),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }

                is MdBlock.Table -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, FoundryColors.Border, RoundedCornerShape(10.dp))
                            .background(Color.White),
                    ) {
                        block.rows.forEachIndexed { rIdx, row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (rIdx == 0) FoundryColors.SidebarBg else Color.White)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                row.forEach { cell ->
                                    val cellAnn = remember(cell, linkColor, codeBg, textColor) {
                                        MarkdownParser.parseInline(cell, linkColor, codeBg, textColor)
                                    }
                                    Text(
                                        text = cellAnn,
                                        fontSize = 12.5.sp,
                                        lineHeight = 17.sp,
                                        fontWeight = if (rIdx == 0) FontWeight.SemiBold else FontWeight.Normal,
                                        color = textColor,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            if (rIdx < block.rows.lastIndex) {
                                HorizontalDivider(color = FoundryColors.Border, thickness = 0.7.dp)
                            }
                        }
                    }
                }

                MdBlock.Divider -> {
                    HorizontalDivider(
                        color = FoundryColors.Border,
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun RichLineText(
    annotated: AnnotatedString,
    style: TextStyle,
    onLinkClick: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (onLinkClick != null && annotated.getStringAnnotations("LINK", 0, annotated.length).isNotEmpty()) {
        ClickableText(
            text = annotated,
            style = style,
            modifier = modifier,
            onClick = { offset ->
                annotated.getStringAnnotations(tag = "LINK", start = offset, end = offset)
                    .firstOrNull()?.let { ann -> onLinkClick(ann.item) }
            },
        )
    } else {
        Text(
            text = annotated,
            style = style,
            modifier = modifier,
        )
    }
}
