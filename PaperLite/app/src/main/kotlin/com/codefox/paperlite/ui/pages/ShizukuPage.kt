package com.codefox.paperlite.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codefox.paperlite.core.BundledBinaries
import com.codefox.paperlite.core.Channel
import com.codefox.paperlite.core.ChannelManager
import com.codefox.paperlite.core.Installer
import com.codefox.paperlite.core.RootManager
import com.codefox.paperlite.core.ShizukuManager
import com.codefox.paperlite.ui.components.AnimatedGroup
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.BulletRow
import com.codefox.paperlite.ui.components.Chip
import com.codefox.paperlite.ui.components.InfoRow
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.components.StatusHero
import com.codefox.paperlite.ui.components.ToastMessage
import com.codefox.paperlite.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

/**
 * Shizuku 页：只管"把官方服务跑起来"。
 *
 * 旧版的「身份执行」「授权名单」是 PaperLite 自己重做的一套 shell 通道，
 * 和命令行页、超级用户页重复，已删除。本页只保留检测 + 启动。
 *
 * Shizuku 服务端类只在官方 APK 的 classes.dex 里，所以没装时 PaperLite
 * 会用随包内置的官方 APK 自行静默安装；启动器 ELF 也由自己投放，
 * 不再要求用户先打开一次 Shizuku 去生成 start.sh。
 */
@Composable
fun ShizukuPage(channels: ChannelManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val installer = remember { Installer(context, channels) }

    val states by channels.states.collectAsStateWithLifecycle()

    var install by remember { mutableStateOf(ShizukuManager.installInfo(context)) }
    var running by remember { mutableStateOf(ShizukuManager.RunState()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showFull by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(RootManager.Status()) }

    suspend fun refresh() {
        install = ShizukuManager.installInfo(context)
        running = ShizukuManager.runState(channels)
        status = RootManager.collect(channels)
    }

    LaunchedEffect(Unit) { refresh() }

    val identity = when {
        states[Channel.Root]?.available == true -> "Root 模式"
        states[Channel.Wireless]?.available == true -> "ADB 模式"
        states[Channel.LocalLoop]?.available == true -> "ADB 模式"
        else -> "未取得身份"
    }
    val shellReady = identity != "未取得身份"

    val c = LocalStatusColors.current
    val heroTitle = when {
        running.running -> "工作中"
        install.installed -> "未运行"
        else -> "未安装"
    }
    val heroSubtitle = when {
        running.running -> "版本：${install.versionName.ifBlank { "Shizuku" }}"
        install.installed -> "已安装 ${install.versionName}，服务没有运行"
        else -> "尚未安装，启动时会用随包内置的安装包自动装好"
    }
    val heroHint = when {
        running.running -> "$identity · ${running.detail}"
        !shellReady -> "先在「超级用户」页拿到 adb shell 身份，才能投放启动器"
        else -> "已内置官方 APK 与启动器，点「启动服务」即可"
    }
    val chips = buildList {
        add(Chip(identity, if (shellReady) c.ok else c.neutral, if (shellReady) c.onOk else c.onNeutral))
        if (install.installed) add(Chip("Shizuku ${install.versionName}", c.info, c.onInfo))
    }
    val heroRows = listOf(
        "Shizuku 包名" to ShizukuManager.PACKAGE,
        "Shizuku 版本" to install.versionName.ifBlank { "未安装" },
        "运行方式" to identity,
        "服务状态" to running.detail,
        "本机架构" to BundledBinaries.abi(context),
        "内核版本" to status.kernelVersion,
        "SELinux" to status.selinux,
        "设备型号" to status.model,
        "Build 指纹" to status.fingerprint,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "header") {
                PageHeader(
                    title = "Shizuku",
                    kicker = if (busy) "处理中…" else heroTitle,
                )
            }

            item(key = "hero") {
                StatusHero(
                    title = heroTitle,
                    subtitle = heroSubtitle,
                    hint = heroHint,
                    chips = chips,
                    rows = heroRows,
                    showFullStatus = showFull,
                    onToggleFullStatus = { showFull = it },
                    accent = if (running.running) c.ok else c.neutral,
                    modifier = Modifier.padding(horizontal = Metrics.pad, vertical = 4.dp),
                )
            }

            item(key = "actions") {
                AnimatedGroup {
                    SectionCard(title = "服务", subtitle = "安装与启动都在应用内完成") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Metrics.inset, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ActionButton(
                                text = if (busy) "启动中…" else "启动服务",
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val r = ShizukuManager.start(context, channels, installer)
                                        message = r.message
                                        busy = false
                                        refresh()
                                    }
                                },
                            )
                            ActionButton(
                                text = "重新检测",
                                onClick = { scope.launch { refresh() } },
                            )
                            ActionButton(
                                text = "投放 rish",
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val r = ShizukuManager.stageRish(context, channels)
                                        message = r.message
                                        busy = false
                                    }
                                },
                            )
                        }
                        InfoRow("内置安装包", if (BundledBinaries.hasShizukuApk(context)) "已就绪" else "缺失")
                        InfoRow("内置启动器", if (BundledBinaries.hasStarter(context)) "已就绪" else "缺失")
                        BulletRow("服务端类只在官方 APK 里，未安装时 PaperLite 会自行静默安装。")
                        BulletRow("启动器 libshizuku.so 按本机架构投放，不再依赖官方生成的 start.sh。")
                        BulletRow("rish 是官方终端接入件，投放到 /data/local/tmp/paperlite_shizuku/rish。")
                        BulletRow("执行命令请用「命令行」页，授权请到 Shizuku 应用里设置。")
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
