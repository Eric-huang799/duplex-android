package com.duplex.mobile.agent

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import com.duplex.mobile.browser.BrowserTab
import com.duplex.mobile.browser.PageLibrary
import com.duplex.mobile.browser.TabManager
import com.duplex.mobile.shared.Address
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 把内置 agent 的工具调用映射到手机 WebView。
 * 对应桌面版 src/main/tool-handlers.ts（那边走 CDP，这边走 evaluateJavascript +
 * dispatchTouchEvent / dispatchKeyEvent）。
 */
class ToolBridge(private val tabs: TabManager) {

    suspend fun execute(name: String, args: JsonObject): String {
        return try {
            dispatch(name, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "ERROR: ${e.message ?: e}"
        }
    }

    // ---------- WebView eval helpers ----------

    private suspend fun evalJson(webView: WebView, script: String): JsonElement? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                try {
                    webView.evaluateJavascript(script) { raw ->
                        val v = if (raw == null || raw == "null") null
                        else runCatching { JsonParser.parseString(raw) }.getOrNull()
                        if (cont.isActive) cont.resume(v)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    private suspend fun evalString(webView: WebView, script: String): String {
        val el = evalJson(webView, script) ?: return ""
        return when {
            el.isJsonPrimitive -> el.asString
            else -> el.toString()
        }
    }

    private suspend fun ensureLib(tab: BrowserTab) {
        val check = evalJson(
            tab.webView,
            "typeof window.__cb === 'object' && window.__cb.v === ${PageLibrary.VERSION}"
        )
        val has = check?.isJsonPrimitive == true && check.asBoolean
        if (!has) evalJson(tab.webView, PageLibrary.JS)
    }

    private fun jsStr(s: String): String = JsonPrimitive(s).toString()

    /** 收起软键盘（AI 操作期间不让输入法挡住页面）。 */
    private suspend fun hideIme(webView: WebView) {
        withContext(Dispatchers.Main) {
            try {
                val imm = webView.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager
                imm?.hideSoftInputFromWindow(webView.windowToken, 0)
            } catch (_: Exception) {
            }
        }
    }

    /** 页面内可视化：状态条 / 光标 / 高亮。 */
    private suspend fun showStatus(tab: BrowserTab, text: String, ttl: Int = 3500) {
        evalJson(tab.webView, "window.__cb.overlay.status(${jsStr(text)}, $ttl)")
    }

    private suspend fun showTarget(tab: BrowserTab, r: JsonObject, pulse: Boolean) {
        val x = r.get("x")?.takeIf { it.isJsonPrimitive }?.asFloat ?: return
        val y = r.get("y")?.takeIf { it.isJsonPrimitive }?.asFloat ?: return
        evalJson(tab.webView, "window.__cb.overlay.cursor($x, $y, $pulse)")
        val rect = r.getAsJsonObject("rect")
        if (rect != null) {
            val rx = rect.get("x")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            val ry = rect.get("y")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            val rw = rect.get("w")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            val rh = rect.get("h")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            evalJson(
                tab.webView,
                "window.__cb.overlay.highlight($rx, $ry, $rw, $rh, 5000)"
            )
        }
    }

    suspend fun clearOverlay() {
        val tab = tabs.active ?: return
        try {
            ensureLib(tab)
            evalJson(tab.webView, "window.__cb.overlay.clear()")
        } catch (_: Exception) {
        }
    }

    // ---------- target helpers ----------

    private fun tabArg(args: JsonObject): BrowserTab? {
        val id = args.get("tabId")?.takeIf { it.isJsonPrimitive }?.asInt
        if (id != null) {
            val t = tabs.tabs.firstOrNull { it.id == id }
            if (t != null) {
                if (tabs.activeId != id) tabs.select(id)
                return t
            }
        }
        return tabs.active
    }

    private suspend fun waitForLoad(tab: BrowserTab, timeoutMs: Long = 15000) {
        delay(300)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!tab.isLoading) break
            delay(200)
        }
        delay(200)
    }

    /** CSS 坐标 → 视图坐标后派发真实触摸（phone/tablet 没有鼠标）。 */
    private suspend fun tapTab(tab: BrowserTab, cssX: Float, cssY: Float, clickCount: Int = 1) {
        val innerW = evalJson(tab.webView, "window.innerWidth")
            ?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f
        withContext(Dispatchers.Main) {
            val wv = tab.webView
            if (innerW <= 0f || wv.width <= 0) return@withContext
            val scale = wv.width.toFloat() / innerW
            val x = cssX * scale
            val y = cssY * scale
            repeat(clickCount) { i ->
                val t = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
                wv.dispatchTouchEvent(down)
                val up = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0)
                wv.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
                if (i < clickCount - 1) SystemClock.sleep(80)
            }
        }
        hideIme(tab.webView)
        delay(250)
    }

