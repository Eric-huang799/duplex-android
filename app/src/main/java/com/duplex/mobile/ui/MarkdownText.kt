package com.duplex.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures

/**
 * 轻量 Markdown 渲染（无第三方依赖）：标题 / 段落 / 列表（含续行与任务项）/
 * 代码块 / 引用 / 分隔线 / **表格（对齐与横向滚动）** +
 * 行内加粗、斜体、删除线、行内代码、链接（http/https 可点击）、图片占位。
 */
private sealed interface MdBlock {
    data class P(val text: String) : MdBlock
    data class H(val level: Int, val text: String) : MdBlock
    data class Bullet(val indent: Int, val text: String) : MdBlock
    data class Num(val number: String, val indent: Int, val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val aligns: List<TextAlign>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
private val QUOTE = Regex("^\\s*>\\s?(.*)$")
private val TASK = Regex("^\\[([ xX])]\\s+(.*)$")

private fun isRule(line: String): Boolean {
    val t = line.trim()
    if (t.length < 3) return false
    return t.all { it == '-' } || t.all { it == '*' } || t.all { it == '_' }
}

/** 分隔行（|---|---:|:--:|）→ 每列的对齐；不是表格分隔行返回 null。 */
private fun parseSeparator(line: String): List<TextAlign>? {
    val t = line.trim()
    if (!t.contains('|') || !t.contains('-')) return null
    val cells = splitTableRow(t)
    if (cells.isEmpty()) return null
    val aligns = mutableListOf<TextAlign>()
    for (cell in cells) {
        val c = cell.trim()
        if (!Regex("^:?-{2,}:?$").matches(c)) return null
        aligns.add(
            when {
                c.startsWith(":") && c.endsWith(":") -> TextAlign.Center
                c.endsWith(":") -> TextAlign.End
                else -> TextAlign.Start
            }
        )
    }
    return aligns
}

/** 按 | 拆分一行表格；支持 \\| 转义；去掉首尾空单元（来自行首/行尾的 |）。 */
private fun splitTableRow(line: String): List<String> {
    val t = line.trim()
    val cells = mutableListOf<String>()
    val sb = StringBuilder()
    var i = 0
    while (i < t.length) {
        val ch = t[i]
        when {
            ch == '\\' && i + 1 < t.length && t[i + 1] == '|' -> {
                sb.append('|'); i += 2
            }
            ch == '|' -> {
                cells.add(sb.toString()); sb.clear(); i++
            }
            else -> {
                sb.append(ch); i++
            }
        }
    }
    cells.add(sb.toString())
    if (cells.isNotEmpty() && cells.first().isBlank() && t.startsWith("|")) cells.removeAt(0)
    if (cells.isNotEmpty() && cells.last().isBlank() && t.endsWith("|")) cells.removeAt(cells.size - 1)
    return cells
}

private fun looksLikeTableRow(line: String): Boolean {
    val t = line.trim()
    return t.contains('|') && !t.startsWith("```")
}

private fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.replace("\r\n", "\n").split("\n")
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out.add(MdBlock.P(para.toString().trim()))
        para.clear()
    }
    /** 列表续行：追加到上一个列表项。 */
    fun appendContinuation(text: String): Boolean {
        val last = out.lastOrNull() ?: return false
        val joined = text.trim()
        if (joined.isBlank()) return false
        when (last) {
            is MdBlock.Bullet -> { out[out.size - 1] = last.copy(text = last.text + " " + joined); return true }
            is MdBlock.Num -> { out[out.size - 1] = last.copy(text = last.text + " " + joined); return true }
            else -> return false
        }
    }
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                out.add(MdBlock.Code(code.toString().trimEnd()))
            }

            line.isBlank() -> flush()

            HEADING.matches(line) -> {
                flush()
                val m = HEADING.find(line)!!
                out.add(MdBlock.H(m.groupValues[1].length, m.groupValues[2].trim()))
            }

            isRule(line) -> {
                flush()
                out.add(MdBlock.Rule)
            }

            // 表格：当前行含 |，下一行是分隔行
            looksLikeTableRow(line) && i + 1 < lines.size &&
                parseSeparator(lines[i + 1]) != null -> {
                flush()
                val aligns = parseSeparator(lines[i + 1])!!
                val header = splitTableRow(line).map { it.trim() }
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && looksLikeTableRow(lines[i]) && lines[i].isNotBlank()) {
                    rows.add(splitTableRow(lines[i]).map { it.trim() })
                    i++
                }
                i--
                out.add(MdBlock.Table(header, aligns, rows))
            }

            BULLET.matches(line) -> {
                flush()
                val m = BULLET.find(line)!!
                var text = m.groupValues[2].trim()
                val tm = TASK.find(text)
                if (tm != null) {
                    val mark = if (tm.groupValues[1].isBlank()) "☐" else "☑"
                    text = "$mark ${tm.groupValues[2].trim()}"
                }
                out.add(MdBlock.Bullet(m.groupValues[1].length / 2, text))
            }

            NUMBERED.matches(line) -> {
                flush()
                val m = NUMBERED.find(line)!!
                out.add(
                    MdBlock.Num(
                        m.groupValues[2],
                        m.groupValues[1].length / 2,
                        m.groupValues[3].trim()
                    )
                )
            }

            QUOTE.matches(line) -> {
                flush()
                out.add(MdBlock.Quote(QUOTE.find(line)!!.groupValues[1].trim()))
            }

            else -> {
                // 列表项续行（缩进或普通文本，且上一块是列表）：并进上一条
                val prevIsList = out.lastOrNull() is MdBlock.Bullet || out.lastOrNull() is MdBlock.Num
                if (prevIsList && para.isBlank() && trimmed.isNotEmpty()) {
                    appendContinuation(trimmed)
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

private const val URL_ANNOTATION = "mdurl"

private fun inline(
    text: String,
    codeBg: Color,
    linkColor: Color
): AnnotatedString = buildAnnotatedString {
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    val italic = SpanStyle(fontStyle = FontStyle.Italic)
    val strike = SpanStyle(textDecoration = TextDecoration.LineThrough)
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)
    val link = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
    val img = SpanStyle(color = linkColor, fontStyle = FontStyle.Italic)
    var i = 0
    while (i < text.length) {
        when {
            // ![alt](url) → 占位文本（不加载网络图片）
            text.startsWith("![", i) -> {
                val close = text.indexOf("](", i + 2)
                val closeP = if (close > 0) text.indexOf(')', close + 2) else -1
                if (close > 0 && closeP > close) {
                    val alt = text.substring(i + 2, close).ifBlank { "图片" }
                    withStyle(img) { append("🖼 $alt") }
                    i = closeP + 1
                } else {
                    append(text[i]); i++
                }
            }

            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(bold) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append(text[i]); i++
                }
            }

            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i) {
                    withStyle(strike) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append(text[i]); i++
                }
            }

            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(code) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }

            text[i] == '[' -> {
                val close = text.indexOf("](", i + 1)
                val closeP = if (close > 0) text.indexOf(')', close + 2) else -1
                if (close > 0 && closeP > close) {
                    val label = text.substring(i + 1, close)
                    val url = text.substring(close + 2, closeP).trim()
                    val start = length
                    withStyle(link) { append(label.ifBlank { url }) }
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        addStringAnnotation(URL_ANNOTATION, url, start, length)
                    }
                    i = closeP + 1
                } else {
                    append(text[i]); i++
                }
            }

            text[i] == '*' || text[i] == '_' -> {
                val ch = text[i]
                val end = text.indexOf(ch, i + 1)
                if (end > i + 1) {
                    withStyle(italic) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }

            else -> {
                append(text[i]); i++
            }
        }
    }
}

