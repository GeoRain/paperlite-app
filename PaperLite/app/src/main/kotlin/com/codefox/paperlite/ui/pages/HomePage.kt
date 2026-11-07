package com.codefox.paperlite.ui.pages

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import com.codefox.paperlite.ui.theme.Motion
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codefox.paperlite.core.Channel
import com.codefox.paperlite.core.ChannelManager
import com.codefox.paperlite.core.RootManager
import com.codefox.paperlite.ui.Destination
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.BulletRow
import com.codefox.paperlite.ui.components.Chip
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.components.StatusHero
import com.codefox.paperlite.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 概览页。
 *
 * 顶部是 SukiSU 形态的开屏大卡片：一眼看出权限跑起来没有、以哪种方式跑的。
 * 通道细节收在「超级用户」页，这里只留结论与入口。
 */
@Composable
fun HomePage(
    channels: ChannelManager,
    onOpen: (Destination) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val states by channels.states.collectAsStateWithLifecycle()
    val busy by channels.busy.collectAsStateWithLifecycle()

    // root 状态：概览卡片要显示「以哪种方式运行」，所以这里要的是
    // root / shell / 设备所有者三种来源里最高的那一档
    var rootStatus by remember { mutableStateOf<RootManager.Status?>(null) }

    LaunchedEffect(Unit) {
        rootStatus = RootManager.collect(channels)
    }

    val identity = remember(states, rootStatus) {
        when {
            rootStatus?.available == true ->
                Identity.Running("Root 模式", rootStatus!!.detail)

            states[Channel.Wireless]?.available == true ->
                Identity.Running("ADB shell 模式", "无线调试会话已建立")

            states[Channel.LocalLoop]?.available == true ->
                Identity.Running("ADB shell 模式", "本地回环通道可用")

            states[Channel.DeviceOwner]?.available == true ->
                Identity.Running("设备所有者模式", "已获得 DevicePolicyManager 权限")

            else -> Identity.NotRunning(
                hint = when {
                    busy != null -> busy!!
                    else -> "尚未取得高权限身份"
                },
            )
        }
    }

    val shellReady = states[Channel.Root]?.available == true ||
        states[Channel.Wireless]?.available == true ||
        states[Channel.LocalLoop]?.available == true

    val c = LocalStatusColors.current
    val chips = remember(states) {
        buildList {
            states[Channel.Root]?.available?.takeIf { it }?.let {
                add(Chip("Root", c.ok, c.onOk))
            }
            states[Channel.Wireless]?.available?.takeIf { it }?.let {
                add(Chip("无线调试", c.info, c.onInfo))
            }
            states[Channel.LocalLoop]?.available?.takeIf { it }?.let {
                add(Chip("本地回环", c.neutral, c.onNeutral))
            }
            states[Channel.DeviceOwner]?.available?.takeIf { it }?.let {
                add(Chip("设备所有者", c.neutral, c.onNeutral))
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "header") {
            PageHeader(
                title = "概览",
                kicker = when {
                    busy != null -> busy
                    identity is Identity.Running -> "权限已就绪 · ${(identity as Identity.Running).mode}"
                    else -> "权限未就绪"
                },
                trailing = {
                    ActionButton(
                        text = "刷新",
                        onClick = {
                            scope.launch {
                                rootStatus = RootManager.collect(channels)
                                channels.probeAll()
                            }
                        },
                    )
                },
            )
        }

        item(key = "splash") {
            StatusHero(
                title = when {
                    identity is Identity.Running -> "工作中"
                    shellReady -> "未运行"
                    else -> "未安装"
                },
                subtitle = when (identity) {
                    is Identity.Running -> identity.mode
                    is Identity.NotRunning -> "还没有拿到高权限身份"
                },
                hint = when (identity) {
                    is Identity.Running -> identity.detail
                    is Identity.NotRunning -> identity.hint
                },
                chips = chips,
                rows = emptyList(),
                showFullStatus = false,
                onToggleFullStatus = null,
                accent = if (identity is Identity.Running) c.ok else c.neutral,
                modifier = Modifier.padding(horizontal = Metrics.pad, vertical = 4.dp),
            )
        }

        item(key = "quick") {
            SectionCard(title = "入口") {
                QuickEntry(
                    title = "应用",
                    subtitle = "应用管理 · 安装 · 卸载",
                    onClick = { onOpen(Destination.Apps) },
                )
                QuickEntry(
                    title = "超级用户",
                    subtitle = "root 管理器 · 授权名单 · 设备所有者",
                    onClick = { onOpen(Destination.Superuser) },
                )
                QuickEntry(
                    title = "Shizuku",
                    subtitle = "服务检测 · 官方启动 · 身份执行",
                    onClick = { onOpen(Destination.Shizuku) },
                )
                QuickEntry(
                    title = "命令行",
                    subtitle = "执行任意 shell 命令",
                    onClick = { onOpen(Destination.Terminal) },
                )
                QuickEntry(
                    title = "设置",
                    subtitle = "外观 · 授权器 · 日志 · 系统信息",
                    onClick = { onOpen(Destination.Settings) },
                )
            }
        }

        item(key = "jccode") {
            SectionCard(title = "换个用法") {
                JCCodeEntry(
                    text = "如果用不习惯，请试试JCCode！",
                    onClick = {
                        runCatching {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://app.aibangos.xyz"),
                            )
                            context.startActivity(intent)
                        }.getOrElse { onOpen(Destination.Settings) }
                    },
                )
            }
        }

        item(key = "note") {
            SectionCard(title = "说明") {
                BulletRow("大卡片只给结论：权限跑起来没有、以什么方式跑的。")
                BulletRow("各条通道的详细状态在「超级用户」页里看。")
            }
        }
    }
}

/** 一行可点的引导：文案用主色强调，右侧带箭头。 */
@Composable
private fun JCCodeEntry(
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Metrics.inset, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "↗",
            style = MiuixTheme.textStyles.title4,
            color = MiuixTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

/** 概览卡片要表达的三种状态。 */
private sealed interface Identity {
    /** 已运行：带运行方式标注。 */
    data class Running(val mode: String, val detail: String) : Identity

    /** 未运行：带下一步提示。 */
    data class NotRunning(val hint: String) : Identity
}

/** 一行可点的入口。 */
@Composable
private fun QuickEntry(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    // 按下时整行轻微右移 + 淡出背景，给出「点到了」的即时反馈
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val offset by animateDpAsState(
        targetValue = if (pressed) 4.dp else 0.dp,
        animationSpec = tween(Motion.QUICK, easing = Motion.enterEasing),
        label = "entry-press",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset(x = offset)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = Metrics.inset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "›",
            style = MiuixTheme.textStyles.title4,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            maxLines = 1,
        )
    }
}
