package com.codefox.paperlite.ui.pages

import com.codefox.paperlite.ui.components.PageTabs
import android.app.Activity
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.codefox.paperlite.core.AutoActivate
import com.codefox.paperlite.core.BundledBinaries
import com.codefox.paperlite.core.Channel
import com.codefox.paperlite.core.ChannelManager
import com.codefox.paperlite.core.DeviceOwner
import com.codefox.paperlite.core.DirtyFrag
import com.codefox.paperlite.core.RootManager
import com.codefox.paperlite.core.ZybosApi
import com.codefox.paperlite.core.channel.PairingCaptureService
import com.codefox.paperlite.data.Settings
import com.codefox.paperlite.ui.components.AnimatedGroup
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.BulletRow
import com.codefox.paperlite.ui.components.Chip
import com.codefox.paperlite.ui.components.ConsentDialog
import com.codefox.paperlite.ui.components.InfoRow
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.Pill
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.components.StatusHero
import com.codefox.paperlite.ui.components.TextInput
import com.codefox.paperlite.ui.components.ToastMessage
import com.codefox.paperlite.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
* 超级用户页。
*
* 顶部信息卡照搬 **SukiSU** 首页那张：大标题（工作中 / 未运行）→ 版本副标题 →
* 状态芯片 → 完整状态信息列表，文案也沿用 SukiSU 的说法。
*
* 下面的操作全部在应用内完成：无线调试配对、DirtyFrag 一键 root
* （ksud 随包内置，不用先装 SukiSU）、设备所有者、模块列表。
*/
/**
 * 超级用户页的高频分组（页内 Tab 栏）：内部标识 + 显示名。
 *
 * 键是内部标识（与 `section` 状态一致），值是显示名。
 * 旧版这里还有一个「超级用户」分组，是 PaperLite 自己重做的一套
 * root 管理器（su 代理 + 授权名单），与 SukiSU/KernelSU 官方实现重复，已删除；
 * 「设备所有者」和「模块」从汉堡菜单提上来，避免「Tab + 汉堡两套导航」
 * 指向同样的内容，也顺手去掉了一个没有内容块的死入口「设备信息」。
 */
private val SUPERUSER_TABS: List<Pair<String, String>> = listOf(
    "grant" to "获取权限",
    "modules" to "模块",
    "owner" to "设备所有者",
)

