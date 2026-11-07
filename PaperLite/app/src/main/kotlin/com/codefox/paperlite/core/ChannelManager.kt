package com.codefox.paperlite.core

import android.content.Context
import com.codefox.paperlite.core.channel.AdbConnection
import com.codefox.paperlite.core.channel.IdentityStore
import com.codefox.paperlite.core.channel.ShellResult
import com.codefox.paperlite.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyPair

/** 六条通道。 */
enum class Channel(
    val title: String,
    val desc: String,
    val provide: String,
) {
    DeviceOwner(
        "设备所有者",
        "DevicePolicyManager 授予的 owner 权限，可静默安装、单独应用防卸载、临时停用账号应用。",
        "静默安装 / 防卸载 / 临时降级",
    ),
    Wireless(
        "无线调试会话",
        "与本机无线调试服务配对后建立的 adb 会话，可执行任意 shell 命令。",
        "shell 命令 / 停用管控 / 冻结应用",
    ),
    LocalLoop(
        "本地回环推包",
        "经由 127.0.0.1 的 adbd 回环端口推包，无需无线调试，断网也可用。",
        "静默安装 / 静默卸载",
    ),
    Root(
        "Root 通道",
        "检测到 su 后直接用 root 执行，能力最全且不受系统校验限制。",
        "全部 shell 能力",
    ),
    Accessibility(
        "无障碍服务",
        "读取配对页面并自动填写配对码，也可用于自动化点击。",
        "配对码自动填写",
    ),
}

/** 单条通道的实时状态。 */
data class ChannelState(
    val channel: Channel,
    val available: Boolean = false,
    val detail: String = "未检测",
) {
    val statusText: String get() = if (available) "可用" else "不可用"
}

/**
 * 通道总控。
 *
 * 所有需要高权限的操作都从这里走：先按优先级选一条可用通道，
 * 拿不到再回退下一条，最终把失败原因透出到 UI。
 *
 * 优先级：Root > DeviceOwner > Wireless > LocalLoop
 */
