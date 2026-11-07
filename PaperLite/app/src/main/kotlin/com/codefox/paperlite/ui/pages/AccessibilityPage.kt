package com.codefox.paperlite.ui.pages

import android.content.Context
import android.provider.Settings
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codefox.paperlite.core.AutoRoot
import com.codefox.paperlite.core.Channel
import com.codefox.paperlite.core.ChannelManager
import com.codefox.paperlite.core.DirtyFrag
import com.codefox.paperlite.core.RootManager
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.BulletRow
import com.codefox.paperlite.ui.components.Chip
import com.codefox.paperlite.ui.components.HamburgerMenu
import com.codefox.paperlite.ui.components.InfoRow
import com.codefox.paperlite.ui.components.MenuEntry
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.components.StatusHero
import com.codefox.paperlite.ui.components.TextInput
import com.codefox.paperlite.ui.components.ToastMessage
import com.codefox.paperlite.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 无障碍页（抄「无障碍管理器」）。
 *
 * 那个应用的真身是 **Xposed 模块 + 无障碍服务修复工具**：
 * - 无障碍服务被系统禁用时，用 Xposed 钩住 `AccessibilityServiceConnection` 把它救回来；
 * - 它的无障碍服务声明是「接收开屏和解锁事件，从而在设备解锁时触发崩溃检测」——
 *   也就是**设备一解锁就自动跑提权流程**（内核漏洞利用要求设备处于已解锁状态）。
 *
 * 这里抄的是它的行为模型，做成 PaperLite 自己的东西：
 * - **解锁即提权**：无障碍服务收到解锁事件 → 自动执行 DirtyFrag 提权；
 * - **开机即提权**：开机广播 → 同样流程；
 * - **服务自愈**：检测无障碍服务是否被系统禁用，必要时引导重新启用；
 * - **保活**：常驻前台服务，避免被系统杀掉。
 */
