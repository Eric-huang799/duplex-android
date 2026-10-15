package com.duplex.mobile.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.duplex.mobile.browser.PageLibrary
import com.duplex.mobile.browser.TabManager
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 页面标注控制器（对应桌面版的标注功能，适配触摸）：
 * 开启页面内标注模式（PageLibrary.cb.annot，框/圈/箭头/点选），轮询“完成/退出”事件，
 * 发送消息时把标注摘要 + 用户问题合并成一条结构化消息交给智能体。
 */
class AnnotationController(
    private val tabs: TabManager,
    private val scope: CoroutineScope
) {
    /** 标注模式是否开启（页面正在等待用户圈选）。 */
    var active by mutableStateOf(false)
        private set

    /** 页面上当前已有的标注数量（点“完成”后保留，发送或取消后清零）。 */
    var markCount by mutableStateOf(0)
        private set

    private var tabId: Int? = null
    private var pollJob: Job? = null

    // ---------- WebView 通信 ----------

    private suspend fun evalJson(script: String): JsonElement? {
        val tab = tabs.active ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                try {
                    tab.webView.evaluateJavascript(script) { raw ->
                        val v = if (raw == null || raw == "null") null
                        else runCatching { JsonParser.parseString(raw) }.getOrNull()
                        if (cont.isActive) cont.resume(v)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private suspend fun ensureLib() {
        val check = evalJson(
            "typeof window.__cb === 'object' && window.__cb.v === ${PageLibrary.VERSION}"
        )
        val has = check != null && check.isJsonPrimitive && check.asBoolean
        if (!has) evalJson(PageLibrary.JS)
    }

    private suspend fun annotCount(): Int {
        val v = evalJson(
            "(typeof window.__cb === 'object' && window.__cb.v === ${PageLibrary.VERSION}) ? window.__cb.annot.count() : -1"
        ) ?: return -1
        return if (v.isJsonPrimitive) v.asInt else -1
    }

    // ---------- 对外状态 ----------

    /** 当前是否有可标注的页面（已加载内容的网页）。 */
    fun canAnnotate(): Boolean {
        val t = tabs.active ?: return false
        return !t.isBlank
    }

    /** 开启标注模式；false 表示当前没有可标注的页面。 */
    fun start(): Boolean {
        val tab = tabs.active ?: return false
        if (tab.isBlank) return false
        tabId = tab.id
        active = true
        scope.launch {
            ensureLib()
            evalJson("window.__cb.annot.enter('box')")
            markCount = annotCount().coerceAtLeast(0)
        }
        if (pollJob == null) {
            pollJob = scope.launch {
                while (isActive) {
                    delay(400)
                    if (!pollOnce()) break
                }
                pollJob = null
            }
        }
        return true
    }

    /** 面板上的“完成”：收起页面标注工具，保留已画的标记。 */
    fun finishFromPanel() {
        if (!active) return
        active = false
        scope.launch { evalJson("window.__cb.annot.finish()") }
    }

    /** 取消/清空：移除页面标记并结束标注会话。 */
    fun cancelMarks() {
        active = false
        scope.launch { evalJson("window.__cb.annot.exit()") }
        endLocally()
    }

    /** 发送消息时调用：有标注则返回“标注摘要 + 问题”的合成文本并清空标记；否则返回 null。 */
    suspend fun consumeMessage(question: String): String? {
        if (markCount <= 0) return null
        val summaryRaw = evalJson("window.__cb.annot.summary()")
            ?.takeIf { it.isJsonPrimitive }?.asString
        val marks = summaryRaw?.let {
            runCatching { JsonParser.parseString(it).asJsonArray }.getOrNull()
        }
        evalJson("window.__cb.annot.exit()")
        endLocally()
        if (marks == null || marks.isEmpty()) return null
        return buildText(marks, question)
    }

    // ---------- 内部 ----------

    private fun endLocally() {
        active = false
        markCount = 0
        tabId = null
    }

    /** 每次轮询返回 false 表示标注会话结束（轮询循环退出）。 */
    private suspend fun pollOnce(): Boolean {
        val tab = tabs.active
        val tid = tabId
        if (tab == null || tid == null || tab.id != tid) {
            // 切换到别的标签 / 标签被关闭：结束会话
            endLocally()
            return false
        }
        val n = annotCount()
        if (n < 0) {
            // 页面刷新后注入库被重置：标记已不存在
            endLocally()
            return false
        }
        markCount = n
        val evRaw = evalJson("window.__cb.annot.poll()")
            ?.takeIf { it.isJsonPrimitive }?.asString
        if (evRaw != null) {
            runCatching { JsonParser.parseString(evRaw).asJsonArray }.getOrNull()?.forEach { ev ->
                val type = ev.takeIf { it.isJsonObject }?.asJsonObject?.get("type")?.asString
                if (type == "done" || type == "exit") active = false
            }
        }
        if (!active && markCount == 0) {
            tabId = null
            return false
        }
        return true
    }

    private fun toolLabel(tool: String): String = when (tool) {
        "circle" -> "圈选"
        "arrow" -> "箭头"
        "point" -> "点选"
        else -> "框选"
    }

    private fun buildText(marks: JsonArray, question: String): String {
        val tab = tabs.active
        val sb = StringBuilder()
        sb.append("[页面标注] 用户直接在页面上做了标记，请结合标记回答。\n")
        sb.append("页面: ${tab?.title ?: ""} ${tab?.url ?: ""}\n".trimEnd())
        sb.append('\n')
        marks.forEach { el ->
            val m = el.asJsonObject
            val idx = m.get("index")?.asInt ?: 0
            val tool = m.get("tool")?.asString ?: "box"
            val x = m.get("x")?.asInt ?: 0
            val y = m.get("y")?.asInt ?: 0
            val w = m.get("w")?.asInt ?: 0
            val h = m.get("h")?.asInt ?: 0
            val anchor = m.getAsJsonObject("anchor")
            val tag = anchor?.get("tag")?.asString ?: ""
            val selector = anchor?.get("selector")?.asString ?: ""
            val text = anchor?.get("text")?.asString ?: ""
            sb.append("- 标注 $idx（${toolLabel(tool)}）：")
            sb.append("页面坐标 (${x},${y})")
            if (w > 0 || h > 0) sb.append("，区域 ${w}×${h}")
            if (tag.isNotBlank()) sb.append("，元素 <$tag>")
            if (selector.isNotBlank()) sb.append("，selector: $selector")
            if (text.isNotBlank()) sb.append("，元素文本：\"${text}\"")
            sb.append('\n')
        }
        sb.append("\n用户提问：$question")
        return sb.toString()
    }
}
