package com.codefox.paperlite.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codefox.paperlite.core.ChannelManager
import com.codefox.paperlite.core.Installer
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.BulletRow
import com.codefox.paperlite.ui.components.ConfirmDialog
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.components.TextInput
import com.codefox.paperlite.ui.components.ToastMessage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 卸载与管控主体。
 *
 * 核心是停用系统管控服务 `com.zuoyebang.padms`，让系统不再按黑名单删应用。
 */
@Composable
fun UninstallPage(
    channels: ChannelManager,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = remember { Installer(context, channels) }
    val listState = rememberLazyListState()

    var pkg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }
    // 待确认的卸载目标：非 null 时弹确认框
    var pendingUninstall by remember { mutableStateOf<String?>(null) }

    val states by channels.states.collectAsStateWithLifecycle()
    val canRun = states.values.any { it.available }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 12.dp),
    ) {
        item(key = "header") {
            PageHeader(
                title = "卸载",
                kicker = "输入包名，或在应用页选择后操作",
            )
        }

        item(key = "single") {
            SectionCard(title = "手动卸载", subtitle = "输入包名，自动查找并卸载") {
                TextInput(
                    value = pkg,
                    onValueChange = { pkg = it },
                    label = "应用包名",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 3.dp),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    ActionButton(
                        text = if (busy) "执行中…" else "卸载",
                        onClick = {
                            if (pkg.isBlank()) {
                                output = "请先填写应用包名"
                                return@ActionButton
                            }
                            // 不可逆操作，先弹确认框（带缩放 + 上浮入场动画）
                            pendingUninstall = pkg.trim()
                        },
                    )
                    ActionButton(
                        text = "清数据",
                        onClick = {
                            if (pkg.isBlank()) {
                                output = "请先填写应用包名"
                                return@ActionButton
                            }
                            busy = true
                            scope.launch {
                                val r = runCatching { installer.clearData(pkg.trim()) }
                                    .getOrElse {
                                        Installer.Outcome(false, "全部", "清数据出错：${it.message}")
                                    }
                                output = "${r.via}：${r.message}"
                                busy = false
                            }
                        },
                    )
                    ActionButton(
                        text = "冻结",
                        onClick = {
                            if (pkg.isBlank()) {
                                output = "请先填写应用包名"
                                return@ActionButton
                            }
                            busy = true
                            scope.launch {
                                val r = runCatching { installer.freeze(pkg.trim(), frozen = true) }
                                    .getOrElse {
                                        Installer.Outcome(false, "全部", "冻结出错：${it.message}")
                                    }
                                output = "${r.via}：${r.message}"
                                busy = false
                            }
                        },
                    )
                }
                if (output != null) {
                    Spacer(Modifier.height(3.dp))
                    SelectionContainer {
                        Text(
                            text = output.orEmpty(),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.primary,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = Metrics.inset, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        item(key = "padms") {
            SectionCard(
                title = "系统管控",
                subtitle = "停用 com.zuoyebang.padms 后，系统不再删除第三方应用",
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    ActionButton(
                        text = if (busy) "执行中…" else "停用管控",
                        onClick = {
                            busy = true
                            scope.launch {
                                val r = channels.exec(
                                    "pm disable-user --user 0 com.zuoyebang.padms && " +
                                        "am force-stop com.zuoyebang.padms",
                                )
                                output = if (r.ok) {
                                    "已停用管控服务（${r.via}），本次开机期间系统不再删除第三方应用"
                                } else {
                                    "停用失败：${r.error ?: r.output.take(120)}"
                                }
                                busy = false
                            }
                        },
                    )
                    ActionButton(
                        text = "恢复管控",
                        onClick = {
                            busy = true
                            scope.launch {
                                val r = channels.exec("pm enable com.zuoyebang.padms")
                                output = if (r.ok) "已恢复系统管控（${r.via}）"
                                else "恢复失败：${r.error ?: r.output.take(120)}"
                                busy = false
                            }
                        },
                    )
                }
                BulletRow("停用只在本次开机有效：系统每次开机都会重新扫描并删除黑名单应用。")
                BulletRow("重启后保护即失效；要持久保护请用「防卸载」的设备所有者方案。")
            }
        }

        item(key = "note") {
            SectionCard(title = "重要提醒") {
                BulletRow("卸载前请确认目标不是系统关键组件，误删可能导致设备异常。")
                BulletRow("系统应用的卸载通常会被拒绝，此时可以用「冻结」达到同样效果。")
            }
        }
        item(key = "sp") { Spacer(Modifier.height(16.dp)) }
    }

        // 反馈消息：从底部滑入淡出
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            ToastMessage(text = output)
        }

        // 卸载确认弹窗
        val target = pendingUninstall
        if (target != null) {
            ConfirmDialog(
                title = "卸载 $target？",
                message = "卸载后应用数据会被清除，此操作不可撤销。",
                confirmText = "卸载",
                danger = true,
                onConfirm = {
                    pendingUninstall = null
                    busy = true
                    scope.launch {
                        val r = runCatching { installer.uninstall(target, keepData = false) }
                            .getOrElse {
                                Installer.Outcome(false, "全部", "卸载出错：${it.message}")
                            }
                        output = "${r.via}：${r.message}"
                        busy = false
                    }
                },
                onDismiss = { pendingUninstall = null },
            )
        }
    }
}
