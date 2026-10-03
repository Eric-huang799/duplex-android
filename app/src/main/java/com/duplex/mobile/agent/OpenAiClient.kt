package com.duplex.mobile.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class LlmToolCall(val id: String, val name: String, val args: String)

data class ChatResult(val text: String, val toolCalls: List<LlmToolCall>)

/**
 * OpenAI 兼容的 chat/completions 客户端（SSE 流式 + function calling）。
 * 与桌面版 src/main/agent/llm/openai-chat.ts 行为对齐。
 */
class OpenAiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun chatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JsonArray,
        tools: JsonArray?,
        onDelta: (String) -> Unit
    ): ChatResult = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw IOException("未配置 API 地址")
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"

        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            if (tools != null && tools.size() > 0) add("tools", tools)
            addProperty("stream", true)
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()

        val call = client.newCall(request)
        val text = StringBuilder()
        val toolAcc = sortedMapOf<Int, LlmToolCall>()

        try {
            call.execute().use { resp ->
                val respBody = resp.body ?: throw IOException("响应为空")
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code}: ${respBody.string().take(400)}")
                }
                val source = respBody.source()
                while (isActive) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty() || line.startsWith(":")) continue
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val json = try {
                        JsonParser.parseString(payload)
                    } catch (e: Exception) {
                        continue
                    }
                    val delta = json.asJsonObject
                        ?.getAsJsonArray("choices")
                        ?.firstOrNull()
                        ?.asJsonObject
                        ?.getAsJsonObject("delta")
                        ?: continue

                    val contentEl = delta.get("content")
                    val chunk = when {
                        contentEl == null || contentEl.isJsonNull -> ""
                        contentEl.isJsonPrimitive -> contentEl.asString
                        contentEl.isJsonArray -> contentEl.asJsonArray.joinToString("") { p ->
                            p.takeIf { it.isJsonObject }
                                ?.asJsonObject?.get("text")
                                ?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                        }
                        else -> ""
                    }
                    if (chunk.isNotEmpty()) {
                        text.append(chunk)
                        onDelta(chunk)
                    }

                    delta.getAsJsonArray("tool_calls")?.forEach { el ->
                        val tc = el.asJsonObject
                        val idx = tc.get("index")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                        val cur = toolAcc[idx] ?: LlmToolCall("", "", "")
                        val newId = tc.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                            ?.takeIf { it.isNotEmpty() } ?: cur.id
                        val fn = tc.getAsJsonObject("function")
                        val newName = fn?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                            ?.takeIf { it.isNotEmpty() } ?: cur.name
                        val extraArgs =
                            fn?.get("arguments")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                        toolAcc[idx] = LlmToolCall(newId, newName, cur.args + extraArgs)
                    }
                }
            }
        } finally {
            call.cancel()
        }

        val calls = toolAcc.values
            .filter { it.name.isNotEmpty() }
            .map {
                if (it.id.isEmpty()) {
                    it.copy(id = "call_" + UUID.randomUUID().toString().replace("-", "").take(12))
                } else it
            }
        ChatResult(text.toString(), calls)
    }
}
