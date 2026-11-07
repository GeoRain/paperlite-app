package com.codefox.paperlite.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codefox.paperlite.core.Channel
import com.codefox.paperlite.core.ChannelManager
import com.codefox.paperlite.core.Installer
import com.codefox.paperlite.core.ZybosApi
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.BulletRow
import com.codefox.paperlite.ui.components.InfoRow
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.Pill
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.components.TextInput
import com.codefox.paperlite.ui.components.ToastMessage
import com.codefox.paperlite.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 安装通道主体。
 *
 * 输入 APK 路径 → 校验 → 按通道优先级尝试静默安装 → 失败回退到系统安装器。
 * 默认自带页头；被「应用」页的分段标签内嵌时传 `showHeader = false`。
 */
@Composable
fun InstallPage(
    channels: ChannelManager,
    showHeader: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = remember { Installer(context, channels) }
    val listState = rememberLazyListState()

    var path by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Installer.Outcome?>(null) }
    var meta by remember { mutableStateOf<Installer.ApkMeta?>(null) }
    var picking by remember { mutableStateOf(false) }
    // 安装诊断输出：直接把原始输出摊开给用户看
    var diagnostic by remember { mutableStateOf<String?>(null) }

    val states by channels.states.collectAsStateWithLifecycle()

    @Suppress("DEPRECATION")
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    fun inspect() {
        val f = File(path.trim())
        meta = if (f.exists()) installer.parseApk(f) else null
    }

    /**
     * 内置文件选择器（对齐原版 JC Code 3）。
     *
     * SAF 返回的是 content:// Uri，不能直接当 File 用，
     * 所以先把流拷到**外部存储目录**再走安装链路。
     *
     * 为什么不放 cacheDir：cacheDir 是应用私有目录，shell / app_process 读不到，
     * 而安装代理恰恰需要用 shell 去读这个 APK。原版也是把包放在
     * `/storage/` 或 `/sdcard/` 下——规则完全一致。
     */
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        picking = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val name = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                }
            }.getOrNull() ?: "picked.apk"
            // 放在外部存储，shell 与 app_process 都能读到
            val apkDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "apk")
            if (!apkDir.exists()) apkDir.mkdirs()
            val dest = File(apkDir, "install_$name")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            busy = false
            if (dest.exists() && dest.length() > 0) {
                path = dest.absolutePath
                meta = installer.parseApk(dest)
            } else {
                meta = null
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 12.dp),
    ) {
        if (showHeader) {
            item(key = "header") {
                PageHeader(
                    title = "安装",
                    kicker = "选包 → 解析 → 按通道安装 → 失败回退系统安装器",
                )
            }
        }

        item(key = "avail") {
            SectionCard(title = "可用通道") {
                val c = LocalStatusColors.current
                val authorizer by channels.settingsRef.installAuthorizer.collectAsStateWithLifecycle()
                InfoRow("安装授权器", "${authorizer.title} · ${authorizer.desc}")
                if (ZybosApi.isAvailable(context)) {
                    InfoRow("作业帮系统服务", "可用（可无声安装）")
                }
                Channel.entries.forEach { ch ->
                    val st = states[ch]
                    if (st?.available == true) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Metrics.inset, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Pill(text = "可用", color = c.ok, onColor = c.onOk)
                            Spacer(Modifier.width(9.dp))
                            Text(
                                text = ch.title,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = st.detail,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (states.values.none { it.available }) {
                    BulletRow("当前没有任何高权限通道，只能回退到系统安装界面。")
                }
                InfoRow(
                    "安装代理",
                    if (channels.installAgentReady()) {
                        "就绪（通道① 可用）"
                    } else {
                        "未就绪：需要 root 或无线调试配对"
                    },
                )
                if (!channels.installAgentReady()) {
                    BulletRow(
                        "安装代理要用 app_process 在 system 身份下提交安装会话，" +
                            "这要求命令以 root 或 adb shell 身份执行。" +
                            "当前没有这样的通道，请先到「配对」页完成无线调试配对。",
                    )
                }
            }
        }

        item(key = "input") {
            SectionCard(title = "安装本地文件", subtitle = "从文件选择器挑选，或直接填路径") {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    ActionButton(
                        text = if (picking) "打开选择器…" else "选择 APK 文件",
                        onClick = {
                            picking = true
                            picker.launch("application/vnd.android.package-archive")
                        },
                    )
                    ActionButton(
                        text = "解析信息",
                        onClick = {
                            if (path.isBlank()) {
                                meta = null
                            } else {
                                inspect()
                            }
                        },
                    )
                }
                TextInput(
                    value = path,
                    onValueChange = {
                        path = it
                        if (meta != null) meta = null
                    },
                    label = "/storage/emulated/0/Download/app.apk",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 3.dp),
                    singleLine = true,
                )
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    ActionButton(
                        text = "解析信息",
                        onClick = {
                            if (path.isBlank()) {
                                meta = null
                            } else {
                                inspect()
                            }
                        },
                    )
                    ActionButton(
                        text = if (busy) "安装中…" else "开始安装",
                        onClick = {
                            if (path.isBlank()) {
                                result = Installer.Outcome(false, "无", "请先选择或填写 APK 路径")
                                return@ActionButton
                            }
                            busy = true
                            result = null
                            scope.launch {
                                val f = File(path.trim())
                                val r = withContext(Dispatchers.IO) { installer.install(f) }
                                result = r
                                busy = false
                            }
                        },
                    )
                }
                if (meta != null) {
                    Spacer(Modifier.height(3.dp))
                    InfoRow("应用名", meta!!.label)
                    InfoRow("包名", meta!!.packageName, mono = true)
                    InfoRow("版本", "${meta!!.versionName} (${meta!!.versionCode})")
                    InfoRow("大小", com.codefox.paperlite.data.DeviceInfo.formatSize(meta!!.size))
                }
            }
        }

        if (result != null) {
            item(key = "result") {
                val r = result!!
                SectionCard(title = "安装结果", subtitle = "通道：${r.via}") {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.inset, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val c = LocalStatusColors.current
                        Pill(
                            text = when {
                                r.success -> "成功"
                                r.requiresUserAction -> "待确认"
                                else -> "失败"
                            },
                            color = when {
                                r.success -> c.ok
                                r.requiresUserAction -> c.warn
                                else -> c.danger
                            },
                            onColor = when {
                                r.success -> c.onOk
                                r.requiresUserAction -> c.onWarn
                                else -> c.onDanger
                            },
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = r.message,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item(key = "diagnose") {
            SectionCard(
                title = "安装诊断",
                subtitle = "装不上时用这个看卡在哪一步",
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    ActionButton(
                        text = if (busy) "执行中…" else "运行诊断",
                        onClick = {
                            if (path.isBlank()) {
                                diagnostic = "请先选择或填写 APK 路径"
                                return@ActionButton
                            }
                            busy = true
                            diagnostic = null
                            scope.launch {
                                val text = withContext(Dispatchers.IO) {
                                    installer.diagnose(File(path.trim()))
                                }
                                diagnostic = text
                                busy = false
                            }
                        },
                    )
                    ActionButton(
                        text = "复制输出",
                        onClick = {
                            val t = diagnostic
                            if (t.isNullOrBlank()) {
                                diagnostic = "还没有诊断输出"
                            } else {
                                clipboard.setText(AnnotatedString(t))
                                diagnostic = "诊断输出已复制"
                            }
                        },
                    )
                }
                if (diagnostic != null) {
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            text = diagnostic.orEmpty(),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                            maxLines = 24,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = Metrics.inset, vertical = 2.dp),
                        )
                    }
                }
                BulletRow("诊断会真的跑一遍安装代理命令，把系统返回的原始输出贴出来。")
                BulletRow("常见返回：ClassNotFoundException 说明入口没被找到；Permission denied 说明 SELinux 拦了。")
            }
        }

        item(key = "howto") {
            SectionCard(title = "通道说明（与原版 JC Code V3 一致）") {
                BulletRow("通道① 安装代理：把自身 APK 挂给 app_process，以 system 身份走 PackageInstaller 会话 API，全静默。")
                BulletRow("通道② 本地 pm install：pm install -r -d 覆盖安装。")
                BulletRow("通道③ 无线调试会话：推包到 /data/local/tmp 后 pm install。")
                BulletRow("通道④ 设备所有者：owner 权限下的 PackageInstaller 静默会话。")
                BulletRow("通道⑤ 系统安装器：以上都失败时拉起系统界面，需要手动确认。")
            }
        }
        item(key = "sp") { Spacer(Modifier.height(16.dp)) }
    }

        // 安装结果反馈：从底部滑入淡出
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val r = result
            ToastMessage(
                text = r?.let {
                    (if (it.success) "安装成功 · ${it.via}" else "安装失败 · ${it.via}") +
                        "：${it.message}"
                },
            )
        }
    }
}
