package com.codefox.paperlite.ui.pages

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codefox.paperlite.data.DeviceInfo
import com.codefox.paperlite.data.InfoGroup
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.EmptyHint
import com.codefox.paperlite.ui.components.InfoRow
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设备信息页。
 *
 * 系统属性、内存存储、可见进程。全部在 IO 线程采集，主线程只负责画。
 */
@Composable
fun DevicePage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var groups by remember { mutableStateOf<List<InfoGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showProcesses by remember { mutableStateOf(false) }
    var processes by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // /proc 遍历 + 每个 pid 读 cmdline，几百个进程在主线程做必然 ANR
        groups = withContext(Dispatchers.IO) {
            runCatching { DeviceInfo.basic(context) }.getOrDefault(emptyList())
        }
        loading = false
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "header") {
            PageHeader(
                title = "设备",
                kicker = if (loading) "正在读取…" else "${groups.sumOf { it.rows.size }} 项信息",
            )
        }

        if (loading) {
            item(key = "loading") { EmptyHint("正在读取系统信息…") }
        }

        items(groups, key = { it.title }) { group ->
            SectionCard(title = group.title) {
                group.rows.forEach { (k, v) ->
                    InfoRow(k, v.ifBlank { "未获取" }, mono = k.contains("码") || k.contains("指纹"))
                }
            }
        }

        item(key = "proc") {
            SectionCard(
                title = "进程列表",
                subtitle = if (showProcesses) "共 ${processes.size} 个可见进程" else "需要手动扫描",
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionButton(
                        text = when {
                            scanning -> "扫描中…"
                            showProcesses -> "收起"
                            else -> "扫描进程"
                        },
                        onClick = {
                            if (showProcesses) {
                                showProcesses = false
                            } else {
                                scanning = true
                                scope.launch {
                                    processes = withContext(Dispatchers.IO) {
                                        runCatching { DeviceInfo.processes() }.getOrDefault(emptyList())
                                    }
                                    showProcesses = true
                                    scanning = false
                                }
                            }
                        },
                    )
                }
                if (showProcesses) {
                    SelectionContainer {
                        Column {
                            processes.take(40).forEach { (pid, name) ->
                                Text(
                                    text = "$pid  $name",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = Metrics.inset, vertical = 1.dp),
                                )
                            }
                            if (processes.size > 40) {
                                Text(
                                    text = "… 其余 ${processes.size - 40} 个已省略",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = Metrics.inset, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "sp") { Spacer(Modifier.height(8.dp)) }
    }
}
