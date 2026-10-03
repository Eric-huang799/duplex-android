package com.duplex.mobile.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 聊天面板里的一条消息（text/toolStatus 是可观察状态，支持流式更新）。 */
class ChatMessage(
    val id: Long,
    val role: String, // user | assistant | tool | info
    initialText: String,
    val toolName: String? = null
) {
    var text by mutableStateOf(initialText)
    var toolStatus by mutableStateOf<String?>(null) // running | done | error
    var toolDetail by mutableStateOf<String?>(null)
}

/**
 * 内置 agent：与桌面版 src/main/agent/runtime.ts 的循环等价。
 * LLM 流式输出 → 工具调用 → 执行（ToolBridge）→ 回填结果 → 继续，直到无工具调用。
 */
class ChatController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val bridge: ToolBridge,
    val config: ProviderStore
) {
    val messages = mutableStateListOf<ChatMessage>()

    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set

    private val client = OpenAiClient()
    private val history = mutableListOf<JsonObject>()
    private var job: Job? = null
    private var nextId = 1L

    private val file: File get() = File(context.filesDir, "chat_history.json")

    init {
        load()
    }

    private fun systemPrompt(): String = """
你是 Duplex 手机/平板版浏览器里的内置操作 agent。你可以直接读写并操作用户当前浏览器里的页面，用户能同时看到你操作的页面。

工作规则：
- 用 snapshot 查看页面结构（这是你的主要“眼睛”）：它返回紧凑的 DOM 文本大纲，可交互元素带 [eN] ref。
- click/type 的 target 可以是 ref（如 "e12"）或 CSS 选择器。ref 在页面导航后失效，需要重新 snapshot。
- 这是移动端：没有鼠标悬停（不要用 hover）；长页面用 scroll 滚动；页面加载慢用 wait。
- 需要搜索时用 search 工具；已知网址用 navigate。
- 敏感操作（发送消息、提交表单、下单）前先说明你将要做什么。
- 完成任务后用简洁的中文回答（说明做了什么、发现了什么）。
- 如果工具结果提示操作被用户停止，立即停下来等待指示。
""".trimIndent()

    private fun buildMessages(): JsonArray {
        val arr = JsonArray()
        arr.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", systemPrompt())
        })
        history.forEach { arr.add(it) }
        return arr
    }

    private fun addMsg(role: String, text: String, toolName: String? = null): ChatMessage {
        val m = ChatMessage(nextId++, role, text, toolName)
        messages.add(m)
        return m
    }

    fun send(text: String) {
        if (running) return
        if (!config.configured) {
            addMsg("info", "请先在设置里配置 API（地址 / Key / 模型），再开始对话。")
            return
        }
        addMsg("user", text)
        history.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", text)
        })
        persist()
        running = true
        job = scope.launch {
            try {
                runLoop()
            } catch (e: CancellationException) {
                addMsg("info", "已停止。")
                throw e
            } catch (e: Exception) {
                addMsg("info", "⚠️ ${e.message ?: e}")
            } finally {
                withContext(NonCancellable) {
                    try {
                        bridge.clearOverlay()
                    } catch (_: Exception) {
                    }
                    persist()
                }
                running = false
                status = ""
            }
        }
    }

    fun stop() {
        if (job?.isActive == true) {
            status = "正在停止…"
            job?.cancel()
        }
    }

    fun newChat() {
        stop()
        messages.clear()
        history.clear()
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    /** 持久化（app 私有目录，重启后恢复）。 */
    fun persist() {
        try {
            if (messages.size > 400) {
                repeat(messages.size - 400) { messages.removeAt(0) }
            }
            if (history.size > 200) {
                repeat(history.size - 200) { history.removeAt(0) }
            }
            val arr = JsonArray()
            messages.forEach { m ->
                val o = JsonObject()
                o.addProperty("id", m.id)
                o.addProperty("role", m.role)
                o.addProperty("text", m.text)
                m.toolName?.let { o.addProperty("toolName", it) }
                m.toolStatus?.let { o.addProperty("toolStatus", it) }
                m.toolDetail?.let { o.addProperty("toolDetail", it) }
                arr.add(o)
            }
            val root = JsonObject()
            root.add("messages", arr)
            root.add("history", JsonArray().also { h -> history.forEach { h.add(it) } })
            file.writeText(root.toString())
        } catch (_: Exception) {
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val root = JsonParser.parseString(file.readText()).asJsonObject
            root.getAsJsonArray("messages")?.forEach { el ->
                val o = el.asJsonObject
                val m = ChatMessage(
                    o.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: nextId++,
                    o.get("role")?.asString ?: "info",
                    o.get("text")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    o.get("toolName")?.takeIf { !it.isJsonNull }?.asString
                )
                m.toolStatus = o.get("toolStatus")?.takeIf { !it.isJsonNull }?.asString
                m.toolDetail = o.get("toolDetail")?.takeIf { !it.isJsonNull }?.asString
                messages.add(m)
            }
            root.getAsJsonArray("history")?.forEach { el ->
                if (el.isJsonObject) history.add(el.asJsonObject)
            }
            nextId = (messages.maxOfOrNull { it.id } ?: 0L) + 1
        } catch (_: Exception) {
        }
    }

    private suspend fun runLoop() {
        var iterations = 0
        while (true) {
            if (++iterations > 16) {
                addMsg("info", "已达单轮工具调用上限（16 次），先停下来。")
                return
            }
            status = "思考中…"
            val bubble = addMsg("assistant", "")
            val sb = StringBuilder()
            val result = client.chatStream(
                config.baseUrl,
                config.apiKey,
                config.model,
                buildMessages(),
                ToolSchemas.tools()
            ) { delta ->
                sb.append(delta)
                bubble.text = sb.toString()
            }
            if (bubble.text.isBlank()) messages.remove(bubble)

            if (result.toolCalls.isEmpty()) {
                if (result.text.isNotBlank()) {
                    history.add(JsonObject().apply {
                        addProperty("role", "assistant")
                        addProperty("content", result.text)
                    })
                }
                return
            }

            history.add(assistantToolMsg(result.text, result.toolCalls))
            for (tc in result.toolCalls) {
                val card = addMsg("tool", "", tc.name)
                card.toolStatus = "running"
                status = "正在执行 ${tc.name}…"
                val argsObj = try {
                    JsonParser.parseString(tc.args.ifBlank { "{}" }).asJsonObject
                } catch (e: Exception) {
                    JsonObject()
                }
                val out = bridge.execute(tc.name, argsObj)
                card.toolStatus = if (out.startsWith("ERROR")) "error" else "done"
                card.toolDetail = if (out.length > 400) out.take(400) + "…" else out
                history.add(JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", tc.id)
                    addProperty("content", out.take(30000))
                })
                persist()
            }
        }
    }

    private fun assistantToolMsg(text: String, calls: List<LlmToolCall>): JsonObject {
        val o = JsonObject()
        o.addProperty("role", "assistant")
        if (text.isNotBlank()) o.addProperty("content", text) else o.add("content", JsonNull.INSTANCE)
        val arr = JsonArray()
        calls.forEach { c ->
            val f = JsonObject().apply {
                addProperty("name", c.name)
                addProperty("arguments", c.args.ifBlank { "{}" })
            }
            val tc = JsonObject().apply {
                addProperty("id", c.id)
                addProperty("type", "function")
                add("function", f)
            }
            arr.add(tc)
        }
        o.add("tool_calls", arr)
        return o
    }
}
