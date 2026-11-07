package com.codefox.paperlite.core

import android.content.Context
import android.util.Log
import java.io.File
import java.lang.reflect.Method

/**
 * 作业帮 / AIbangOS 设备的系统级接口封装。
 *
 * 移植自 InstallerX 的 Birchua 授权器：调 `android.os.ZybosSysApiManager`
 * 的 `silentInstallApk` 静默安装，绕过 PadMS 安装管控，不需要 root 也不弹确认框。
 *
 * 服务名 `zybos_manager`，方法有 `setInstallPackageDisallow`、
 * `silentInstallApk`、`silentUninstallApk`、`isDisallowInstallPackage`
 * 以及几个设备管控开关。不同批次的 ROM 上不一定齐全，
 * 全部走反射 + 存在性检查，缺了就说「不支持」。
 *
 * 三个必须注意的点：
 * 1. 路径要落在公共目录（服务在另一个进程，读不到应用私有目录）；
 * 2. `/data/media/0/` 要转成 `/storage/emulated/0/`；
 * 3. 必须先 `setInstallPackageDisallow(false)` 再装。
 */
object ZybosApi {

    private const val TAG = "ZybosApi"

    /** 系统服务名。 */
    const val SERVICE = "zybos_manager"

    /** 服务实现类。 */
    const val CLASS_NAME = "android.os.ZybosSysApiManager"

    /** 兜底投放目录：原实现用的是这个名字，这里沿用以免踩到 ROM 上的路径白名单。 */
    private const val FALLBACK_DIR = "/storage/emulated/0/birchua_误删"

    /** 安装后轮询验证的最长时间。 */
    private const val VERIFY_TIMEOUT_MS = 30_000L

    /** 轮询间隔。 */
    private const val VERIFY_INTERVAL_MS = 5_000L

    /** 一次操作的结果。 */
    data class Result(
        val success: Boolean,
        val message: String,
        /** 真正交给系统服务的 APK 路径（用了兜底目录时是临时路径）。 */
        val usedPath: String? = null,
        /** 该功能在这台设备上是否受支持。 */
        val supported: Boolean = true,
    )

    // ------------------------------------------------------------------
    // 能力探测
    // ------------------------------------------------------------------

    /** 这台设备上有没有作业帮系统服务。 */
    fun isAvailable(context: Context): Boolean = runCatching {
        service(context) != null
    }.getOrDefault(false)

    /** 取系统服务实例；不是作业帮设备时返回 null。 */
    private fun service(context: Context): Any? = runCatching {
        context.getSystemService(SERVICE)
    }.getOrNull()

    /**
     * 取服务实例并做好 hidden API 放行。
     *
     * 反射调用系统内部类必须先放行 hidden API，
     * 否则 Android 9+ 上会直接抛 `NoSuchMethodException`。
     */
    private fun serviceForReflection(context: Context): Any? {
        HiddenApiBypass.bypass()
        return service(context)
    }

    private fun findMethod(target: Any, name: String, vararg params: Class<*>): Method? = runCatching {
        target.javaClass.getMethod(name, *params)
    }.getOrNull()

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    /**
     * 用作业帮系统服务静默安装 APK。
     *
     * @param packageName 目标包名；传空则由服务自行解析
     */
    fun silentInstall(context: Context, apk: File, packageName: String): Result {
        if (!apk.exists()) {
            return Result(false, "APK 不存在：${apk.absolutePath}")
        }
        val svc = serviceForReflection(context)
            ?: return Result(false, "这台设备没有作业帮系统服务（$SERVICE）", supported = false)

        // ① 解除安装拦截（PadMS 管控）
        val disallow = findMethod(svc, "setInstallPackageDisallow", java.lang.Boolean.TYPE)
        if (disallow != null) {
            runCatching { disallow.invoke(svc, false) }
                .onFailure { Log.w(TAG, "setInstallPackageDisallow 失败: ${it.message}") }
        }

        // ② 找一个系统服务读得到的路径
        val placed = placeReadable(context, apk)
            ?: return Result(false, "无法把安装包放到系统服务可读的位置")
        val (path, isTemp) = placed

        // ③ 调用静默安装
        val install = findMethod(
            svc,
            "silentInstallApk",
            String::class.java,
            java.lang.Boolean.TYPE,
            String::class.java,
        ) ?: run {
            if (isTemp) runCatching { File(path).delete() }
            return Result(false, "系统服务没有 silentInstallApk 方法", supported = false)
        }

        val invoked = runCatching {
            install.invoke(svc, path, true, packageName)
        }
        if (invoked.isFailure) {
            if (isTemp) runCatching { File(path).delete() }
            return Result(false, "silentInstallApk 调用失败：${invoked.exceptionOrNull()?.message}")
        }

        // ④ 轮询验证是否真的装上了
        val installed = verifyInstalled(context, packageName)
        if (isTemp) {
            // 兜底目录里的临时包用完即删
            runCatching { File(path).delete() }
        }
        if (installed) {
            return Result(true, "已通过作业帮系统服务静默安装", path)
        }
        return Result(
            false,
            "安装调用已发出，但 ${VERIFY_TIMEOUT_MS / 1000} 秒内没有检测到 $packageName，可能被 PadMS 拦截",
            path,
        )
    }

