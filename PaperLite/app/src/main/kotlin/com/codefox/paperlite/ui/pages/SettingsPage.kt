package com.codefox.paperlite.ui.pages

import com.codefox.paperlite.ui.components.PageTabs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.codefox.paperlite.data.InstallAuthorizer
import com.codefox.paperlite.data.Settings
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.BulletRow
import com.codefox.paperlite.ui.components.InfoRow
import com.codefox.paperlite.ui.components.HamburgerMenu
import com.codefox.paperlite.ui.components.MenuEntry
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.components.SegmentedTabs
import com.codefox.paperlite.ui.components.TextInput
import com.codefox.paperlite.ui.components.ToggleRow
import com.codefox.paperlite.ui.components.ToastMessage
import com.codefox.paperlite.ui.theme.ColorSource
import com.codefox.paperlite.ui.theme.DesignStyle
import com.codefox.paperlite.ui.theme.WallpaperStore
import com.codefox.paperlite.ui.theme.Motion
import com.codefox.paperlite.ui.theme.ThemeMode
import com.codefox.paperlite.ui.theme.displayName
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 设置页的四个视图。 */
/**
 * 设置页的高频分组（页内 Tab 栏）。
 *
 * 「常规 / 安装 / 提权 / 关于」都在 Tab 里：**关于页必须是能一眼找到的**，
 * 之前把它只挂在汉堡菜单里，结果没人发现它存在——一个藏着等于没有的页面。
 * 剩下的「系统 / 公告」是低频的看一眼就走的内容，仍收在汉堡菜单里。
 */
private val SETTINGS_TABS = listOf("常规", "安装", "提权", "关于")

/**
 * 设置页。
 *
 * 侧边栏只留五个入口之后，「设备」「公告」「关于」不再是独立页面，
 * 而是收进这里的分段标签——它们本质上都是「关于这台设备的配置与信息」。
 *
 * ## 性能注意
 *
 * 日志区原来订阅整份日志列表（最多 600 条），每写一行日志整页都要重组一次；
 * 安装过程中日志是刷屏式写入的，那就是肉眼可见的卡顿。
 * 现在标题只用 [Settings.logCount]（一个 Int），列表只在展开时才订阅。
 */
@Composable
fun SettingsPage(
    settings: Settings,
    darkTheme: Boolean,
) {
    var tab by rememberSaveable { mutableStateOf(SETTINGS_TABS[0]) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏：当前分组 + 汉堡菜单（分组都在菜单里）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Metrics.pad + 4.dp, end = Metrics.pad + 4.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "设置",
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = tab,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
            }
            HamburgerMenu(
                items = listOf(
                    MenuEntry(title = "系统", summary = "设备信息与通道参数") { tab = "系统" },
                    MenuEntry(title = "公告", summary = "版本公告与更新说明") { tab = "公告" },
                ),
            )
        }
        // 页内 Tab 栏：只在本页显示，且在 AnimatedContent 之外
        // （放里面会跟着内容一起淡入淡出，Tab 本身就该是稳的）
        //
        // 「系统 / 公告 / 关于」是从汉堡菜单进的独立分组，不属于 Tab 组，
        // 这时候隐藏 Tab 栏，不让它露出一个没有选中项的样子。
        if (tab in SETTINGS_TABS) {
            PageTabs(
                options = SETTINGS_TABS,
                selected = tab,
                label = { it },
                onSelect = { tab = it },
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 分组切换：淡入 + 轻微上移
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (fadeIn(tween(Motion.LONG, easing = Motion.emphasized)) +
                        slideInVertically(tween(Motion.LONG, easing = Motion.emphasized)) {
                            Motion.switchOffset
                        })
                        .togetherWith(fadeOut(tween(Motion.QUICK, easing = Motion.exitEasing)))
                },
            label = "settings-tab",
            ) { t ->
                when (t) {
                    "安装" -> InstallSettingsSection(settings = settings)

                    "提权" -> RootSettingsSection(settings = settings)

                    "系统" -> DevicePage(showHeader = false)

                    "公告" -> NoticePage(settings = settings, showHeader = false)

                    "关于" -> AboutPage(showHeader = false)

                    else -> GeneralSettingsSection(settings = settings, darkTheme = darkTheme)
                }
            }
        }
    }
}

