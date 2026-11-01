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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codefox.paperlite.data.DeviceInfo
import com.codefox.paperlite.data.Settings
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.BulletRow
import com.codefox.paperlite.ui.components.InfoRow
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.components.SegmentedTabs
import com.codefox.paperlite.ui.components.TextInput
import com.codefox.paperlite.ui.components.ToggleRow
import com.codefox.paperlite.ui.components.ToastMessage
import com.codefox.paperlite.ui.theme.ThemeMode
import com.codefox.paperlite.ui.theme.displayName
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页。
 *
 * 外观、服务器、通道参数、运行日志、异常记录。
 * 所有按钮都可点：不能用的操作在点击后给出明确提示，而不是把按钮置灰。
 */
@Composable
fun SettingsPage(
    settings: Settings,
    darkTheme: Boolean,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    var loopPort by remember { mutableStateOf(settings.localLoopPort.value.toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }

    val hideUnavailable by settings.hideUnavailable.collectAsStateWithLifecycle()
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val fontScale by settings.fontScale.collectAsStateWithLifecycle()
    val logs by settings.logLines.collectAsStateWithLifecycle()
    // 端口在配对成功后才会写入，必须订阅，否则页面一直显示旧值
    val pairPort by settings.pairPort.collectAsStateWithLifecycle()
    val connectPort by settings.connectPort.collectAsStateWithLifecycle()

    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            PageHeader(
                title = "设置",
                kicker = "外观 · 通道 · 日志",
            )
        }

        item(key = "appearance") {
            SectionCard(title = "外观") {
                SegmentedTabs(
                    options = ThemeMode.entries,
                    selected = themeMode,
                    label = { it.displayName() },
                    onSelect = { settings.setThemeMode(it) },
                    modifier = Modifier.padding(horizontal = 0.dp, vertical = 2.dp),
                )
                SegmentedTabs(
                    options = listOf(90, 100, 110, 120),
                    selected = fontScale,
                    label = { "$it%" },
                    onSelect = { settings.setFontScale(it) },
                    modifier = Modifier.padding(horizontal = 0.dp, vertical = 2.dp),
                )
                ToggleRow(
                    title = "隐藏不可用按钮",
                    subtitle = "通道不支持时直接隐藏，而不是置灰",
                    checked = hideUnavailable,
                    onCheckedChange = { settings.setHideUnavailable(it) },
                )
            }
        }

        item(key = "channel") {
            SectionCard(title = "通道参数") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextInput(
                        value = loopPort,
                        onValueChange = { loopPort = it.filter { c -> c.isDigit() }.take(5) },
                        label = "本地回环端口",
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    ActionButton(
                        text = "保存",
                        onClick = {
                            settings.setLocalLoopPort(loopPort.toIntOrNull() ?: 5555)
                            message = "本地回环端口已设为 ${settings.localLoopPort.value}"
                        },
                    )
                }
                InfoRow("配对端口", pairPort.toString())
                InfoRow("连接端口", connectPort.toString())
            }
        }

        item(key = "log") {
            SectionCard(title = "运行日志", subtitle = "共 ${logs.size} 条") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    ActionButton(
                        text = if (showLog) "收起" else "查看",
                        onClick = { showLog = !showLog },
                    )
                    ActionButton(
                        text = "复制",
                        onClick = {
                            clipboard.setText(AnnotatedString(settings.logText()))
                            message = "日志已复制"
                        },
                    )
                    ActionButton(
                        text = "清空",
                        onClick = {
                            settings.clearLog()
                            message = "日志已清空"
                        },
                    )
                }
                if (showLog) {
                    SelectionContainer {
                        Column(modifier = Modifier.padding(horizontal = Metrics.inset, vertical = 2.dp)) {
                            logs.takeLast(120).forEach { line ->
                                Text(
                                    text = line,
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "crash") {
            SectionCard(
                title = "上次异常退出",
                subtitle = "把这里的堆栈发给我就能定位闪退",
            ) {
                var crash by remember { mutableStateOf<String?>(null) }
                var expanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    ActionButton(
                        text = if (expanded) "收起" else "查看",
                        onClick = {
                            if (!expanded) crash = com.codefox.paperlite.core.CrashGuard.readLast(context)
                            expanded = !expanded
                        },
                    )
                    ActionButton(
                        text = "复制",
                        onClick = {
                            val t = crash
                                ?: com.codefox.paperlite.core.CrashGuard.readLast(context)
                            if (t.isNullOrBlank()) {
                                message = "没有异常记录"
                            } else {
                                clipboard.setText(AnnotatedString(t))
                                message = "异常堆栈已复制"
                            }
                        },
                    )
                }
                if (expanded) {
                    if (crash.isNullOrBlank()) {
                        BulletRow("没有异常记录，说明最近一次是正常退出。")
                    } else {
                        SelectionContainer {
                            Column(modifier = Modifier.padding(horizontal = Metrics.inset, vertical = 2.dp)) {
                                crash.orEmpty().lineSequence().take(60).forEach { line ->
                                    Text(
                                        text = line,
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "about-entry") {
            SectionCard(title = "环境") {
                InfoRow("本机设备码", DeviceInfo.deviceCode(context))
                InfoRow("应用版本", com.codefox.paperlite.BuildConfig.VERSION_NAME)
                InfoRow("包名", context.packageName)
            }
        }
    }
        // 反馈消息：从底部滑入淡出
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            ToastMessage(text = message)
        }
    }
}
