package com.duplex.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.duplex.mobile.SettingsStore
import com.duplex.mobile.agent.ChatController
import com.duplex.mobile.agent.ChatMessage

/** 右侧 AI 聊天面板：手机全屏抽屉 / 平板侧栏通用。 */
@Composable
fun ChatPanel(
    controller: ChatController,
    settings: SettingsStore,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSettings by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val cfg = controller.config

    fun submitInput() {
        if (input.isNotBlank()) {
            controller.send(input.trim())
            input = ""
            keyboard?.hide()
        }
    }

    // 新消息 / 流式更新时贴住底部
    LaunchedEffect(controller.messages.size, controller.messages.lastOrNull()?.text) {
        if (controller.messages.isNotEmpty()) {
            listState.scrollToItem(controller.messages.size - 1)
        }
    }

    Surface(modifier, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Duplex AI", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (controller.status.isNotBlank()) {
                    Text(
                        controller.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                IconButton(onClick = { controller.newChat() }) {
                    Icon(Icons.Filled.Add, contentDescription = "新对话")
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭面板")
                }
            }
            HorizontalDivider()

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (controller.messages.isEmpty()) {
                    item {
                        Text(
                            if (cfg.configured) {
                                "让 AI 帮你操作浏览器，例如：\n「打开百度，搜索 DeepSeek 最新消息」\n「去 B 站找一个讲 CUDA 的视频」"
                            } else {
                                "还没有配置模型 API。点右上角 ⚙ 填一个 OpenAI 兼容接口（DeepSeek / Kimi / GLM / Ollama 都可以）。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(controller.messages, key = { it.id }) { m ->
                    ChatBubble(m)
                }
            }

            HorizontalDivider()

            // 模型快速切换栏
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { modelMenu = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            cfg.active?.let {
                                listOf(it.label, it.model).filter { s -> s.isNotBlank() }.joinToString(" · ")
                            } ?: "配置模型 API",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "选择模型",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        cfg.providers.forEach { p ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        listOf(p.label, p.model).filter { it.isNotBlank() }.joinToString(" · "),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = {
                                    if (cfg.active?.id == p.id) {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    } else {
                                        Spacer(Modifier.size(24.dp))
                                    }
                                },
                                onClick = {
                                    cfg.setActive(p.id)
                                    modelMenu = false
                                }
                            )
                        }
                        if (cfg.providers.isNotEmpty()) HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("管理配置…") },
                            leadingIcon = { Spacer(Modifier.size(24.dp)) },
                            onClick = {
                                modelMenu = false
                                showSettings = true
                            }
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("让 AI 操作浏览器…", style = MaterialTheme.typography.bodySmall) },
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitInput() })
                )
                if (controller.running) {
                    IconButton(onClick = { controller.stop() }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "停止",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    IconButton(onClick = { submitInput() }) {
                        Icon(Icons.Filled.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(cfg, settings, onDismiss = { showSettings = false })
    }
}

@Composable
private fun ChatBubble(m: ChatMessage) {
    when (m.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Text(m.text, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        "assistant" -> if (m.text.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.widthIn(max = 380.dp)
                ) {
                    MarkdownText(
                        m.text,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        "tool" -> ToolCard(m)

        else -> Text(
            m.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ToolCard(m: ChatMessage) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            val color = when (m.toolStatus) {
                "running" -> MaterialTheme.colorScheme.primary
                "error" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.secondary
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "⚙ ${m.toolName ?: "tool"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    when (m.toolStatus) {
                        "running" -> "运行中…"
                        "error" -> "失败"
                        else -> "完成"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = color
                )
            }
            if (!m.toolDetail.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    m.toolDetail ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
