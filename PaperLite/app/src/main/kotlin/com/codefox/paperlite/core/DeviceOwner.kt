package com.codefox.paperlite.core

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 设备所有者 / 设备管理接收器。
 *
 * 持有它可以做三件别处做不到的事：
 * - `setPackagesSuspended`：临时停用账号应用（临时降级流程用）
 * - `setUninstallBlocked`：单独应用防卸载，重启后依然有效
 * - `installExistingPackage`：无需用户点确认的静默安装
 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) = Unit
    override fun onDisabled(context: Context, intent: Intent) = Unit
}

/** 设备所有者状态与操作。 */
object DeviceOwner {

    /**
     * 当前设备所有者的包名，没有则为 null。
     *
     * 这里统一走反射：`getDeviceOwnerComponentOnAnyUser()` 是 `@TestApi`，
     * 编译期不可见；`deviceOwnerComponent` 属性要到 API 30 才公开。
     * 反射一次拿全版本，编译期也不依赖具体 SDK 常量。
     */
    fun ownerPackage(context: Context): String? {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return null
        return reflectOwnerComponent(dpm)?.packageName
    }

    /** 反射读取 DeviceOwner 组件名，兼容各版本的方法名差异。 */
    private fun reflectOwnerComponent(dpm: DevicePolicyManager): ComponentName? {
        val names = arrayOf(
            "getDeviceOwnerComponentOnAnyUser",
            "getDeviceOwnerComponent",
        )
        for (name in names) {
            val r = runCatching {
                val m = DevicePolicyManager::class.java.getMethod(name)
                m.isAccessible = true
                m.invoke(dpm) as? ComponentName
            }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    /** 本应用是否已成为设备所有者。 */
    fun isSelf(context: Context): Boolean =
        ownerPackage(context) == context.packageName

    /** 是否有任意设备所有者（可能是别的管理端）。 */
    fun hasAny(context: Context): Boolean = ownerPackage(context) != null

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, AdminReceiver::class.java)

    /** 是否已激活设备管理员（不一定有 owner 权限）。 */
    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return runCatching { dpm.isAdminActive(adminComponent(context)) }.getOrDefault(false)
    }

    /** 拉起系统的「激活设备管理员」页面。 */
    fun requestAdmin(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * 单独应用防卸载开关。
     *
     * @return true 表示设置成功
     */
    fun setUninstallBlocked(context: Context, packageName: String, blocked: Boolean): Boolean {
        if (!isSelf(context)) return false
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return runCatching {
            dpm.setUninstallBlocked(adminComponent(context), packageName, blocked)
            true
        }.getOrDefault(false)
    }

    fun isUninstallBlocked(context: Context, packageName: String): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return runCatching { dpm.isUninstallBlocked(adminComponent(context), packageName) }
            .getOrDefault(false)
    }

    /** 临时停用某个应用（账号类应用的临时降级）。 */
    fun setSuspended(context: Context, packageName: String, suspended: Boolean): Boolean {
        if (!isSelf(context)) return false
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setPackagesSuspended(adminComponent(context), arrayOf(packageName), suspended)
                true
            } else false
        }.getOrDefault(false)
    }

    /** 静默安装已存在于系统的包（降级 / 恢复用）。 */
    fun installExisting(context: Context, packageName: String): Boolean {
        if (!isSelf(context)) return false
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return runCatching {
            // 高版本 SDK 上这个方法已改为返回 Boolean（成功即 true）
            @Suppress("DEPRECATION")
            dpm.installExistingPackage(adminComponent(context), packageName)
        }.getOrDefault(false)
    }

    /** 允许 / 禁止安装未知来源（owner 权限下无需用户操作）。 */
    fun setUninstallBlockedAll(context: Context, blocked: Boolean): Int {
        if (!isSelf(context)) return 0
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return 0
        val admin = adminComponent(context)
        val pkgs = runCatching { context.packageManager.getInstalledPackages(0) }
            .getOrDefault(emptyList())
            .mapNotNull { it.packageName }
            .filter { it != context.packageName }
        var count = 0
        for (p in pkgs) {
            if (runCatching { dpm.setUninstallBlocked(admin, p, blocked) }.isSuccess) count++
        }
        // 全量结果变了，让列表下次重新问一次
        invalidateCache()
        return count
    }

    /**
     * 防卸载状态缓存。
     *
     * 早前的实现对**每个**已安装包各调一次 `isUninstallBlocked`，
     * 每个调用都是一次跨进程 binder，几百个应用的机器上会在主线程之外
     * 堆出上百次 IPC——这正是「用一半突然没了」的主要来源之一。
     *
     * 现在整个查询只做一次，结果缓存住；只有本应用主动改过、或设备重启
     * 之后才失效重查。
     */
    @Volatile
    private var blockedCache: Set<String>? = null

    fun invalidateCache() {
        blockedCache = null
    }

    /** 当前所有受防卸载保护的包（带缓存，可安全在 IO 线程调用）。 */
    fun blockedPackages(context: Context): List<String> {
        val cached = blockedCache
        if (cached != null) return cached.toList()
        if (!isSelf(context)) {
            blockedCache = emptySet()
            return emptyList()
        }
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return emptyList()
        val admin = adminComponent(context)
        val pkgs = runCatching { context.packageManager.getInstalledPackages(0) }
            .getOrDefault(emptyList())
            .mapNotNull { it.packageName }
        val out = LinkedHashSet<String>()
        for (p in pkgs) {
            // 单包查询失败只跳过这一条，不能让整页崩掉
            val blocked = runCatching { dpm.isUninstallBlocked(admin, p) }.getOrDefault(false)
            if (blocked) out += p
        }
        blockedCache = out
        return out.toList()
    }
}
