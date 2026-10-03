package com.duplex.mobile.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 单个标签页：一个独立 WebView + 页面状态。 */
class BrowserTab(val id: Int, val webView: WebView) {
    var title by mutableStateOf("新标签页")
    var url by mutableStateOf("")
    var isLoading by mutableStateOf(false)
    var progress by mutableStateOf(0)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    /** 最近的 console 消息（get_console 工具用）。 */
    val console = mutableStateListOf<String>()

    val isBlank: Boolean get() = url.isBlank() && !isLoading
}

/** 多标签管理：与桌面版 TabManager 相同的心智模型（一个标签一个视图）。 */
class TabManager(private val context: Context) {
    val tabs = mutableStateListOf<BrowserTab>()
    var activeId by mutableStateOf<Int?>(null)

    private var nextId = 1

    val active: BrowserTab?
        get() = tabs.firstOrNull { it.id == activeId }

    @SuppressLint("SetJavaScriptEnabled")
    fun createTab(initialUrl: String? = null): BrowserTab {
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        val tab = BrowserTab(nextId++, wv)
        wv.webViewClient = object : WebViewClient() {
            /** 非 http(s) 协议不在 WebView 里打开：交给系统 App，没有则静默忽略。 */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: return false
                if (scheme == "http" || scheme == "https" || scheme == "about" ||
                    scheme == "data" || scheme == "file" || scheme == "blob"
                ) return false
                if (request.isForMainFrame) {
                    try {
                        val ctx = view?.context ?: return true
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, uri)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                        /* 设备上没有对应 App：忽略 */
                    }
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                tab.url = url ?: tab.url
                tab.isLoading = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tab.url = url ?: tab.url
                tab.isLoading = false
                tab.canGoBack = view?.canGoBack() ?: false
                tab.canGoForward = view?.canGoForward() ?: false
                val t = view?.title
                if (!t.isNullOrBlank()) tab.title = t
                else if (tab.title == "新标签页" && !tab.url.isBlank()) tab.title = tab.url
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            /** target=_blank / window.open：开一个新标签页接管。 */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val newTab = createTab()
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = newTab.webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                tab.progress = newProgress
                tab.isLoading = newProgress < 100
            }

            override fun onReceivedTitle(view: WebView?, t: String?) {
                if (!t.isNullOrBlank()) tab.title = t
            }

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                if (tab.console.size > 300) tab.console.removeAt(0)
                tab.console.add(
                    "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})"
                )
                return true
            }
        }
        if (!initialUrl.isNullOrBlank() && initialUrl != "about:blank") wv.loadUrl(initialUrl)
        tabs.add(tab)
        activeId = tab.id
        return tab
    }

    fun closeTab(id: Int) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = tabs[idx]
        detach(tab)
        tab.webView.destroy()
        tabs.removeAt(idx)
        if (activeId == id) {
            activeId = tabs.getOrNull(idx - 1)?.id ?: tabs.firstOrNull()?.id
        }
        if (tabs.isEmpty()) createTab()
    }

    fun select(id: Int) {
        if (tabs.any { it.id == id }) activeId = id
    }

    /** 把指定标签的 WebView 挂到容器上（切换标签时换视图）。 */
    fun attachInto(container: ViewGroup, tab: BrowserTab?) {
        if (tab == null) return
        val wv = tab.webView
        if (wv.parent === container) return
        (wv.parent as? ViewGroup)?.removeView(wv)
        container.removeAllViews()
        container.addView(
            wv,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    fun destroyAll() {
        tabs.forEach { tab ->
            detach(tab)
            tab.webView.destroy()
        }
        tabs.clear()
        activeId = null
    }

    private fun detach(tab: BrowserTab) {
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
    }
}
