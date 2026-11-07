package com.codefox.paperlite.core

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 随包自带的高权限二进制与启动件。
 *
 * ## 为什么不再向设备上已安装的应用"借"
 *
 * 旧版 DirtyFrag 会扫描设备上所有第三方应用，找 `nativeLibraryDir` 里带
 * `libksud.so` 的 KernelSU 系管理器，把它的 ksud 复制出来用；Shizuku 页也
 * 要求用户先装官方 APK、还得先打开一次让它生成 `start.sh`。
 * 这两条都是**隐式外部依赖**：用户没装就用不了，装了不同版本又可能对不上。
 *
 * 现在这两个上游的组件都直接从官方发行 APK 里提取、按 ABI 打进本应用的
 * assets，运行时自己投放。用到的东西全部随包走，不再依赖用户环境。
 *
 * ## 来源（版本锁定，升级时整组替换）
 *
 * - `assets/bundled/ksud/<abi>/libksud.so`
 *   来自 SukiSU_Vv4.2.1-r1.apk 的 `lib/<abi>/libdhxddxovny.so`。
 *   SukiSU 把 ksud 的库名混淆了，但里面是完整的 Rust 守护进程
 *   （`ksud::su`、`/data/adb/ksu/` 等符号都在），可以直接当可执行文件跑。
 * - `assets/bundled/shizuku/<abi>/libshizuku.so`
 *   来自 Shizuku_V13.6.0.r1086.2650830c.apk。它是**可执行 ELF**（有 `main`），
 *   吃 `--apk=`，自己设 `CLASSPATH` 与 `-Dshizuku.library.path` 再
 *   `execvp("app_process", ... rikka.shizuku.server.ShizukuService")`。
 *   不是 JNI 库，所以不能放进 jniLibs，只能当 assets 投放。
 * - `rish` / `rish_shizuku.dex`：官方终端侧 shell 启动件。
 * - `shizuku.apk`：官方 Shizuku 本体。**必须保留**——
 *   `ShizukuService.main()` 会查自己的 `ApplicationInfo` 并从自身 classes.dex
 *   加载服务端类，找不到就 `System.exit(50)`，所以只投启动器是起不来服务的。
 *   PaperLite 内置它并在需要时自行静默安装，用户不必去外面找包。
 */
object BundledBinaries {

    /** 内置 ksud 的 SukiSU 包名：bootstrap 靠 `su_manager` 偏好把它传给 ksud。 */
    const val KSUD_PACKAGE = "com.sukisu.ultra"

    /** 内置 Shizuku 的包名。 */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private const val KSUD_DIR = "bundled/ksud"
    private const val SHIZUKU_DIR = "bundled/shizuku"

    /** 随包提供的 ABI（按优先级，与 Android 的 SUPPORTED_ABIS 顺序对齐）。 */
    private val KSUD_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    private val SHIZUKU_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /**
     * 选一个本机 ABI。
     *
     * 不能只看 `Build.SUPPORTED_ABIS[0]`：x86 模拟器上可能只有 x86_64，
     * 32 位进程跑在 64 位系统上时首选 ABI 也可能是 armeabi-v7a。
     * 逐个比对到内置里确实存在的那个，避免拿到一个 64 位 ksud
     * 交给 32 位 app_process 执行。
     */
    /**
     * 选一个本机 ABI。
     *
     * 不能只看 `Build.SUPPORTED_ABIS[0]`：x86 模拟器上可能只有 x86_64，
     * 32 位进程跑在 64 位系统上时首选 ABI 也可能是 armeabi-v7a。
     * 逐个比对到内置里确实存在的那个，避免拿到一个 64 位 ksud
     * 交给 32 位 app_process 执行。
     *
     * **必须探具体文件**：`AssetManager.open()` 打不开目录，
     * 拿 `bundled/ksud/arm64-v8a` 这种目录路径去试会一律抛异常，
     * 结果就是所有 ABI 都被判成"缺失"。
     */
    private fun pickAbi(
        candidates: List<String>,
        context: Context,
        baseDir: String,
        probeFile: String,
    ): String? {
        for (abi in Build.SUPPORTED_ABIS) {
            if (abi in candidates && hasAsset(context, "$baseDir/$abi/$probeFile")) return abi
        }
        // 兜底：64 位系统能兼容 32 位库，反过来不行；
        // 这里按 candidates 顺序取第一个确实内置的。
        return candidates.firstOrNull { hasAsset(context, "$baseDir/$it/$probeFile") }
    }