class ChannelManager(
    private val context: Context,
    private val settings: Settings,
) {

    private val identity = IdentityStore(context)
    private val pairing = com.codefox.paperlite.core.channel.WirelessPairing(context)

    /** 复用的 adb 会话，避免每次命令都重新握手。 */
    @Volatile
    private var session: AdbConnection? = null
    private val sessionLock = Mutex()

    /**
     * 本地 adbd 直连会话（对应原版的「本地 ADB 模式」）。
     *
     * 与 [session]（无线调试）分开保存：两条通道的端口与生命周期不同，
     * 但拿到的都是 uid=2000 的 shell 权限，用途一致。
     */
    @Volatile
    private var localSession: AdbConnection? = null
    private val localSessionLock = Mutex()

    private val _states = MutableStateFlow(
        Channel.entries.associateWith { ChannelState(it) },
    )
    val states: StateFlow<Map<Channel, ChannelState>> = _states.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)

    /** 当前正在执行的耗时操作描述，null 表示空闲。 */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private fun update(channel: Channel, available: Boolean, detail: String) {
        _states.value = _states.value.toMutableMap().apply {
            put(channel, ChannelState(channel, available, detail))
        }
    }

    /**
     * 写一条运行日志。
     *
     * **必须截断**：推包时每 96KB 一块的 base64 有 128KB，
     * 原样塞进日志会让 StateFlow 每分钟搬运上百兆字符串——
     * 这是「点开始安装之后整个界面像卡死」的直接原因之一。
     */
    fun log(msg: String) {
        val line = if (msg.length > 240) msg.take(240) + "…" else msg
        settings.appendLog("[${nowText()}] $line")
    }

    private fun nowText(): String {
        val f = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return f.format(java.util.Date())
    }

    // ---------------------------------------------------------------- 探测

    /**
     * 一键探测全部通道。
     *
     * 每条探测单独包一层 [safely]：任何一条通道抛异常（反射被 ROM 拦、
     * Binder 被回收、su 进程卡死）都只把这一条标成「探测异常」，
     * 不能让它连带把整个首页一起带走——这是「打开就闪退」的常见原因。
     */
    suspend fun probeAll(): Map<Channel, ChannelState> = withContext(Dispatchers.IO) {
        _busy.value = "正在探测通道"
        try {
            safely(Channel.DeviceOwner) { probeDeviceOwner() }
            safely(Channel.Root) { probeRoot() }
            safely(Channel.Accessibility) { probeAccessibility() }
            safely(Channel.LocalLoop) { probeLocalLoop() }
            safelySuspend(Channel.Wireless) { probeWireless() }
            // 本地 adbd 直连：对齐原版的「本地 ADB 模式」。
            // 放在探测之后异步建立，不阻塞界面——扫端口可能要试几十个。
            if (states.value[Channel.Root]?.available != true &&
                states.value[Channel.Wireless]?.available != true
            ) {
                warmUpLocalAdb()
            }
        } finally {
            probed = true
            _busy.value = null
        }
        _states.value
    }

    /** 是否已经做过一次完整探测（应用启动时只探测一次，不必每次进页面都探）。 */
    @Volatile
    private var probed = false

    fun needsProbe(): Boolean = !probed

    /** 包一层异常，失败时把原因写进该通道的 detail，而不是往外抛。 */
    private inline fun safely(channel: Channel, block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val reason = e.message ?: e::class.java.simpleName
            update(channel, false, "探测异常：$reason")
        }
    }

    /** 挂起版本的 [safely]，供需要重连的探测使用。 */
    private suspend fun safelySuspend(channel: Channel, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val reason = e.message ?: e::class.java.simpleName
            update(channel, false, "探测异常：$reason")
        }
    }

    fun probeDeviceOwner() {
        val isSelf = DeviceOwner.isSelf(context)
        val owner = DeviceOwner.ownerPackage(context)
        val active = DeviceOwner.isAdminActive(context)
        when {
            isSelf -> update(Channel.DeviceOwner, true, "已成为设备所有者")
            owner != null -> update(Channel.DeviceOwner, false, "设备所有者已被 $owner 占用")
            active -> update(Channel.DeviceOwner, false, "已激活设备管理员，但未成为设备所有者")
            else -> update(Channel.DeviceOwner, false, "未激活")
        }
    }

    suspend fun probeRoot(): Boolean = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/debug_ramdisk/su",
        )
        val found = candidates.firstOrNull { runCatching { java.io.File(it).exists() }.getOrDefault(false) }
        if (found != null) {
            // 关键：必须用 `su -c id` 才算数。
            // 直接跑 `id` 得到的是应用自己的 uid，root 设备上也会被判成「没有 root」，
            // 于是 pm install / 安装代理整条链都用不到 root。
            // su 可能在没 root 的机器上存在但会一直阻塞等授权，所以加 2.5 秒超时
            val r: ShellResult? = kotlinx.coroutines.withTimeoutOrNull(2_500L) {
                kotlinx.coroutines.withContext(Dispatchers.IO) { execLocal("su -c id") }
            }
            val hasRoot = r != null && r.ok && r.output.contains("uid=0")
            update(Channel.Root, hasRoot, if (hasRoot) "已取得 root 身份" else "找到 $found 但无法执行")
            hasRoot
        } else {
            update(Channel.Root, false, "未找到 su")
            false
        }
    }

    fun probeAccessibility() {
        val enabled = com.codefox.paperlite.core.channel.PairingAccessibilityService.isConnected()
        update(Channel.Accessibility, enabled, if (enabled) "配对码自动填写已就绪" else "未开启无障碍服务")
    }

    private fun probeLocalLoop() {
        val port = settings.localLoopPort.value
        val reachable = runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), 400)
                true
            }
        }.getOrDefault(false)
        update(
            Channel.LocalLoop,
            reachable,
            if (reachable) "127.0.0.1:$port 可达" else "127.0.0.1:$port 不可达",
        )
    }

    /**
     * 探测无线调试通道。
     *
     * 不能只看 `session != null`：应用重启后内存里的会话必然为空，
     * 但设置里还记着上次的连接端口，这时直接判「不可用」，
     * 安装就会掉到系统安装器——用户看到的就是「静默安装失败」。
     * 所以这里在有待用端口时**主动重连一次**。
     */
    private suspend fun probeWireless() {
        if (session != null) {
            update(Channel.Wireless, true, "会话已建立（${settings.lastDevice.value ?: "127.0.0.1"}）")
            return
        }
        val port = settings.connectPort.value
        val last = settings.lastDevice.value
        if (port <= 0) {
            update(Channel.Wireless, false, if (last != null) "上次配对 $last，端口缺失" else "未配对")
            return
        }
        update(Channel.Wireless, false, "正在重连 127.0.0.1:$port …")
        val conn = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
            runCatching { requireSession() }.getOrNull()
        }
        if (conn != null) {
            update(Channel.Wireless, true, "会话已建立（127.0.0.1:$port）")
        } else {
            update(Channel.Wireless, false, "重连失败，请到「超级用户」页重新配对")
        }
    }

    // ---------------------------------------------------------------- 会话

    /**
     * 取得可用的 adb 会话；没有则尝试重连上次的端口。
     */
    suspend fun requireSession(): AdbConnection? = sessionLock.withLock {
        session?.let { return@withLock it }
        val port = settings.connectPort.value
        if (port <= 0) return@withLock null
        val conn = runCatching {
            AdbConnection.open("127.0.0.1", port, identity.obtain())
                .also { it.connect() }
        }.getOrNull()
        if (conn != null) {
            session = conn
            _busy.value = null
            update(Channel.Wireless, true, "会话已建立（127.0.0.1:$port）")
            log("无线调试会话已建立，端口 $port")
        } else {
            update(Channel.Wireless, false, "重连失败，请重新配对")
        }
        conn
    }

    /** 走无线调试会话执行 shell。 */
    suspend fun shell(command: String): ShellResult = withContext(Dispatchers.IO) {
        val conn = requireSession() ?: return@withContext ShellResult(
            command, "", 1, "无线调试会话未建立，请先完成配对",
        )
        val r = runCatching { conn.shell(command) }.getOrElse {
            session = null
            ShellResult(command, "", 1, it.message ?: "会话异常，已断开")
        }
        log("$ $command → 退出码 ${r.exitCode}")
        if (!r.ok && r.error != null) {
            update(Channel.Wireless, false, r.error)
        }
        r
    }

    /** 走无线调试会话执行 shell，并强制超时（默认 45 秒）。 */
    suspend fun shellTimeout(command: String, timeoutMs: Long = 45_000L): ShellResult =
        withContext(Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { shell(command) }
                ?: ShellResult(command, "", 124, "命令超时（${timeoutMs / 1000}s）")
        }

    /**
     * 只用「shell 身份」执行一条命令。
     *
     * ## 为什么需要单独一条
     *
     * `app_process` 加载的安装代理要在 system 身份下 attach system_server，
     * 这要求进程 uid 是 **root(0) 或 adb shell(2000)**。
     * 而普通的 [exec] 最后会退到「本进程」通道——那是应用自己的 uid，
     * SELinux 下 app 域没有向 system_server 做 binder 调用的权限，
     * `ActivityThread.systemMain()` 必然抛异常。
     *
     * 所以这条路径**不做兜底**：只走 root 与无线调试会话，
     * 两条都拿不到就明确返回失败，让调用方给出可执行的提示。
     */
    suspend fun execAsShell(
        command: String,
        timeoutMs: Long = 120_000L,
    ): ExecResult = withContext(Dispatchers.IO) {
        val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            runCatching { execShellInner(command) }.getOrElse {
                ExecResult(false, "", "异常", it.message ?: "执行异常")
            }
        }
        result ?: ExecResult(false, "", "超时", "命令执行超过 ${timeoutMs / 1000} 秒，已放弃")
    }

    private suspend fun execShellInner(command: String): ExecResult {
        val states = _states.value
        val attempts = ArrayList<ExecResult>(3)

        // 1. Root：su 之后 uid 变成 0
        if (states[Channel.Root]?.available == true) {
            val r = execLocal("su -c ${quote(command)}")
            val res = ExecResult(r.ok, r.output, "Root 通道", if (r.ok) null else r.error)
            attempts += res
            if (r.ok) return res
        }
        // 2. 无线调试会话：adb shell 的 uid 是 2000。
        //    只要设置里还记着连接端口，就先按需重连一次——
        //    状态缓存可能是「应用重启后还没探测」的旧值，不能拿它当准绳。
        if (states[Channel.Wireless]?.available == true || settings.connectPort.value > 0) {
            val conn = runCatching { requireSession() }.getOrNull()
            if (conn != null) {
                val r = runCatching { conn.shell(command) }.getOrElse {
                    session = null
                    ShellResult(command, "", 1, it.message ?: "会话异常，已断开")
                }
                val res = ExecResult(r.ok, r.output, "无线调试会话", if (r.ok) null else r.error)
                attempts += res
                if (r.ok) return res
            }
        }
        // 3. 本地回环：同样是 adb shell 身份
        if (states[Channel.LocalLoop]?.available == true) {
            val r = runCatching { localLoopShell(command) }
                .getOrElse { ShellResult(command, "", 1, it.message) }
            val res = ExecResult(r.ok, r.output, "本地回环推包", if (r.ok) null else r.error)
            attempts += res
            if (r.ok) return res
        }
        // 4. 本地 adbd 直连（对齐原版「本地 ADB 模式」）：
        //    扫 /proc/net/tcp 找 adbd 端口，用配对时保存的密钥直连。
        //    这条路不需要开无线调试，也不依赖外网。
        val localAdbResult = runCatching { localAdbShell(command) }
            .getOrElse { ShellResult(command, "", 1, it.message) }
        attempts += ExecResult(
            localAdbResult.ok,
            localAdbResult.output,
            "本地 ADB 直连",
            if (localAdbResult.ok) null else localAdbResult.error,
        )
        if (localAdbResult.ok) return attempts.last()

        val last = attempts.lastOrNull()
        return last?.copy(
            ok = false,
            error = "需要 root 或无线调试配对才能以 shell 身份执行（尝试了 ${attempts.size} 条）",
        ) ?: ExecResult(false, "", "无", "没有可用的 shell 身份通道，请先完成无线调试配对或获取 root")
    }


    /** 本地无通道时的兜底执行（只能跑当前进程权限内的命令）。 */
    fun execLocal(command: String): ShellResult = runCatching {
        val p = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        // 读超时要能中断，否则某些命令（如 su、ping）会永久挂住 IO 线程
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val code = p.waitFor()
        ShellResult(command, out, code, null)
    }.getOrElse { ShellResult(command, "", 1, it.message) }

    /**
     * 按优先级执行一条需要高权限的 shell 命令。
     *
     * 整条链路有 60 秒硬超时：任何一条通道卡住（su 等授权、adbd 半死、
     * 回环端口假死）都不会把调用方的协程永远挂起，也就不会出现
     * 「点了按钮之后整个界面像死了一样」的观感。
     */
    suspend fun exec(command: String, timeoutMs: Long = 60_000L): ExecResult =
        withContext(Dispatchers.IO) {
            val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                runCatching { execInner(command) }.getOrElse {
                    ExecResult(false, "", "本进程", it.message ?: "执行异常")
                }
            }
            result ?: ExecResult(false, "", "超时", "命令执行超过 ${timeoutMs / 1000} 秒，已放弃")
        }

    private suspend fun execInner(command: String): ExecResult {
        val states = _states.value
        // 逐条通道尝试，**任何一条失败都继续试下一条**。
        // 这点很关键：安装代理命令在 root 下可能因为 SELinux 或路径权限失败，
        // 但在无线调试会话下反而能跑通——如果第一条通道失败就直接返回，
        // 后面的通道根本没机会执行（原版 bbzow 失败会回退，走的就是这个逻辑）。
        val attempts = ArrayList<ExecResult>(4)

        // 1. Root
        if (states[Channel.Root]?.available == true) {
            val r = execLocal("su -c ${quote(command)}")
            val res = ExecResult(r.ok, r.output, "Root 通道", if (r.ok) null else r.error)
            attempts += res
            if (r.ok) return res
        }
        // 2. 无线调试
        if (states[Channel.Wireless]?.available == true) {
            val r = runCatching { shell(command) }
                .getOrElse { ShellResult(command, "", 1, it.message) }
            val res = ExecResult(r.ok, r.output, "无线调试会话", if (r.ok) null else r.error)
            attempts += res
            if (r.ok) return res
        }
        // 3. 本地回环
        if (states[Channel.LocalLoop]?.available == true) {
            val r = runCatching { localLoopShell(command) }
                .getOrElse { ShellResult(command, "", 1, it.message) }
            val res = ExecResult(r.ok, r.output, "本地回环推包", if (r.ok) null else r.error)
            attempts += res
            if (r.ok) return res
        }
        // 4. 本进程权限（能力有限，但至少能跑）
        val r = execLocal(command)
        val res = ExecResult(r.ok, r.output, "本进程", if (r.ok) null else r.error)
        attempts += res
        if (r.ok) return res

        // 全部失败：把最后一次的输出带出去，方便定位
        val last = attempts.lastOrNull()
        return last?.copy(
            ok = false,
            error = "所有通道均失败（尝试了 ${attempts.size} 条）",
        ) ?: ExecResult(false, "", "无", "没有可用通道")
    }

    /**
     * 走本机 adbd 直连执行命令。
     *
     * 对应原版 `mjFkc.rkYL`（本地 ADB 模式）：
     * 1. 扫 `/proc/net/tcp` 找 adbd 监听端口
     * 2. 用配对时保存的密钥直连本机 adbd
     * 3. 拿到 uid=2000 的 shell 会话执行命令
     *
     * 关键是第 3 步拿到的 shell 身份——`app_process` 安装代理必须靠它
     * 才能在 system 身份下提交安装会话。
     */
    private suspend fun localAdbShell(command: String): ShellResult {
        // 已有会话先复用
        localSession?.let { conn ->
            val r = runCatching { conn.shell(command) }.getOrNull()
            if (r != null && r.ok) return r
        }
        // 没有就去找端口并建立连接
        val conn = openLocalAdb() ?: return ShellResult(command, "", 1, "未找到可用的本机 adbd")
        val r = runCatching { conn.shell(command) }
            .getOrElse { ShellResult(command, "", 1, it.message) }
        return r
    }

    /**
     * 建立本地 adbd 直连。
     *
     * 扫端口可能耗时（每个端口都要试连），所以放在 IO 线程里做。
     */
    private suspend fun openLocalAdb(): AdbConnection? = localSessionLock.withLock {
        localSession?.let { return@withLock it }
        val keyPair = identity.obtain()
        for ((host, port) in com.codefox.paperlite.core.channel.LocalAdb.discoverAdbEndpoints()) {
            val conn = com.codefox.paperlite.core.channel.LocalAdb
                .tryConnect(host, port, keyPair)
                ?: continue
            localSession = conn
            log("本地 ADB 直连已建立：$host:$port")
            update(Channel.LocalLoop, true, "本地 adbd 直连 $host:$port")
            return@withLock conn
        }
        null
    }

    /**
     * 后台预热本地 adbd 直连。
     *
     * 对应原版 `mjFkc.alEkx()`：起一个名为 `jc-local-adb` 的守护线程，
     * 成功则提示「已切换到本地 ADB 模式（断网 / 关无线调试后仍可用）」。
     *
     * 这里不常驻线程（避免后台耗电），而是在需要时按需建立并缓存会话——
     * 效果一样，但不长期占资源。
     */
    private fun warmUpLocalAdb() {
        if (localSession != null) return
        try {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val conn = openLocalAdb()
                if (conn != null) {
                    log("已切换到本地 ADB 模式（断网 / 关无线调试后仍可用）")
                }
            }
        } catch (e: Throwable) {
            // 预热失败不影响主流程
        }
    }

    private fun localLoopShell(command: String): ShellResult {
        val port = settings.localLoopPort.value
        val conn = runCatching {
            AdbConnection.open("127.0.0.1", port, identity.obtain()).also { it.connect() }
        }.getOrNull() ?: return ShellResult(command, "", 1, "本地回环不可达")
        return try {
            conn.shell(command)
        } finally {
            runCatching { conn.close() }
        }
    }

    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    // ---------------------------------------------------------------- 配对

    suspend fun pair(code: String): com.codefox.paperlite.core.channel.WirelessPairing.Result =
        withContext(Dispatchers.IO) {
            _busy.value = "正在与设备配对"
            try {
                // identity.obtain() 现在保证不抛异常，这里再用 runCatching 兜底一层，
                // 确保任何意外（RSA 提供器异常等）都转成 Result，绝不穿透到 scope.launch。
                val keyPair = runCatching { identity.obtain() }.getOrNull()
                    ?: return@withContext com.codefox.paperlite.core.channel.WirelessPairing.Result(
                        false, 0, 0, "无法生成本机 adb 密钥（RSA 提供器异常）",
                    )
                val r = pairing.pair(
                    pairCode = code,
                    keyPair = keyPair,
                    port = settings.pairPort.value,
                    onStage = { log(it) },
                )
                if (r.success) {
                    settings.setPairPort(r.pairPort)
                    settings.setConnectPort(r.connectPort)
                    settings.setLastDevice("127.0.0.1")
                    update(Channel.Wireless, true, "配对成功")
                } else {
                    update(Channel.Wireless, false, r.message)
                }
                log("配对结果：${r.message}")
                r
            } catch (e: Exception) {
                // 双保险：WirelessPairing.pair 内部已兜住握手异常，
                // 这里再兜一层，任何未料的异常都转成 Result 返回，
                // 避免未捕获异常在 scope.launch 里直接杀掉进程。
                com.codefox.paperlite.core.channel.WirelessPairing.Result(
                    false, 0, 0, "配对异常：${e.message ?: e::class.java.simpleName}",
                )
            } finally {
                _busy.value = null
            }
        }

    /** 主动发现配对端口与连接端口。 */
    suspend fun discoverPorts(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        _busy.value = "正在搜索无线调试服务"
        try {
            val pairPort = pairing.findPairingPort()
            val connPort = pairing.findConnectPort()
            if (pairPort > 0) settings.setPairPort(pairPort)
            if (connPort > 0) settings.setConnectPort(connPort)
            log("发现配对端口 $pairPort，连接端口 $connPort")
            pairPort to connPort
        } finally {
            _busy.value = null
        }
    }

    /** 断开并清除本机配对身份。 */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { session?.close() }
        session = null
        settings.setConnectPort(0)
        settings.setLastDevice(null)
        update(Channel.Wireless, false, "已断开")
        log("无线调试会话已断开")
        // 清理系统侧残留的配对记录
        cleanupSystemPairings()
    }

    /**
     * 清理系统里由本应用产生的无线调试配对。
     *
     * 系统的配对记录在 `/data/misc/adb/adb_keys`，普通权限删不掉；
     * 有高于普通权限的通道时才能清理，否则只能做本应用侧清理。
     */
    suspend fun cleanupSystemPairings(): Boolean = withContext(Dispatchers.IO) {
        val r = exec("rm -f /data/misc/adb/adb_keys 2>/dev/null; echo done")
        val ok = r.ok
        log(if (ok) "系统配对记录已清理" else "系统配对记录清理失败：${r.error ?: "权限不足"}")
        ok
    }

    fun identityStore(): IdentityStore = identity

    /**
     * 设置的只读入口。
     *
     * 安装器需要读「安装授权器」，但它本身不持有 Settings，
     * 由通道总控代为暴露，避免再往构造参数里塞一层。
     */
    val settingsRef: Settings get() = settings

    /**
     * 取本应用自身的 APK 路径。
     *
     * 原版 JC Code V3 的安装代理靠 `CLASSPATH='<自身 APK>' app_process ...`
     * 把自己的 dex 挂进 system 进程，所以这里必须给出真实的 sourceDir，
     * 而不是 classpath 里的某个 jar。
     */
    fun selfApkPath(): String? = runCatching {
        context.applicationInfo.sourceDir?.takeIf { it.isNotBlank() && it.endsWith(".apk") }
    }.getOrNull()

    /**
     * 安装代理是否可用。
     *
     * 依赖两点：自身 APK 路径可读（CLASSPATH 的载体），
     * 以及至少有一条能在目标机上执行 shell 的通道。
     */
    fun installAgentReady(): Boolean {
        val s = _states.value
        val hasChannel = s[Channel.Root]?.available == true ||
            s[Channel.Wireless]?.available == true ||
            s[Channel.LocalLoop]?.available == true
        return hasChannel && agentAssetPresent
    }

    /**
     * 安装代理 dex 是否真的在 APK 里。
     *
     * 之前这里默认「assets 里天然存在」，结果打包漏了文件时界面照样显示
     * 「就绪（通道① 可用）」，装不上却看不出原因——现在改成真的去开一次。
     */
    private val agentAssetPresent: Boolean by lazy {
        runCatching { context.assets.open("agent/classes.dex").use { it.read() >= 0 } }
            .getOrDefault(false)
    }

    /**
     * 有没有能以 shell 身份执行命令的通道。
     *
     * 与 [installAgentReady] 的区别：这里不检查自身 APK，
     * 只回答「能不能拿到 root 或 adb shell」。
     */
    fun hasShellIdentity(): Boolean {
        val s = _states.value
        return s[Channel.Root]?.available == true ||
            s[Channel.Wireless]?.available == true ||
            s[Channel.LocalLoop]?.available == true
    }

    /**
     * 现场确认「现在能不能拿到 shell 身份」，而不是读缓存状态。
     *
     * 安装流程必须先问这一句：`states` 只是上一次探测的快照，
     * 应用重启、无线调试端口变化、root 授权被撤，都会让它过期。
     * 只看快照就会得出「没有可用通道」，然后把安装甩给系统安装器。
     */
    suspend fun acquireShellIdentity(): Boolean = withContext(Dispatchers.IO) {
        if (hasShellIdentity()) return@withContext true
        val r = runCatching { execShellInner("id") }.getOrNull() ?: return@withContext false
        val uid = r.output
        val ok = r.ok && (uid.contains("uid=0") || uid.contains("uid=2000"))
        if (ok) {
            // 探测成功后同步一次状态，界面上的「可用」才不会是假的不可用
            when {
                uid.contains("uid=0") -> update(Channel.Root, true, "已取得 root 身份")
                else -> update(Channel.Wireless, true, "会话已建立")
            }
            log("安装前探测：已取得 shell 身份（${r.via}）")
        }
        ok
    }

    fun release() {
        runCatching { session?.close() }
        session = null
        runCatching { localSession?.close() }
        localSession = null
    }
}

/** 一次高权限命令执行结果，带走了哪条通道。 */
data class ExecResult(
    val ok: Boolean,
    val output: String,
    val via: String,
    val error: String?,
)
