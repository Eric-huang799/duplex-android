package com.duplex.mobile.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson

/** 内置模型的 API 配置（OpenAI 兼容协议）。可保存多套、按厂商分类，随时切换当前使用的一套。 */
class ProviderStore(context: Context) {
    data class Provider(
        val id: String,
        val label: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String
    )

    private val prefs = context.getSharedPreferences("duplex_provider", Context.MODE_PRIVATE)
    private val gson = Gson()

    var providers by mutableStateOf(loadProviders())
        private set

    var activeId by mutableStateOf(prefs.getString("active_id", "") ?: "")
        private set

    /** 当前选中的配置；active_id 失效时回退到第一条。 */
    val active: Provider?
        get() = providers.firstOrNull { it.id == activeId } ?: providers.firstOrNull()

    val baseUrl: String get() = active?.baseUrl ?: ""
    val apiKey: String get() = active?.apiKey ?: ""
    val model: String get() = active?.model ?: ""

    val configured: Boolean
        get() = active != null && baseUrl.isNotBlank() && model.isNotBlank()

    fun setActive(id: String) {
        activeId = id
        prefs.edit().putString("active_id", id).apply()
    }

    fun add(label: String, base: String, key: String, modelName: String) {
        val p = Provider(newId(), label.trim(), base.trim(), key.trim(), modelName.trim())
        providers = providers + p
        persist()
        if (activeId.isBlank()) setActive(p.id)
    }

    fun update(id: String, label: String, base: String, key: String, modelName: String) {
        providers = providers.map {
            if (it.id == id) {
                it.copy(
                    label = label.trim(),
                    baseUrl = base.trim(),
                    apiKey = key.trim(),
                    model = modelName.trim()
                )
            } else it
        }
        persist()
    }

    fun remove(id: String) {
        providers = providers.filterNot { it.id == id }
        if (activeId == id) setActive(providers.firstOrNull()?.id ?: "")
        persist()
    }

    private fun persist() {
        prefs.edit().putString("providers_json", gson.toJson(providers)).apply()
    }

    private fun newId(): String =
        "p" + System.currentTimeMillis().toString(36) + (100 + (Math.random() * 900).toInt())

    private fun loadProviders(): List<Provider> {
        val json = prefs.getString("providers_json", null)
        if (json != null) {
            return try {
                (gson.fromJson(json, Array<Provider>::class.java) ?: emptyArray()).toList()
            } catch (_: Exception) {
                emptyList()
            }
        }
        // 旧版单配置迁移为一条记录
        val base = prefs.getString("baseUrl", "") ?: ""
        val key = prefs.getString("apiKey", "") ?: ""
        val mdl = prefs.getString("model", "") ?: ""
        if (base.isBlank() && mdl.isBlank()) return emptyList()
        val p = Provider(newId(), guessLabel(base), base, key, mdl)
        prefs.edit()
            .putString("providers_json", gson.toJson(listOf(p)))
            .putString("active_id", p.id)
            .remove("baseUrl")
            .remove("apiKey")
            .remove("model")
            .apply()
        return listOf(p)
    }

    private fun guessLabel(base: String): String = when {
        base.contains("deepseek") -> "DeepSeek"
        base.contains("kimi") || base.contains("moonshot") -> "Kimi"
        base.contains("bigmodel") -> "智谱 GLM"
        base.contains("dashscope") -> "通义千问"
        base.contains("localhost") || base.contains("127.0.0.1") -> "Ollama（本机）"
        else -> "默认"
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