    /** 当前架构的显示名，用于日志与界面提示（以 ksud 为准）。 */
    fun abi(context: Context): String =
        pickAbi(KSUD_ABIS, context, KSUD_DIR, "libksud.so")
            ?: Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    private fun hasAsset(context: Context, path: String): Boolean = runCatching {
        context.assets.open(path).use { true }
    }.getOrDefault(false)

    /** 读一份 assets 文件；不存在返回 null（不抛异常打断调用链）。 */
    fun readAsset(context: Context, path: String): ByteArray? = runCatching {
        context.assets.open(path).use { it.readBytes() }
    }.getOrNull()

    /**
     * 取当前 ABI 的 ksud。
     *
     * @return 字节与所用 ABI；本机架构没有对应内置时返回 null
     */
    fun ksud(context: Context): Pair<ByteArray, String>? {
        val abi = pickAbi(KSUD_ABIS, context, KSUD_DIR, "libksud.so") ?: return null
        return readAsset(context, "$KSUD_DIR/$abi/libksud.so")?.let { it to abi }
    }

    /** 取当前 ABI 的 Shizuku 启动器 ELF。 */
    fun shizukuStarter(context: Context): Pair<ByteArray, String>? {
        val abi = pickAbi(SHIZUKU_ABIS, context, SHIZUKU_DIR, "libshizuku.so") ?: return null
        return readAsset(context, "$SHIZUKU_DIR/$abi/libshizuku.so")?.let { it to abi }
    }

    /** 取当前 ABI 的 rish / adb 客户端库（与服务同目录投放）。 */
    fun shizukuLibs(context: Context, abi: String): Map<String, ByteArray> = buildMap {
        for (name in listOf("librish.so", "libadb.so")) {
            readAsset(context, "$SHIZUKU_DIR/$abi/$name")?.let { put(name, it) }
        }
    }

    /** 官方 `rish` 脚本与 `rish_shizuku.dex`。 */
    fun shizukuRish(context: Context): Pair<ByteArray, ByteArray>? {
        val script = readAsset(context, "$SHIZUKU_DIR/rish") ?: return null
        val dex = readAsset(context, "$SHIZUKU_DIR/rish_shizuku.dex") ?: return null
        return script to dex
    }

    /** 只探存在性，不读 2.5MB 内容——界面重组时用它。 */
    fun hasShizukuApk(context: Context): Boolean =
        hasAsset(context, "$SHIZUKU_DIR/shizuku.apk")

    /** 同上，界面用。 */
    fun hasStarter(context: Context): Boolean = shizukuStarter(context) != null

    /** ksud 是否随包可用（只探存在性，界面重组时用它）。 */
    fun hasKsud(context: Context): Boolean =
        pickAbi(KSUD_ABIS, context, KSUD_DIR, "libksud.so") != null

    /**
     * 把内置的官方 Shizuku APK 落到应用私有目录，交给 [Installer] 安装。
     *
     * 只有一份，不按 ABI 分（APK 里自带四个架构的库）。
     */
    fun shizukuApk(context: Context): File? {
        val bytes = readAsset(context, "$SHIZUKU_DIR/shizuku.apk") ?: return null
        val out = File(context.filesDir, "bundled/shizuku.apk")
        out.parentFile?.mkdirs()
        // 内容一致就不重写，避免每次进入页面都触发一次大文件 IO
        if (out.exists() && out.length() == bytes.size.toLong()) return out
        return runCatching {
            out.outputStream().use { it.write(bytes) }
            out
        }.getOrNull()
    }

    /**
     * 内置件是否齐全（用于界面给出明确提示，而不是运行时才失败）。
     * 全部只探存在性，不读大文件——这个函数会在 Compose 重组里被调用。
     */
    fun integrity(context: Context): String = buildString {
        appendLine("架构：${abi(context)}")
        appendLine("ksud：${if (hasKsud(context)) "已内置" else "缺失"}")
        appendLine("Shizuku 启动器：${if (hasStarter(context)) "已内置" else "缺失"}")
        appendLine("Shizuku 本体：${if (hasShizukuApk(context)) "已内置" else "缺失"}")
    }.trimEnd()
}
