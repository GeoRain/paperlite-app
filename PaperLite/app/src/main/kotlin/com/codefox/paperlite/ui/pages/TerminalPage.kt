package com.codefox.paperlite.ui.pages

import com.codefox.paperlite.ui.components.PageTabs
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codefox.paperlite.core.Channel
import com.codefox.paperlite.core.ChannelManager
import com.codefox.paperlite.core.ExecResult
import com.codefox.paperlite.ui.components.AnimatedGroup
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.BulletRow
import com.codefox.paperlite.ui.components.HamburgerMenu
import com.codefox.paperlite.ui.components.InfoRow
import com.codefox.paperlite.ui.components.MenuEntry
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.components.TextInput
import com.codefox.paperlite.ui.components.ToastMessage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
* 终端页。
*
* 使用习惯对齐 Termux：命令历史（上下键翻）、字号可调、常用命令分组、清屏。
* 执行通道按 Root → 无线调试 → 本地回环 → 本进程 的顺序择一。
*/
/** 终端页的高频分组（页内 Tab 栏）：内部标识 + 显示名。 */
private val TERMINAL_TABS: List<Pair<String, String>> = listOf(
    "exec" to "执行",
    "preset" to "常用命令",
)

@Composable
fun TerminalPage(channels: ChannelManager) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var cmd by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // 命令历史：新的在前，最多保留 50 条
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    // 历史游标：-1 表示不在历史里（正在编辑新命令）
    var historyIndex by remember { mutableStateOf(-1) }
    // 字号，像 Termux 那样可调
    var fontSize by remember { mutableStateOf(12f) }
    // 页内分组，由汉堡菜单切换
    var section by remember { mutableStateOf("exec") }

    val states by channels.states.collectAsStateWithLifecycle()
    val canRun = states.values.any { it.available }
    val wirelessReady = states[Channel.Wireless]?.available == true

    /** 常用命令按功能分组，覆盖原先各页面被删掉的能力。 */
    val presetGroups = remember {
        listOf(
            "系统信息" to listOf(
                "getprop ro.build.display.id" to "系统版本",
                "getprop ro.product.model" to "机型",
                "id" to "当前身份",
            ),
            "应用管理" to listOf(
                "pm list packages -d" to "已禁用应用",
                "pm list packages -3" to "第三方应用",
                "pm list packages com.zuoyebang" to "作业帮相关包",
                "pm path com.zuoyebang.padms" to "管控组件路径",
            ),
            "系统管控" to listOf(
                "dumpsys device_policy" to "设备所有者状态",
                "pm disable-user --user 0 com.zuoyebang.padms" to "停用系统管控",
                "pm enable com.zuoyebang.padms" to "恢复系统管控",
                "settings get global adb_wifi_enabled" to "无线调试开关",
            ),
            "网络与进程" to listOf(
                "ps -A | head -20" to "进程列表",
                "ip addr" to "网络接口",
                "getprop | grep -i build" to "构建信息",
            ),
        )
    }

    fun run(command: String) {
        if (command.isBlank()) return
        busy = true
        scope.launch {
            val r = runCatching { channels.exec(command) }
            .getOrElse { ExecResult(false, "", "异常", it.message ?: "执行失败") }
            val out = buildString {
                append("$ ")
                appendLine(command)
                appendLine("通道：${r.via}　退出码：${if (r.ok) 0 else 1}")
                if (r.output.isNotBlank()) appendLine(r.output.trimEnd())
                if (r.error != null) appendLine("错误：${r.error}")
            }
            lines = (lines + out).takeLast(200)
            // 记历史：与 Termux 一样，同一条命令连续执行只留一份
            history = (listOf(command) + history.filter { it != command }).take(50)
            historyIndex = -1
            busy = false
        }
    }

    /** 上一条历史（Termux 的音量上键）。 */
    fun historyPrev() {
        if (history.isEmpty()) return
        val next = if (historyIndex < 0) 0 else (historyIndex + 1).coerceAtMost(history.size - 1)
        historyIndex = next
        cmd = history[next]
    }

    /** 下一条历史（Termux 的音量下键）。 */
    fun historyNext() {
        if (history.isEmpty()) return
        if (historyIndex <= 0) {
            historyIndex = -1
            cmd = ""
            return
        }
        historyIndex -= 1
        cmd = history[historyIndex]
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "header") {
            PageHeader(
                title = "命令行",
                kicker = if (wirelessReady) "无线调试会话已建立" else "配对后可执行高权限命令",
                trailing = {
                    HamburgerMenu(
                        items = listOf(
                            // 历史是低频功能，收进菜单；
                            // 「执行」「常用命令」用页内 Tab
                            MenuEntry("历史", "最近执行过的命令") { section = "history" },
                        ),
                    )
                },
            )
        }

        // 页内 Tab 栏：只在本页显示
        item(key = "tabs") {
            PageTabs<Pair<String, String>>(
                options = TERMINAL_TABS,
                selected = TERMINAL_TABS.firstOrNull { it.first == section }
                    ?: TERMINAL_TABS.first(),
                label = { it.second },
                onSelect = { section = it.first },
            )
        }

        item(key = "hint") {
            BulletRow(
                if (canRun) "命令按 Root → 无线调试 → 本地回环 → 本进程 的顺序择一执行"
                else "当前没有高权限通道，命令只能以本应用权限执行",
            )
        }

        if (section == "exec") item(key = "input") {
            AnimatedGroup {
                SectionCard(title = "命令输入", subtitle = "空格分隔参数") {
                    TextInput(
                        value = cmd,
                        onValueChange = {
                            cmd = it
                            historyIndex = -1
                        },
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
                            text = "↑ 历史",
                            onClick = { historyPrev() },
                        )
                        ActionButton(
                            text = "↓ 历史",
                            onClick = { historyNext() },
                        )
                    }
                    Row(
                        modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ActionButton(
                            text = "清空输入",
                            onClick = { cmd = "" },
                        )
                        ActionButton(
                            text = "清屏",
                            onClick = {
                                lines = emptyList()
                                message = "输出已清空（命令历史保留）"
                            },
                        )
                        ActionButton(
                            text = "A-",
                            onClick = { fontSize = (fontSize - 1f).coerceAtLeast(9f) },
                        )
                        ActionButton(
                            text = "A+",
                            onClick = { fontSize = (fontSize + 1f).coerceAtMost(22f) },
                        )
                    }
                }
            }
        }

        if (section == "preset") item(key = "quick") {
            AnimatedGroup {
                SectionCard(title = "常用命令", subtitle = "按功能分组，点击即执行") {
                    Column {
                        presetGroups.forEach { (group, items) ->
                            InfoRow(group, "")
                            items.forEach { (q, desc) ->
                                Row(
                                    modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Metrics.inset, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
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
            }
        }

        if (history.isNotEmpty() && section == "history") {
            item(key = "history-title") {
                Text(
                    text = "命令历史",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(start = Metrics.pad + 4.dp, end = Metrics.pad, top = 12.dp, bottom = 4.dp),
                )
            }
            item(key = "history") {
                Card(
                    modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Metrics.pad, vertical = 4.dp),
                ) {
                    Column {
                        history.take(12).forEach { h ->
                            Text(
                                text = h,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .fillMaxWidth(),
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
                                    fontSize = fontSize.sp,
                                    maxLines = 60,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "sp") { Spacer(Modifier.height(8.dp)) }
    }

    Box(
        modifier = Modifier
        .fillMaxSize()
        .padding(bottom = 24.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        ToastMessage(text = message)
    }
}