    /** 轮询确认包是否真的安装成功。 */
    private fun verifyInstalled(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return true
        val pm = context.packageManager
        var waited = 0L
        while (waited < VERIFY_TIMEOUT_MS) {
            val ok = runCatching { pm.getPackageInfo(packageName, 0) }.isSuccess
            if (ok) return true
            Thread.sleep(VERIFY_INTERVAL_MS)
            waited += VERIFY_INTERVAL_MS
        }
        return false
    }

    /**
     * 让 APK 落到系统服务读得到的位置。
     *
     * @return Pair(路径, 是否是临时拷贝)
     */
    private fun placeReadable(context: Context, apk: File): Pair<String, Boolean>? {
        // 已经是公共目录里的文件，直接用
        if (isPubliclyReadable(apk.absolutePath) && apk.canRead()) {
            return apk.absolutePath to false
        }
        // 本应用私有目录（/data/user/0/<pkg>/cache）系统服务读不到，必须拷出去
        val targets = listOf(
            File(FALLBACK_DIR),
            context.getExternalFilesDir(null),
        )
        for (dir in targets) {
            if (dir == null) continue
            val copied = runCatching {
                dir.mkdirs()
                val out = File(dir, "install_${System.currentTimeMillis()}.apk")
                apk.inputStream().use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                if (out.exists() && out.length() == apk.length()) out.absolutePath else null
            }.getOrNull()
            if (copied != null) return copied to true
        }
        return null
    }

    /** 路径是否在所有应用都能读到的公共区域。 */
    private fun isPubliclyReadable(path: String): Boolean =
        path.startsWith("/storage/emulated/0/") ||
            path.startsWith("/sdcard/") ||
            path.startsWith("/storage/self/primary/") ||
            path.startsWith("/mnt/sdcard/")

    // ------------------------------------------------------------------
    // 卸载 / 管控
    // ------------------------------------------------------------------

    /** 静默卸载（如果 ROM 支持该方法）。 */
    fun silentUninstall(context: Context, packageName: String): Result {
        val svc = serviceForReflection(context)
            ?: return Result(false, "这台设备没有作业帮系统服务", supported = false)
        val method = findMethod(
            svc,
            "silentUninstallApk",
            String::class.java,
            java.lang.Boolean.TYPE,
        ) ?: return Result(false, "系统服务没有 silentUninstallApk 方法", supported = false)
        return runCatching {
            method.invoke(svc, packageName, true)
            Result(true, "已通过作业帮系统服务静默卸载")
        }.getOrElse { Result(false, "静默卸载失败：${it.message}") }
    }

    /** 查询当前是否处于「禁止安装」状态。 */
    fun installDisallowState(context: Context): Result {
        val svc = serviceForReflection(context)
            ?: return Result(false, "这台设备没有作业帮系统服务", supported = false)
        val method = findMethod(svc, "isDisallowInstallPackage")
            ?: return Result(false, "系统服务没有 isDisallowInstallPackage 方法", supported = false)
        return runCatching {
            val v = method.invoke(svc)
            Result(true, if (v == true) "当前禁止安装" else "当前允许安装")
        }.getOrElse { Result(false, "查询失败：${it.message}") }
    }

    /**
     * 设备管控类开关。
     *
     * @param action 管控动作
     */
    fun control(context: Context, action: ControlAction): Result {
        val svc = serviceForReflection(context)
            ?: return Result(false, "这台设备没有作业帮系统服务", supported = false)
        return runCatching {
            when (action) {
                is ControlAction.SetPackageList -> {
                    val m = findMethod(svc, "setPackageListDisallow", List::class.java)
                        ?: return Result(false, "系统服务不支持设置禁用包列表", supported = false)
                    m.invoke(svc, action.packages)
                    Result(true, "已更新禁用包列表（${action.packages.size} 项）")
                }

                is ControlAction.SetClassList -> {
                    val m = findMethod(svc, "setClassListDisallow", List::class.java)
                        ?: return Result(false, "系统服务不支持设置课堂禁用列表", supported = false)
                    m.invoke(svc, action.classes)
                    Result(true, "已更新课堂禁用列表（${action.classes.size} 项）")
                }

                is ControlAction.SetUsbDisallow -> {
                    val m = findMethod(svc, "setUSBDisallow", java.lang.Boolean.TYPE)
                        ?: return Result(false, "系统服务不支持 USB 管控", supported = false)
                    m.invoke(svc, action.disallow)
                    Result(true, if (action.disallow) "已禁用 USB" else "已恢复 USB")
                }

                is ControlAction.CancelUpdate -> {
                    val m = findMethod(svc, "cancelUpdate")
                        ?: return Result(false, "系统服务不支持取消更新", supported = false)
                    m.invoke(svc)
                    Result(true, "已取消系统更新")
                }
            }
        }.getOrElse { Result(false, "管控操作失败：${it.message}") }
    }

    /** 设备管控动作。 */
    sealed interface ControlAction {
        data class SetPackageList(val packages: List<String>) : ControlAction
        data class SetClassList(val classes: List<String>) : ControlAction
        data class SetUsbDisallow(val disallow: Boolean) : ControlAction
        data object CancelUpdate : ControlAction
    }
}