@Composable
fun AccessibilityPage(channels: ChannelManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val states by channels.states.collectAsStateWithLifecycle()

    var status by remember { mutableStateOf(RootManager.Status()) }
    var serviceOn by remember { mutableStateOf(false) }
    var autoRoot by remember { mutableStateOf(false) }
    var bootRoot by remember { mutableStateOf(false) }
    var keepAlive by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var section by remember { mutableStateOf("state") }

    LaunchedEffect(Unit) {
        status = RootManager.collect(channels)
        serviceOn = AutoRoot.isAccessibilityEnabled(context)
        autoRoot = AutoRoot.autoRootOnUnlock(context)
        bootRoot = AutoRoot.autoRootOnBoot(context)
        keepAlive = AutoRoot.keepAliveEnabled(context)
    }

    val c = LocalStatusColors.current
    val heroTitle = when {
        status.available -> "工作中"
        serviceOn -> "未运行"
        else -> "未启用"
    }
    val heroSubtitle = when {
        status.available -> "版本：${status.framework ?: "Root"}" + (status.version?.let { " $it" } ?: "")
        serviceOn -> "无障碍服务已开启，等待解锁触发提权"
        else -> "还没有开启无障碍服务"
    }
    val heroHint = when {
        status.available -> "内核已接管"
        serviceOn && autoRoot -> "解锁一次就会自动尝试提权"
        else -> "开启无障碍服务后，解锁时自动提权"
    }
    val chips = buildList {
        add(
            Chip(
                if (serviceOn) "无障碍已开" else "无障碍未开",
                if (serviceOn) c.ok else c.neutral,
                if (serviceOn) c.onOk else c.onNeutral,
            ),
        )
        if (autoRoot) add(Chip("解锁即提权", c.info, c.onInfo))
        if (bootRoot) add(Chip("开机即提权", c.info, c.onInfo))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "header") {
                PageHeader(
                    title = "无障碍",
                    kicker = if (busy) "处理中…" else heroTitle,
                    trailing = {
                        HamburgerMenu(
                            items = listOf(
                                MenuEntry("状态", "无障碍与提权状态") { section = "state" },
                                MenuEntry("自动提权", "解锁 / 开机自动获取 root") { section = "auto" },
                                MenuEntry("立即提权", "手动跑一次漏洞利用") { section = "run" },
                                MenuEntry("保活", "避免服务被系统杀掉") { section = "keep" },
                            ),
                        )
                    },
                )
            }

            item(key = "hero") {
                StatusHero(
                    title = heroTitle,
                    subtitle = heroSubtitle,
                    hint = heroHint,
                    chips = chips,
                    rows = listOf(
                        "无障碍服务" to if (serviceOn) "已开启" else "未开启",
                        "当前身份" to status.uidLine.ifBlank { "未取得 root" },
                        "内核版本" to status.kernelVersion,
                        "SELinux" to status.selinux,
                        "设备型号" to status.model,
                    ),
                    showFullStatus = false,
                    onToggleFullStatus = null,
                    accent = if (status.available) c.ok else c.neutral,
                    modifier = Modifier.padding(horizontal = Metrics.pad, vertical = 4.dp),
                )
            }

            if (section == "state") {
                item(key = "state") {
                    SectionCard(title = "无障碍服务", subtitle = "自动提权靠它监听解锁") {
                        InfoRow("PaperLite 服务", if (serviceOn) "已开启" else "未开启")
                        InfoRow(
                            "系统设置里的状态",
                            AutoRoot.enabledServices(context) ?: "读取不到",
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Metrics.inset, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ActionButton(
                                text = "去开启",
                                onClick = {
                                    val ok = runCatching {
                                        context.startActivity(
                                            android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
                                                .let { android.content.Intent(it) }
                                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                        true
                                    }.getOrDefault(false)
                                    if (!ok) message = "无法打开无障碍设置页"
                                },
                            )
                            ActionButton(
                                text = "重新检测",
                                onClick = {
                                    scope.launch {
                                        status = RootManager.collect(channels)
                                        serviceOn = AutoRoot.isAccessibilityEnabled(context)
                                        message = if (serviceOn) "无障碍服务已开启" else "无障碍服务还没开启"
                                    }
                                },
                            )
                        }
                        BulletRow("没有无障碍服务就收不到解锁事件，自动提权也就无从触发。")
                    }
                }
            }

            if (section == "auto") {
                item(key = "auto") {
                    SectionCard(title = "自动提权", subtitle = "解锁即提权 / 开机即提权") {
                        com.codefox.paperlite.ui.components.ToggleRow(
                            title = "解锁后自动获取 root",
                            subtitle = "设备一解锁就自动跑一次漏洞利用",
                            checked = autoRoot,
                            onCheckedChange = {
                                autoRoot = it
                                AutoRoot.setAutoRootOnUnlock(context, it)
                                message = if (it) "已开启解锁即提权" else "已关闭解锁即提权"
                            },
                        )
                        com.codefox.paperlite.ui.components.ToggleRow(
                            title = "开机后自动获取 root",
                            subtitle = "重启后开机自启时自动提权",
                            checked = bootRoot,
                            onCheckedChange = {
                                bootRoot = it
                                AutoRoot.setAutoRootOnBoot(context, it)
                                message = if (it) "已开启开机即提权" else "已关闭开机即提权"
                            },
                        )
                        BulletRow("提权走的是 DirtyFrag 内核漏洞，临时 root，重启失效。")
                        BulletRow("su 守护进程（ksud）随包内置，不需要先装 SukiSU 等管理器。")
                        BulletRow("首次运行会弹出授权确认，同意后才会真的提权。")
                    }
                }
            }

            if (section == "run") {
                item(key = "run") {
                    SectionCard(title = "立即提权", subtitle = "手动跑一次漏洞利用") {
                        InfoRow("状态", if (status.available) "已经有 root 了" else "还没有 root")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Metrics.inset, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ActionButton(
                                text = if (busy) "提权中…" else "开始提权",
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val r = DirtyFrag.run(
                                            context = context,
                                            channels = channels,
                                        )
                                        busy = false
                                        status = RootManager.collect(channels)
                                        message = r.message
                                    }
                                },
                            )
                            ActionButton(
                                text = "重新检测",
                                onClick = {
                                    scope.launch {
                                        status = RootManager.collect(channels)
                                        message = status.detail
                                    }
                                },
                            )
                        }
                        BulletRow("提权成功后会自动把本应用提为设备所有者，静默安装能力随之生效。")
                    }
                }
            }

            if (section == "keep") {
                item(key = "keep") {
                    SectionCard(title = "保活", subtitle = "避免后台服务被系统回收") {
                        com.codefox.paperlite.ui.components.ToggleRow(
                            title = "无障碍服务保活",
                            subtitle = "用前台服务常驻，防止解锁事件收不到",
                            checked = keepAlive,
                            onCheckedChange = {
                                keepAlive = it
                                AutoRoot.setKeepAlive(context, it)
                                message = if (it) "已开启保活" else "已关闭保活"
                            },
                        )
                        BulletRow("部分国产 ROM 会在息屏后杀掉无障碍服务，保活能显著减少这种情况。")
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
}
