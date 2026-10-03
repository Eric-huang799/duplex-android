package com.duplex.mobile.agent

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
import kotlinx.coroutines.launch

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