@Composable
fun SuperuserPage(
    channels: ChannelManager,
    settings: Settings,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val states by channels.states.collectAsStateWithLifecycle()
    val busy by channels.busy.collectAsStateWithLifecycle()
    val pairPort by settings.pairPort.collectAsStateWithLifecycle()
    val connectPort by settings.connectPort.collectAsStateWithLifecycle()

    var pairCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var ownerSelf by remember { mutableStateOf(DeviceOwner.isSelf(context)) }
    var ownerActive by remember { mutableStateOf(DeviceOwner.isAdminActive(context)) }

    var status by remember { mutableStateOf(RootManager.Status()) }
    var showFull by remember { mutableStateOf(false) }
    var modules by remember { mutableStateOf<List<RootManager.Module>>(emptyList()) }
    var allowlist by remember { mutableStateOf<String?>(null) }

    var dirtyState by remember { mutableStateOf("") }
    var dirtyBusy by remember { mutableStateOf(false) }
    var dirtyLog by remember { mutableStateOf<List<String>>(emptyList()) }

    var zybosState by remember { mutableStateOf("") }
    var activateMessage by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("grant") }
    var autoActivated by remember { mutableStateOf(false) }

    // 风险知情确认：**每次进入本页都弹一次**，不落盘、不记忆。
    // 离开本页（切到别的目的地）时 composition 被销毁，返回即重新弹。
    var riskAccepted by remember { mutableStateOf(false) }

    val captureResult by PairingCaptureService.result.collectAsStateWithLifecycle()

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            PairingCaptureService.start(context, result.resultCode, result.data!!)
            message = "正在识别配对码，请停留在配对页面"
        } else {
            message = "用户拒绝屏幕读取授权"
        }
    }

    suspend fun refresh() {
        status = RootManager.collect(channels)
        modules = RootManager.listModules(channels)
        allowlist = RootManager.allowlistSummary(channels)
        dirtyState = DirtyFrag.supportInfo(context)
        if (ZybosApi.isAvailable(context)) {
            zybosState = ZybosApi.installDisallowState(context).message
        }
        // 已经拿到 root 就自动把本应用提为设备所有者（只做一次）
        if (status.available && !autoActivated) {
            autoActivated = true
            val r = AutoActivate.activate(context, channels)
            activateMessage = r.message
            ownerSelf = DeviceOwner.isSelf(context)
            ownerActive = DeviceOwner.isAdminActive(context)
            if (r.needsUserConfirm) {
                AutoActivate.launchAdmin(context)
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    LaunchedEffect(captureResult) {
        captureResult?.let {
            if (it.code != null) {
                pairCode = it.code
                message = "已识别配对码：${it.code}"
            } else if (it.error != null) {
                message = it.error
            }
            PairingCaptureService.consume()
        }
    }

    val shellReady = states[Channel.Root]?.available == true ||
    states[Channel.Wireless]?.available == true ||
    states[Channel.LocalLoop]?.available == true

    val c = LocalStatusColors.current
    val heroTitle = when {
        status.available -> "工作中"
        shellReady -> "未运行"
        else -> "未安装"
    }
    val heroSubtitle = when {
        status.available -> "版本：${status.framework ?: "Root"}" + (status.version?.let { " $it" } ?: "")
        shellReady -> "已取得 adb shell 身份，但还没有 root"
        else -> "内核上未检测到 root 驱动"
    }
    val heroHint = when {
        status.available -> "内核已接管，${allowlist ?: ""}"
        shellReady -> "已取得 adb shell 身份，可以直接尝试一键 Root"
        else -> "可以先用无线调试拿到 adb shell，再尝试一键 root"
    }
    val chips = buildList {
        status.framework?.let { add(Chip(it, c.info, c.onInfo)) }
        if (status.selinux != "未知") add(Chip("SELinux ${status.selinux}", c.neutral, c.onNeutral))
        if (modules.isNotEmpty()) add(Chip("模块 ${modules.size}", c.neutral, c.onNeutral))
    }
    val heroRows = listOf(
        "PaperLite 版本" to com.codefox.paperlite.BuildConfig.VERSION_NAME,
        "内核版本" to status.kernelVersion,
        "SELinux" to status.selinux,
        "Seccomp" to status.seccomp,
        "设备型号" to status.model,
        "Build 指纹" to status.fingerprint,
        "KMI" to status.kmi,
        "su 路径" to (status.suPath ?: "未找到"),
        "身份" to status.uidLine.ifBlank { "未取得 root" },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "header") {
                PageHeader(
                    title = "超级用户",
                    kicker = if (busy != null) busy else heroTitle,
                )
            }

            // 页内 Tab 栏：本页所有分组都在这里，不再另设汉堡菜单
            item(key = "tabs") {
                PageTabs<Pair<String, String>>(
                    options = SUPERUSER_TABS,
                    selected = SUPERUSER_TABS.firstOrNull { it.first == section }
                        ?: SUPERUSER_TABS.first(),
                    label = { it.second },
                    onSelect = { section = it.first },
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
                    accent = if (status.available) c.ok else c.neutral,
                    modifier = Modifier.padding(horizontal = Metrics.pad, vertical = 4.dp),
                )
            }

            if (section == "grant") item(key = "actions") {
                AnimatedGroup {
                    SectionCard(title = "获取权限", subtitle = "全部在应用内完成") {
                        Row(
                            modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.inset, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ActionButton(
                                text = if (dirtyBusy) "提权中…" else "一键 Root",
                                onClick = {
                                    dirtyBusy = true
                                    dirtyLog = emptyList()
                                    scope.launch {
                                        val r = DirtyFrag.run(
                                            context = context,
                                            channels = channels,
                                            onLog = { dirtyLog = dirtyLog + it },
                                        )
                                        dirtyBusy = false
                                        dirtyState = r.message
                                        message = r.message
                                        // 提权成功就把本应用提为设备所有者
                                        if (r.success) {
                                            val act = AutoActivate.activate(context, channels)
                                            message = "${r.message}；${act.message}"
                                            ownerSelf = DeviceOwner.isSelf(context)
                                            ownerActive = DeviceOwner.isAdminActive(context)
                                            if (act.needsUserConfirm) {
                                                AutoActivate.launchAdmin(context)
                                            }
                                        }
                                        refresh()
                                    }
                                },
                            )
                            ActionButton(
                                text = "重新检测",
                                onClick = { scope.launch { refresh() } },
                            )
                        }

                        if (dirtyState.isNotBlank()) InfoRow("状态", dirtyState)
                        InfoRow("随包 ksud", BundledBinaries.integrity(context).lineSequence().elementAtOrNull(1).orEmpty())
                        if (dirtyLog.isNotEmpty()) {
                            Card(
                                modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Metrics.inset, vertical = 6.dp),
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = dirtyLog.takeLast(30).joinToString("\n"),
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                        maxLines = 30,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                }
                            }
                        }
                        BulletRow("一键 Root 用的是 DirtyFrag 内核漏洞，临时 root，重启失效。")
                        BulletRow("su 守护进程（ksud）随包内置，不需要先安装 SukiSU 等管理器。")
                    }
                }
            }

            if (section == "grant") item(key = "pair") {
                AnimatedGroup {
                    SectionCard(
                        title = "无线调试配对",
                        subtitle = "三步，照着点就行",
                    ) {
                        // ① 无线调试是否已开启
                        Row(
                            modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.inset, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "① 开启无线调试",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                                )
                                Text(
                                    text = "系统设置 → 开发者选项 → 无线调试，打开后留在这一页",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            ActionButton(
                                text = "检查",
                                onClick = {
                                    scope.launch {
                                        val r = channels.execAsShell(
                                            "settings get global adb_enabled",
                                            timeoutMs = 15_000L,
                                        )
                                        val on = r.output.trim() == "1"
                                        message = if (on) "无线调试已开启" else "无线调试还没打开"
                                    }
                                },
                            )
                        }

                        // ② 配对
                        Row(
                            modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.inset, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "② 完成配对",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                                )
                                Text(
                                    text = "点「无线调试 → 使用配对码配对设备」，然后把 6 位码填到下面",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        TextInput(
                            value = pairCode,
                            onValueChange = { pairCode = it.filter { ch -> ch.isDigit() }.take(6) },
                            label = "6 位配对码",
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
                                text = if (busy != null) "处理中…" else "开始配对",
                                onClick = {
                                    scope.launch {
                                        val r = channels.pair(pairCode)
                                        message = r.message
                                    }
                                },
                            )
                            ActionButton(
                                text = "识别配对码",
                                onClick = {
                                    val mpm = context.getSystemService(
                                        android.content.Context.MEDIA_PROJECTION_SERVICE,
                                    ) as MediaProjectionManager
                                    runCatching { projectionLauncher.launch(mpm.createScreenCaptureIntent()) }
                                    .onFailure { message = "无法请求屏幕读取授权：${it.message}" }
                                },
                            )
                            ActionButton(
                                text = "搜索端口",
                                onClick = {
                                    scope.launch {
                                        val (p, cp) = channels.discoverPorts()
                                        message = "配对端口 $p，连接端口 $cp"
                                    }
                                },
                            )
                        }

                        // ③ 验证
                        Row(
                            modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.inset, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "③ 验证身份",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                                )
                                Text(
                                    text = "配对成功后回来点一下，确认拿到了什么权限",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            ActionButton(
                                text = "验证",
                                onClick = {
                                    scope.launch {
                                        val ok = channels.acquireShellIdentity()
                                        val r = channels.execAsShell("id", timeoutMs = 15_000L)
                                        message = if (ok) {
                                            "已取得身份：${r.output.trim().lines().firstOrNull()}"
                                        } else {
                                            "还没拿到身份，先完成前两步"
                                        }
                                        refresh()
                                    }
                                },
                            )
                        }
                        InfoRow("配对端口", pairPort.toString())
                        InfoRow("连接端口", connectPort.toString())
                        Row(
                            modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.inset, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ActionButton(
                                text = "清理配对",
                                onClick = {
                                    scope.launch {
                                        channels.identityStore().clear()
                                        channels.cleanupSystemPairings()
                                        message = "配对记录已清理，请重新配对"
                                    }
                                },
                            )
                        }
                        BulletRow("识别配对码需要一次性屏幕读取授权，识别完成即停止，不保存截图。")
                    }
                }
            }

            if (section == "owner") item(key = "owner") {
                AnimatedGroup {
                    SectionCard(title = "设备所有者", subtitle = "owner 权限可做静默安装、单独应用防卸载") {
                        InfoRow(
                            "当前状态",
                            when {
                                ownerSelf -> "本应用已是设备所有者"
                                ownerActive -> "已激活设备管理员（未成为 owner）"
                                else -> "未激活"
                            },
                        )
                        InfoRow("占用者", DeviceOwner.ownerPackage(context) ?: "无")
                        if (activateMessage.isNotBlank()) InfoRow("自动激活", activateMessage)
                        Row(
                            modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.inset, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ActionButton(
                                text = "激活设备管理员",
                                onClick = {
                                    val ok = runCatching {
                                        context.startActivity(DeviceOwner.requestAdmin(context))
                                        true
                                    }.getOrDefault(false)
                                    ownerActive = DeviceOwner.isAdminActive(context)
                                    message = if (ok) "请在系统弹窗里确认激活" else "无法拉起激活页面"
                                },
                            )
                            ActionButton(
                                text = "全部防卸载",
                                onClick = {
                                    val n = DeviceOwner.setUninstallBlockedAll(context, true)
                                    ownerSelf = DeviceOwner.isSelf(context)
                                    message = if (n > 0) "已保护 $n 个应用" else "需要先成为设备所有者"
                                },
                            )
                        }
                        BulletRow("设备所有者只能在设备未初始化时通过 adb 授予，普通安装无法自行激活。")
                    }
                }
            }

            if (section == "modules") item(key = "modules") {
                AnimatedGroup {
                    SectionCard(title = "模块", subtitle = "读取 /data/adb/modules") {
                        if (modules.isEmpty()) {
                            InfoRow("模块", "没有读到（需要 root）")
                        } else {
                            modules.take(12).forEach { m ->
                                Row(
                                    modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Metrics.inset, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = m.name,
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = buildString {
                                                append(m.id)
                                                if (m.version.isNotBlank()) append(" · ").append(m.version)
                                                if (m.pending) append(" · 待重启生效")
                                                if (m.disabled) append(" · 已停用")
                                            },
                                            style = MiuixTheme.textStyles.footnote2,
                                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (zybosState.isNotBlank()) {
                item(key = "zybos") {
                    SectionCard(title = "作业帮设备管控", subtitle = "ZybosSysApiManager") {
                        InfoRow("安装拦截", zybosState)
                        Row(
                            modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.inset, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ActionButton(
                                text = "解除安装拦截",
                                onClick = {
                                    scope.launch {
                                        val r = ZybosApi.control(
                                            context,
                                            ZybosApi.ControlAction.SetPackageList(emptyList()),
                                        )
                                        message = r.message
                                    }
                                },
                            )
                            ActionButton(
                                text = "禁用 USB",
                                onClick = {
                                    scope.launch {
                                        val r = ZybosApi.control(
                                            context,
                                            ZybosApi.ControlAction.SetUsbDisallow(true),
                                        )
                                        message = r.message
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (section == "grant") item(key = "agent") {
                AnimatedGroup {
                    SectionCard(title = "安装代理", subtitle = "app_process + PackageInstaller 会话") {
                        InfoRow(
                            "当前状态",
                            if (channels.installAgentReady()) "就绪（可尝试静默安装）" else "未就绪：需要 root 或无线调试配对",
                        )
                        BulletRow("安装时会先用 pm install（shell 身份），失败再走安装代理，最后才回退系统安装器。")
                        BulletRow("若安装失败，到「应用 → 安装 → 安装诊断」里看原始输出。")
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

        if (!riskAccepted) {
            ConsentDialog(
                title = "开启超级用户前请确认",
                message = "本页会向系统分区写入 su 守护进程、执行内核漏洞提权，并接管设备所有者。",
                consent = "root有风险，刷机需谨慎，出事不找我负责，请确保这是你拥有的设备",
                confirmText = "我已了解，进入",
                onConfirm = { riskAccepted = true },
            )
        }
    }
}