/** 可点击链接文本（无 handler 时退化为普通样式文本）。 */
@Composable
private fun InlineText(
    annotated: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onOpenUrl: ((String) -> Unit)? = null,
    textAlign: TextAlign? = null
) {
    if (onOpenUrl == null) {
        Text(annotated, style = style, modifier = modifier, textAlign = textAlign)
        return
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        annotated,
        style = style,
        modifier = modifier.pointerInput(annotated) {
            detectTapGestures { pos ->
                val lr = layout ?: return@detectTapGestures
                val offset = lr.getOffsetForPosition(pos)
                annotated.getStringAnnotations(URL_ANNOTATION, offset, offset)
                    .firstOrNull()
                    ?.let { onOpenUrl(it.item) }
            }
        },
        textAlign = textAlign,
        onTextLayout = { layout = it }
    )
}

/** 表格列宽估计：按该列最长内容估宽（保证对齐 + 允许横向滚动）。 */
private fun estimatedWidth(header: String, rows: List<List<String>>, col: Int): Int {
    var max = header.length
    rows.forEach { r -> r.getOrNull(col)?.let { if (it.length > max) max = it.length } }
    return (max * 8 + 24).coerceIn(84, 260)
}

@Composable
private fun TableCell(
    text: String,
    width: Int,
    align: TextAlign,
    header: Boolean,
    body: TextStyle,
    codeBg: Color,
    linkColor: Color,
    onOpenUrl: ((String) -> Unit)?
) {
    val style = if (header) body.copy(fontWeight = FontWeight.Bold) else body
    InlineText(
        inline(text, codeBg, linkColor),
        style = style,
        modifier = Modifier
            .width(width.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        onOpenUrl = onOpenUrl,
        textAlign = align
    )
}

@Composable
private fun VerticalDivider() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun TableView(
    t: MdBlock.Table,
    body: TextStyle,
    codeBg: Color,
    linkColor: Color,
    onOpenUrl: ((String) -> Unit)?
) {
    val colCount = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
    if (colCount == 0) return
    val widths = (0 until colCount).map { c ->
        estimatedWidth(t.header.getOrNull(c) ?: "", t.rows, c)
    }
    val hScroll = rememberScrollState()
    Column(
        Modifier
            .padding(vertical = 4.dp)
            .horizontalScroll(hScroll)
            .clip(RoundedCornerShape(8.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(8.dp)
            )
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            t.header.forEachIndexed { i, cell ->
                Box(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                    TableCell(
                        cell, widths.getOrElse(i) { 84 },
                        t.aligns.getOrElse(i) { TextAlign.Start },
                        header = true, body = body, codeBg = codeBg,
                        linkColor = linkColor, onOpenUrl = onOpenUrl
                    )
                }
                if (i < colCount - 1) VerticalDivider()
            }
            // 表头比数据行少列时补齐
            for (i in t.header.size until colCount) {
                Box(Modifier.width(widths[i].dp).background(MaterialTheme.colorScheme.surfaceVariant))
                if (i < colCount - 1) VerticalDivider()
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        t.rows.forEachIndexed { r, row ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                for (i in 0 until colCount) {
                    TableCell(
                        row.getOrNull(i) ?: "", widths[i],
                        t.aligns.getOrElse(i) { TextAlign.Start },
                        header = false, body = body, codeBg = codeBg,
                        linkColor = linkColor, onOpenUrl = onOpenUrl
                    )
                    if (i < colCount - 1) VerticalDivider()
                }
            }
            if (r < t.rows.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun BlockView(
    b: MdBlock,
    body: TextStyle,
    onOpenUrl: ((String) -> Unit)?
) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    when (b) {
        is MdBlock.P -> InlineText(
            inline(b.text, codeBg, linkColor),
            style = body,
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            onOpenUrl = onOpenUrl
        )

        is MdBlock.H -> {
            val size = when (b.level) {
                1 -> 20.sp
                2 -> 18.sp
                3 -> 16.sp
                else -> 15.sp
            }
            val style = body.copy(fontSize = size, fontWeight = FontWeight.Bold)
            InlineText(
                inline(b.text, codeBg, linkColor),
                style = style,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                onOpenUrl = onOpenUrl
            )
        }

        is MdBlock.Bullet -> Row(
            Modifier
                .fillMaxWidth()
                .padding(start = (b.indent * 12).dp, top = 1.dp, bottom = 1.dp)
        ) {
            Text("•", style = body, modifier = Modifier.padding(end = 6.dp))
            InlineText(inline(b.text, codeBg, linkColor), style = body, onOpenUrl = onOpenUrl)
        }

        is MdBlock.Num -> Row(
            Modifier
                .fillMaxWidth()
                .padding(start = (b.indent * 12).dp, top = 1.dp, bottom = 1.dp)
        ) {
            Text("${b.number}.", style = body, modifier = Modifier.padding(end = 6.dp))
            InlineText(inline(b.text, codeBg, linkColor), style = body, onOpenUrl = onOpenUrl)
        }

        is MdBlock.Code -> Surface(
            color = codeBg,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            val h = rememberScrollState()
            Text(
                b.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(8.dp).horizontalScroll(h)
            )
        }

        is MdBlock.Quote -> Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(vertical = 2.dp)
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
            InlineText(
                inline(b.text, codeBg, linkColor),
                style = body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(start = 8.dp),
                onOpenUrl = onOpenUrl
            )
        }

        is MdBlock.Table -> TableView(b, body, codeBg, linkColor, onOpenUrl)

        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 6.dp))
    }
}

/** 把一段 Markdown 文本渲染成 Compose 组件。 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    onOpenUrl: ((String) -> Unit)? = null
) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier) {
        blocks.forEach { BlockView(it, style, onOpenUrl) }
    }
}
