package com.duplex.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.duplex.mobile.SettingsStore
import com.duplex.mobile.agent.ProviderStore

/** 设置：主题（跟随系统/浅色/深色）+ 供应商与 API 管理（多套配置，分类保存，单选切换）。 */
@Composable
fun SettingsDialog(providers: ProviderStore, settings: SettingsStore, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<ProviderStore.Provider?>(null) }
    var deleting by remember { mutableStateOf<ProviderStore.Provider?>(null) }
    var label by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }

    fun load(p: ProviderStore.Provider?) {
        editing = p
        label = p?.label ?: ""
        base = p?.baseUrl ?: ""
        key = p?.apiKey ?: ""
        model = p?.model ?: ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 600.dp)
            ) {
                // ---- 主题 ----
                Text("主题", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row {
                    SettingsStore.THEME_OPTIONS.forEach { (value, name) ->
                        FilterChip(
                            selected = settings.theme == value,
                            onClick = { settings.updateTheme(value) },
                            label = { Text(name) },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // ---- 供应商列表 ----
                Text("供应商与 API", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                if (providers.providers.isEmpty()) {
                    Text(
                        "还没有配置。点下面的厂商预设，填好 Key 保存即可对话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                providers.providers.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = providers.active?.id == p.id,
                            onClick = { providers.setActive(p.id) }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(p.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOf(p.baseUrl, p.model).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { load(p) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑")
                        }
                        IconButton(onClick = { deleting = p }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                    }
                    HorizontalDivider()
                }

                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ProviderStore.PRESETS.forEach { preset ->
                        AssistChip(
                            onClick = {
                                label = preset.label
                                base = preset.baseUrl
                                if (preset.model.isNotBlank()) model = preset.model
                            },
                            label = { Text(preset.label) },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名称（厂商）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    label = { Text("API 地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (editing != null) {
                        TextButton(onClick = { load(null) }) { Text("取消编辑") }
                    }
                    Button(
                        onClick = {
                            val cur = editing
                            if (cur != null) {
                                providers.update(cur.id, label, base, key, model)
                            } else {
                                providers.add(label.ifBlank { model }, base, key, model)
                            }
                            load(null)
                        },
                        enabled = base.isNotBlank() && model.isNotBlank()
                    ) {
                        Text(if (editing != null) "更新配置" else "保存新配置")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除配置") },
            text = { Text("删除「${p.label}」？保存的 Key 会一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    if (editing?.id == p.id) load(null)
                    providers.remove(p.id)
                    deleting = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }
}
