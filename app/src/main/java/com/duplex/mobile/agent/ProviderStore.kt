package com.duplex.mobile.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 内置模型的 API 配置（OpenAI 兼容协议）。 */
class ProviderStore(context: Context) {
    private val prefs = context.getSharedPreferences("duplex_provider", Context.MODE_PRIVATE)

    var baseUrl by mutableStateOf(prefs.getString("baseUrl", "") ?: "")
    var apiKey by mutableStateOf(prefs.getString("apiKey", "") ?: "")
    var model by mutableStateOf(prefs.getString("model", "") ?: "")

    val configured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    fun save(base: String, key: String, modelName: String) {
        baseUrl = base.trim()
        apiKey = key.trim()
        model = modelName.trim()
        prefs.edit()
            .putString("baseUrl", baseUrl)
            .putString("apiKey", apiKey)
            .putString("model", model)
            .apply()
    }

    data class Preset(val label: String, val baseUrl: String, val model: String)

    companion object {
        val PRESETS = listOf(
            Preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
            Preset("Kimi", "https://api.moonshot.cn/v1", ""),
            Preset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus"),
            Preset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
            Preset("Ollama（本机）", "http://localhost:11434/v1", "")
        )
    }
}