    private suspend fun dispatchEnterKey(tab: BrowserTab) {
        withContext(Dispatchers.Main) {
            val wv = tab.webView
            wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    // ---------- dispatch ----------

    private suspend fun dispatch(name: String, a: JsonObject): String {
        return when (name) {
        "list_tabs" -> {
            if (tabs.tabs.isEmpty()) "(no tabs)"
            else tabs.tabs.joinToString("\n") { t ->
                "id=${t.id} active=${t.id == tabs.activeId} loading=${t.isLoading} " +
                    "title=${t.title} url=${t.url}"
            }
        }

        "new_tab" -> {
            val raw = a.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            val url = if (raw.isNullOrBlank()) null else Address.resolve(raw)
            val tab = tabs.createTab(url)
            if (url != null) waitForLoad(tab)
            "opened tab ${tab.id}: ${tab.url.ifBlank { "(blank)" }}"
        }

        "close_tab" -> {
            val id = a.get("tabId").asInt
            if (tabs.tabs.none { it.id == id }) "no tab with id $id"
            else {
                tabs.closeTab(id)
                "closed tab $id"
            }
        }

        "switch_tab" -> {
            val id = a.get("tabId").asInt
            if (tabs.tabs.none { it.id == id }) "no tab with id $id"
            else {
                tabs.select(id)
                delay(150)
                "tab $id is now active and visible to the human"
            }
        }

        "navigate" -> {
            val tab = tabArg(a) ?: return "no active tab"
            val raw = a.get("url").asString
            val url = Address.resolve(raw)
            withContext(Dispatchers.Main) { tab.webView.loadUrl(url) }
            waitForLoad(tab)
            "url: ${tab.url}\ntitle: ${tab.title}"
        }

        "history" -> {
            val tab = tabArg(a) ?: return "no active tab"
            when (a.get("action").asString) {
                "back" -> withContext(Dispatchers.Main) { tab.webView.goBack() }
                "forward" -> withContext(Dispatchers.Main) { tab.webView.goForward() }
                "reload" -> withContext(Dispatchers.Main) { tab.webView.reload() }
                else -> return "invalid action (back/forward/reload)"
            }
            waitForLoad(tab)
            "url: ${tab.url}\ntitle: ${tab.title}"
        }

        "snapshot" -> {
            val tab = tabArg(a) ?: return "no active tab"
            ensureLib(tab)
            val snap = evalString(tab.webView, "window.__cb.snapshot()")
            if (snap.length > 16000) snap.take(16000) + "\n…(snapshot truncated)"
            else snap
        }

        "get_html" -> {
            val tab = tabArg(a) ?: return "no active tab"
            ensureLib(tab)
            val sel = a.get("selector")?.takeIf { it.isJsonPrimitive }?.asString
            val maxChars = a.get("maxChars")?.takeIf { it.isJsonPrimitive }?.asInt ?: 40000
            val html = if (sel.isNullOrBlank()) {
                evalString(tab.webView, "window.__cb.body()")
            } else {
                val r = evalJson(tab.webView, "window.__cb.outer(${jsStr(sel)})")
                val obj = r?.takeIf { it.isJsonObject }?.asJsonObject
                if (obj?.get("error") != null) return obj.get("error").asString
                obj?.get("html")?.asString ?: ""
            }
            if (html.length > maxChars) html.take(maxChars) + "\n…(truncated at $maxChars chars)"
            else html
        }

        "query" -> {
            val tab = tabArg(a) ?: return "no active tab"
            ensureLib(tab)
            val sel = a.get("selector").asString
            val limit = a.get("limit")?.takeIf { it.isJsonPrimitive }?.asInt ?: 20
            val r = evalJson(tab.webView, "window.__cb.query(${jsStr(sel)}, $limit)")
            r?.toString() ?: "(no result)"
        }

        "click" -> {
            val tab = tabArg(a) ?: return "no active tab"
            ensureLib(tab)
            val target = a.get("target").asString
            val r = evalJson(tab.webView, "window.__cb.resolve(${jsStr(target)})")?.asJsonObject
                ?: return "ERROR: resolve failed"
            if (r.get("error") != null) return r.get("error").asString
            val x = r.get("x").asFloat
            val y = r.get("y").asFloat
            val tag = r.get("tag")?.asString ?: "?"
            val text = r.get("text")?.asString ?: ""
            val label = if (text.isNotBlank()) "「${text.take(16)}」" else "<$tag>"
            showStatus(tab, "AI 正在点击 $label", 4200)
            showTarget(tab, r, false)
            tapTab(tab, x, y)
            evalJson(tab.webView, "window.__cb.overlay.cursor($x, $y, true)")
            "clicked <$tag>${if (text.isNotBlank()) " \"${text.take(40)}\"" else ""} " +
                "(visible=${r.get("visible")?.asBoolean ?: false})"
        }

        "dblclick" -> {
            val tab = tabArg(a) ?: return "no active tab"
            ensureLib(tab)
            val target = a.get("target").asString
            val r = evalJson(tab.webView, "window.__cb.resolve(${jsStr(target)})")?.asJsonObject
                ?: return "ERROR: resolve failed"
            if (r.get("error") != null) return r.get("error").asString
            showStatus(tab, "AI 正在双击 <${r.get("tag")?.asString ?: "?"}>", 4200)
            showTarget(tab, r, false)
            tapTab(tab, r.get("x").asFloat, r.get("y").asFloat, clickCount = 2)
            evalJson(
                tab.webView,
                "window.__cb.overlay.cursor(${r.get("x").asFloat}, ${r.get("y").asFloat}, true)"
            )
            "double-clicked <${r.get("tag")?.asString ?: "?"}>"
        }

        "type" -> {
            val tab = tabArg(a) ?: return "no active tab"
            ensureLib(tab)
            val target = a.get("target").asString
            val text = a.get("text").asString
            val clear = a.get("clear")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
            val submit = a.get("submit")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            val r = evalJson(tab.webView, "window.__cb.resolve(${jsStr(target)})")?.asJsonObject
                ?: return "ERROR: resolve failed"
            if (r.get("error") != null) return r.get("error").asString
            val tag = r.get("tag")?.asString ?: "?"
            val label = r.get("text")?.asString ?: ""
            showStatus(tab, "AI 正在输入…", 4200)
            showTarget(tab, r, false)
            val ins = evalJson(
                tab.webView,
                "window.__cb.typeInto(${jsStr(target)}, ${jsStr(text)}, $clear)"
            )?.asJsonObject
            if (ins?.get("error") != null) return ins.get("error").asString
            hideIme(tab.webView)
            if (submit) {
                evalJson(tab.webView, "window.__cb.submitFrom(${jsStr(target)})")
                delay(300)
                waitForLoad(tab)
                hideIme(tab.webView)
            }
            "typed ${text.length} chars into <$tag>${if (label.isNotBlank()) " \"${label.take(40)}\"" else ""} (via ${ins?.get("via")?.asString ?: "?"})${if (submit) " and submitted" else ""}"
        }

        "press" -> {
            val tab = tabArg(a) ?: return "no active tab"
            val combo = a.get("key").asString
            val spec = keySpec(combo) ?: return "unsupported key: $combo"
            withContext(Dispatchers.Main) {
                val wv = tab.webView
                val down = KeyEvent(
                    0, 0, KeyEvent.ACTION_DOWN, spec.code, 0, spec.meta
                )
                val up = KeyEvent(0, 0, KeyEvent.ACTION_UP, spec.code, 0, spec.meta)
                wv.dispatchKeyEvent(down)
                wv.dispatchKeyEvent(up)
            }
            delay(200)
            "pressed $combo"
        }

        "scroll" -> {
            val tab = tabArg(a) ?: return "no active tab"
            ensureLib(tab)
            val sel = a.get("selector")?.takeIf { it.isJsonPrimitive }?.asString
            val dy = a.get("dy")?.takeIf { it.isJsonPrimitive }?.asInt ?: 600
            val dx = a.get("dx")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            val selArg = if (sel.isNullOrBlank()) "null" else jsStr(sel)
            val r = evalJson(tab.webView, "window.__cb.scroll($selArg, $dy, $dx)")
            r?.toString() ?: "(no result)"
        }

        "wait" -> {
            val tab = tabArg(a) ?: return "no active tab"
            ensureLib(tab)
            val ms = (a.get("ms")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L).coerceAtMost(30000L)
            val sel = a.get("selector")?.takeIf { it.isJsonPrimitive }?.asString
            val text = a.get("text")?.takeIf { it.isJsonPrimitive }?.asString
            val timeout = a.get("timeout")?.takeIf { it.isJsonPrimitive }?.asLong ?: 10000L
            if (ms > 0) delay(ms)
            if (!sel.isNullOrBlank() || !text.isNullOrBlank()) {
                val deadline = System.currentTimeMillis() + timeout
                var ok = false
                while (System.currentTimeMillis() < deadline) {
                    if (!sel.isNullOrBlank()) {
                        val r = evalJson(tab.webView, "window.__cb.exists(${jsStr(sel)})")
                        if (r?.isJsonPrimitive == true && r.asBoolean) { ok = true; break }
                    }
                    if (!text.isNullOrBlank()) {
                        val r = evalJson(tab.webView, "window.__cb.hasText(${jsStr(text)})")
                        if (r?.isJsonPrimitive == true && r.asBoolean) { ok = true; break }
                    }
                    delay(300)
                }
                return if (ok) "waited ${ms}ms; condition met"
                else "waited; condition NOT met within ${timeout}ms"
            }
            "waited ${ms}ms"
        }

        "get_console" -> {
            val tab = tabArg(a) ?: return "no active tab"
            val limit = a.get("limit")?.takeIf { it.isJsonPrimitive }?.asInt ?: 50
            val clear = a.get("clear")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            val lines = tab.console.takeLast(limit)
            if (clear) tab.console.clear()
            if (lines.isEmpty()) "(console is empty)" else lines.joinToString("\n")
        }

        "search" -> {
            val tab = tabArg(a) ?: return "no active tab"
            val query = a.get("query").asString
            val engine = a.get("engine")?.takeIf { it.isJsonPrimitive }?.asString
            val url = Address.searchUrl(query, engine)
            withContext(Dispatchers.Main) { tab.webView.loadUrl(url) }
            waitForLoad(tab)
            "searched \"$query\"\nurl: ${tab.url}\ntitle: ${tab.title}"
        }

        "evaluate" -> {
            val tab = tabArg(a) ?: return "no active tab"
            val script = a.get("script").asString
            val wrapped =
                "(function(){ try { $script\n } catch (e) { return 'ERROR: ' + (e && e.message ? e.message : e); } })()"
            val out = evalString(tab.webView, wrapped)
            if (out.length > 8000) out.take(8000) + "\n…(truncated)"
            else if (out.isBlank()) "(no output)"
            else out
        }

            else -> "ERROR: unknown tool: $name"
        }
    }

