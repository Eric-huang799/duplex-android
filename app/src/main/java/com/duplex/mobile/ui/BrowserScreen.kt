package com.duplex.mobile.ui

import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.duplex.mobile.agent.ChatController
import com.duplex.mobile.agent.ProviderStore
import com.duplex.mobile.agent.ToolBridge
import com.duplex.mobile.browser.TabManager
import com.duplex.mobile.shared.Address

/** 浏览器主界面：工具栏 + WebView 容器 + 起始页 + 标签总览 + AI 面板。 */
@Composable
fun BrowserScreen(tabs: TabManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val provider = remember { ProviderStore(context) }
    val bridge = remember { ToolBridge(tabs) }
    val chat = remember { ChatController(scope, bridge, provider) }

    LaunchedEffect(Unit) {
        if (tabs.tabs.isEmpty()) tabs.createTab()
    }

    val active = tabs.active
    var address by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var switcherOpen by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val wide = LocalConfiguration.current.screenWidthDp >= 700

    LaunchedEffect(active?.id, active?.url, editing) {
        if (!editing) address = active?.url ?: ""
    }
    LaunchedEffect(active?.progress, active?.isLoading) {
        progress = active?.progress ?: 0
        loading = active?.isLoading ?: false
    }

    fun open(input: String) {
        if (input.isBlank()) return
        tabs.active?.webView?.loadUrl(Address.resolve(input))
        editing = false
        keyboard?.hide()
    }

    BackHandler(enabled = switcherOpen) { switcherOpen = false }
    BackHandler(enabled = !switcherOpen && !wide && panelOpen) { panelOpen = false }
    BackHandler(enabled = !switcherOpen && !panelOpen && active?.canGoBack == true) {
        tabs.active?.webView?.goBack()
    }

    val browserPane: @Composable (Modifier) -> Unit = { m ->
        Column(m.statusBarsPadding()) {
            Surface(tonalElevation = 3.dp) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { tabs.active?.webView?.goBack() },
                            enabled = active?.canGoBack == true
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "后退")
                        }
                        IconButton(
                            onClick = { tabs.active?.webView?.goForward() },
                            enabled = active?.canGoForward == true
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "前进")
                        }
                        OutlinedTextField(
                            value = address,
                            onValueChange = {
                                address = it
                                editing = true
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            placeholder = {
                                Text("搜索或输入网址", style = MaterialTheme.typography.bodyMedium)
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { open(address) }),
                            shape = RoundedCornerShape(22.dp)
                        )
                        IconButton(onClick = { tabs.active?.webView?.reload() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                        OutlinedButton(
                            onClick = { panelOpen = !panelOpen },
                            modifier = Modifier.size(44.dp),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = if (panelOpen) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            } else ButtonDefaults.outlinedButtonColors()
                        ) {
                            Text("AI", style = MaterialTheme.typography.labelLarge)
                        }
                        OutlinedButton(
                            onClick = { switcherOpen = true },
                            modifier = Modifier.size(44.dp),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("${tabs.tabs.size}")
                        }
                    }
                    if (loading) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> FrameLayout(ctx) },
                    update = { container -> tabs.attachInto(container, active) }
                )
                if (active != null && active.isBlank) {
                    Surface(Modifier.fillMaxSize()) {
                        StartPage(onSearch = { open(it) })
                    }
                }
            }
        }
    }

    if (wide && panelOpen) {
        Row(Modifier.fillMaxSize()) {
            browserPane(Modifier.weight(1f).fillMaxHeight())
            ChatPanel(
                controller = chat,
                onClose = { panelOpen = false },
                modifier = Modifier.width(400.dp).fillMaxHeight()
            )
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            browserPane(Modifier.fillMaxSize())
            if (panelOpen) {
                ChatPanel(
                    controller = chat,
                    onClose = { panelOpen = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (switcherOpen) {
        TabSwitcher(tabs = tabs, onDismiss = { switcherOpen = false })
    }
}
