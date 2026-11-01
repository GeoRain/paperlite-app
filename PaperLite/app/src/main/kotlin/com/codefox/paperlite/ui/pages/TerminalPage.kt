package com.codefox.paperlite.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codefox.paperlite.core.Channel
import com.codefox.paperlite.core.ChannelManager
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.BulletRow
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.components.TextInput
import com.codefox.paperlite.ui.components.ToastMessage
import com.codefox.paperlite.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 终端页。
 *
 * 无线调试配对成功后，这里就是「设备所有者 / ADB / 系统管控」等所有
 * 高级能力的统一入口——用命令表达，不再为每种权限单开一个页面。
 */
@Composable
fun TerminalPage(channels: ChannelManager) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var cmd by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    val states by channels.states.collectAsStateWithLifecycle()
    val canRun = states.values.any { it.available }
    val wirelessReady = states[Channel.Wireless]?.available == true

    /** 常用命令按功能分组，覆盖原先各页面被删掉的能力。 */
    val presets = remember {
        listOf(
            "getprop ro.build.display.id" to "系统版本",
            "pm list packages -d" to "已禁用应用",
            "pm list packages com.zuoyebang" to "作业帮相关包",
            "dumpsys device_policy" to "设备所有者状态",
            "pm disable-user --user 0 com.zuoyebang.padms" to "停用系统管控",
            "pm enable com.zuoyebang.padms" to "恢复系统管控",
            "settings get global adb_wifi_enabled" to "无线调试开关",
        )
    }

    fun run(command: String) {
        if (command.isBlank()) return
        busy = true
        scope.launch {
            val r = runCatching { channels.exec(command) }.getOrElse {
                com.codefox.paperlite.core.ExecResult(false, "", "异常", it.message ?: "执行失败")
            }
            val out = buildString {
                append("$ ")
                appendLine(command)
                appendLine("通道：${r.via}　退出码：${if (r.ok) 0 else 1}")
                if (r.output.isNotBlank()) appendLine(r.output.trimEnd())
                if (r.error != null) appendLine("错误：${r.error}")
            }
            lines = (lines + out).takeLast(40)
            busy = false
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "header") {
            PageHeader(
                title = "终端",
                kicker = if (wirelessReady) "无线调试会话已建立" else "配对后可执行高权限命令",
            )
        }

        item(key = "hint") {
            BulletRow(
                if (canRun) "命令按 Root → 无线调试 → 本地回环 → 本进程 的顺序择一执行"
                else "当前没有高权限通道，命令只能以本应用权限执行",
            )
        }

        item(key = "input") {
            SectionCard(title = "命令输入", subtitle = "空格分隔参数") {
                TextInput(
                    value = cmd,
                    onValueChange = { cmd = it },
                    label = "输入 shell 命令",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 4.dp),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionButton(
                        text = if (busy) "执行中…" else "执行",
                        onClick = { run(cmd.trim()) },
                    )
                    ActionButton(
                        text = "清空输入",
                        onClick = { cmd = "" },
                    )
                }
            }
        }

        item(key = "quick") {
            SectionCard(title = "常用命令") {
                Column {
                    presets.forEach { (q, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Metrics.inset, vertical = 3.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = q,
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = desc,
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            ActionButton(
                                text = "执行",
                                onClick = { run(q) },
                            )
                        }
                    }
                }
            }
        }

        if (lines.isNotEmpty()) {
            item(key = "out-title") {
                Text(
                    text = "输出",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(start = Metrics.pad + 4.dp, end = Metrics.pad, top = 12.dp, bottom = 4.dp),
                )
            }
            item(key = "out") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.pad, vertical = 4.dp),
                ) {
                    SelectionContainer {
                        Column {
                            lines.forEach { block ->
                                Text(
                                    text = block,
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    maxLines = 40,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