    private data class KeySpec(val code: Int, val meta: Int)

    private fun keySpec(combo: String): KeySpec? {
        val parts = combo.split("+").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var meta = 0
        for (p in parts.dropLast(1)) {
            when (p.lowercase()) {
                "ctrl", "control" -> meta = meta or KeyEvent.META_CTRL_ON
                "alt" -> meta = meta or KeyEvent.META_ALT_ON
                "shift" -> meta = meta or KeyEvent.META_SHIFT_ON
                "meta", "cmd", "win" -> meta = meta or KeyEvent.META_META_ON
                else -> return null
            }
        }
        val key = parts.last()
        val code = when (key.lowercase()) {
            "enter" -> KeyEvent.KEYCODE_ENTER
            "escape", "esc" -> KeyEvent.KEYCODE_ESCAPE
            "tab" -> KeyEvent.KEYCODE_TAB
            "backspace" -> KeyEvent.KEYCODE_DEL
            "delete" -> KeyEvent.KEYCODE_FORWARD_DEL
            "space" -> KeyEvent.KEYCODE_SPACE
            "pagedown" -> KeyEvent.KEYCODE_PAGE_DOWN
            "pageup" -> KeyEvent.KEYCODE_PAGE_UP
            "end" -> KeyEvent.KEYCODE_MOVE_END
            "home" -> KeyEvent.KEYCODE_MOVE_HOME
            "arrowleft" -> KeyEvent.KEYCODE_DPAD_LEFT
            "arrowright" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "arrowup" -> KeyEvent.KEYCODE_DPAD_UP
            "arrowdown" -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> {
                if (key.length == 1 && key[0].isLetter()) {
                    KeyEvent.KEYCODE_A + (key[0].uppercaseChar() - 'A')
                } else return null
            }
        }
        return KeySpec(code, meta)
    }
}