@Composable
private fun GeneralSettingsSection(
    settings: Settings,
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean,
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loopPort by remember { mutableStateOf(settings.localLoopPort.value.toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }

    val hideUnavailable by settings.hideUnavailable.collectAsStateWithLifecycle()
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val fontScale by settings.fontScale.collectAsStateWithLifecycle()
    val designStyle by settings.designStyle.collectAsStateWithLifecycle()
    val colorSource by settings.colorSource.collectAsStateWithLifecycle()
    val wallpaperEnabled by settings.wallpaperEnabled.collectAsStateWithLifecycle()
    val wallpaperPath by settings.wallpaperPath.collectAsStateWithLifecycle()
    val logCount by settings.logCount.collectAsStateWithLifecycle()
    // 端口在配对成功后才会写入，必须订阅，否则页面一直显示旧值
    val pairPort by settings.pairPort.collectAsStateWithLifecycle()
    val connectPort by settings.connectPort.collectAsStateWithLifecycle()

    // 选图：拿到的是临时 Uri，必须立刻复制进私有目录再持久化路径
    val pickWallpaper = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = WallpaperStore.saveFromUri(context, uri)
            if (saved == null) {
                message = "这张图读不出来，换一张试试"
            } else {
                settings.setWallpaperPath(saved)
                settings.setWallpaperEnabled(true)
                message = "背景已更换，配色会跟着重新取色"
            }
        }
    }

    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            item(key = "appearance") {
                SectionCard(title = "外观", subtitle = "界面风格只影响观感，功能完全一致") {
                    // 风格切换：两种设计语言即时生效，无需重启
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.inset, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DesignStyle.entries.forEach { style ->
                            val active = style == designStyle
                            val bg by animateColorAsState(
                                targetValue = if (active) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.surfaceContainerHighest
                                },
                                animationSpec = tween(Motion.MEDIUM),
                                label = "style-bg",
                            )
                            val fg by animateColorAsState(
                                targetValue = if (active) {
                                    MiuixTheme.colorScheme.onPrimary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceContainerVariant
                                },
                                animationSpec = tween(Motion.MEDIUM),
                                label = "style-fg",
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(bg)
                                    .clickable { settings.setDesignStyle(style) }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = style.title,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = fg,
                                    maxLines = 1,
                                )
                                Text(
                                    text = style.desc,
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = fg.copy(alpha = 0.75f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
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

            item(key = "wallpaper") {
                SectionCard(title = "壁纸与取色", subtitle = "应用内背景，不影响系统桌面") {
                    // 配色来源：小屏上三项刚好放得下
                    SegmentedTabs(
                        options = ColorSource.available(),
                        selected = colorSource,
                        label = { it.title },
                        onSelect = { settings.setColorSource(it) },
                        modifier = Modifier.padding(horizontal = 0.dp, vertical = 2.dp),
                    )
                    InfoRow("配色说明", colorSource.desc)
                    ToggleRow(
                        title = "显示壁纸背景",
                        subtitle = "关掉就是原来的纯白 / 纯黑底",
                        checked = wallpaperEnabled,
                        onCheckedChange = { settings.setWallpaperEnabled(it) },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.inset, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ActionButton(
                            text = "选择图片",
                            onClick = { pickWallpaper.launch("image/*") },
                        )
                        ActionButton(
                            text = "恢复默认",
                            onClick = {
                                settings.setWallpaperPath(null)
                                message = "已恢复随包内置的默认壁纸"
                            },
                        )
                    }
                    InfoRow("当前壁纸", if (wallpaperPath == null) "内置默认图" else "自定义图片")
                    BulletRow("「壁纸取色」会从这张图里提主色，整套按钮与卡片配色跟着变。")
                    BulletRow("「系统动态色」需要 Android 12 以上，取的是系统桌面壁纸的颜色。")
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
                    BulletRow("配对与重新探测在「超级用户」页。")
                }
            }

            item(key = "authorizer") {
                SectionCard(
                    title = "安装授权器",
                    subtitle = "对齐 InstallerX 的 Authorizer：决定安装以谁的身份执行",
                ) {
                    val authorizer by settings.installAuthorizer.collectAsStateWithLifecycle()
                    SegmentedTabs(
                        options = InstallAuthorizer.entries,
                        selected = authorizer,
                        label = { it.title },
                        onSelect = {
                            settings.setInstallAuthorizer(it)
                            message = "安装授权器已设为「${it.title}」"
                        },
                        modifier = Modifier.padding(horizontal = 0.dp, vertical = 2.dp),
                    )
                    InfoRow("当前授权器", authorizer.title)
                    InfoRow("说明", authorizer.desc)
                    BulletRow("「作业帮系统服务」对应 InstallerX 的 Birchua 授权器：直接调系统服务静默安装。")
                    BulletRow("选「系统安装器」时不做任何静默尝试，直接拉起系统安装界面。")
                }
            }

            item(key = "log") {
                SectionCard(title = "运行日志", subtitle = "共 $logCount 条") {
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
                        // 只有展开时才订阅日志列表：收起状态下日志刷新不会触发这里重组
                        val logs by settings.logLines.collectAsStateWithLifecycle()
                        SelectionContainer {
                            Column(modifier = Modifier.padding(horizontal = Metrics.inset, vertical = 2.dp)) {
                                logs.takeLast(80).forEach { line ->
                                    Text(
                                        text = line,
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                        maxLines = 2,
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
                                if (!expanded) {
                                    crash = com.codefox.paperlite.core.CrashGuard.readLast(context)
                                }
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

            item(key = "env") {
                SectionCard(title = "环境") {
                    InfoRow("本机设备码", DeviceInfo.deviceCode(context))
                    InfoRow("应用版本", com.codefox.paperlite.BuildConfig.VERSION_NAME)
                    InfoRow("包名", context.packageName)
                }
            }

            item(key = "sp") { Spacer(Modifier.height(8.dp)) }
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
