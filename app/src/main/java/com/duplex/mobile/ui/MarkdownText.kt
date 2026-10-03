package com.duplex.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

/**
 * 轻量 Markdown 渲染（无第三方依赖）：标题 / 列表 / 代码块 / 引用 / 分隔线 +
 * 行内加粗、斜体、删除线、行内代码、链接（样式化，不拦截点击）。
 */
private sealed interface MdBlock {
    data class P(val text: String) : MdBlock
    data class H(val level: Int, val text: String) : MdBlock
    data class Bullet(val indent: Int, val text: String) : MdBlock
    data class Num(val number: String, val indent: Int, val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
private val QUOTE = Regex("^\\s*>\\s?(.*)$")

private fun isRule(line: String): Boolean {
    val t = line.trim()
    if (t.length < 3) return false
    return t.all { it == '-' } || t.all { it == '*' } || t.all { it == '_' }
}

private fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.replace("\r\n", "\n").split("\n")
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out.add(MdBlock.P(para.toString().trim()))
        para.clear()
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

            BULLET.matches(line) -> {
                flush()
                val m = BULLET.find(line)!!
                out.add(MdBlock.Bullet(m.groupValues[1].length / 2, m.groupValues[2].trim()))
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
                if (para.isNotEmpty()) para.append(' ')
                para.append(line.trim())
            }
        }
        i++
    }
    flush()
    return out
}

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
    var i = 0
    while (i < text.length) {
        when {
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
                    withStyle(link) { append(text.substring(i + 1, close)) }
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

@Composable
private fun BlockView(b: MdBlock, body: TextStyle) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    when (b) {
        is MdBlock.P -> Text(
            inline(b.text, codeBg, linkColor),
            style = body,
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
        )

        is MdBlock.H -> {
            val size = when (b.level) {
                1 -> 20.sp
                2 -> 18.sp
                3 -> 16.sp
                else -> 15.sp
            }
            val style = body.copy(fontSize = size, fontWeight = FontWeight.Bold)
            Text(
                inline(b.text, codeBg, linkColor),
                style = style,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)
            )
        }

        is MdBlock.Bullet -> Row(
            Modifier
                .fillMaxWidth()
                .padding(start = (b.indent * 12).dp, top = 1.dp, bottom = 1.dp)
        ) {
            Text("•", style = body, modifier = Modifier.padding(end = 6.dp))
            Text(inline(b.text, codeBg, linkColor), style = body)
        }

        is MdBlock.Num -> Row(
            Modifier
                .fillMaxWidth()
                .padding(start = (b.indent * 12).dp, top = 1.dp, bottom = 1.dp)
        ) {
            Text("${b.number}.", style = body, modifier = Modifier.padding(end = 6.dp))
            Text(inline(b.text, codeBg, linkColor), style = body)
        }

        is MdBlock.Code -> Surface(
            color = codeBg,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Text(
                b.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(8.dp)
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
            Text(
                inline(b.text, codeBg, linkColor),
                style = body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 6.dp))
    }
}

/** 把一段 Markdown 文本渲染成 Compose 组件。 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier) {
        blocks.forEach { BlockView(it, style) }
    }
}
