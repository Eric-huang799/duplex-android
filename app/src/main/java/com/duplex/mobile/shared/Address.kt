package com.duplex.mobile.shared

import java.net.URLEncoder

/**
 * 地址栏输入解析 + 搜索引擎。
 * 与桌面版 src/shared/url.ts、src/shared/search.ts 行为对齐。
 */
object Address {
    private val HAS_PROTOCOL = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)
    private val LOCALHOST = Regex("^localhost(:\\d+)?([/?#]|\$)", RegexOption.IGNORE_CASE)
    private val IPV4 = Regex("^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?([/?#]|\$)")
    private val DOMAIN =
        Regex("^([\\w-]+\\.)+[a-z][a-z0-9-]{1,}(:\\d+)?([/?#].*)?\$", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s")

    private const val DEFAULT_ENGINE = "baidu"
    private val ENGINES = mapOf(
        "baidu" to "https://www.baidu.com/s?wd=%s",
        "bing" to "https://cn.bing.com/search?q=%s",
        "google" to "https://www.google.com/search?q=%s"
    )

    fun searchUrl(query: String, engine: String? = null): String {
        val key = if (engine != null && ENGINES.containsKey(engine)) engine else DEFAULT_ENGINE
        return ENGINES.getValue(key).replace("%s", URLEncoder.encode(query, "UTF-8"))
    }

    /** 解析地址栏输入：是网址就直接打开，否则当搜索词。 */
    fun resolve(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return "about:blank"
        if (LOCALHOST.containsMatchIn(s)) return "http://$s"
        if (IPV4.containsMatchIn(s)) return "http://$s"
        if (HAS_PROTOCOL.containsMatchIn(s)) return s
        if (s.startsWith("//")) return "https:$s"
        if (!WHITESPACE.containsMatchIn(s) && DOMAIN.containsMatchIn(s)) return "https://$s"
        return searchUrl(s)
    }
}
