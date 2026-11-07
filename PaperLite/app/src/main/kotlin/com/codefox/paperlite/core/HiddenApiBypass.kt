package com.codefox.paperlite.core

import android.os.Build
import java.lang.reflect.Method

/**
 * Hidden API 绕过。
 *
 * 抄自 InstallerX 的 Birchua 授权器（`zyb.installerx.api`）里的实现，
 * 那边是三级降级，本项目照搬同样的顺序与判定条件。
 *
 * 为什么需要：`android.os.ZybosSysApiManager` 是系统内部类，
 * Android 9 起的 hidden API 限制会让反射直接抛 `NoSuchMethodException`，
 * 于是「明明作业帮设备上有这个服务，却调不动」。
 *
 * 三级降级：
 * 1. **LSPosed HiddenApiBypass**：装了 Xposed 模块的设备最稳；
 * 2. **VMRuntime.setHiddenApiExemptions**：Android 9+ 官方留下的口子；
 * 3. **双反射拿 getDeclaredMethodsUnchecked**：连 `Class.getDeclaredMethod`
 *    本身都被拦时的兜底。
 *
 * 三级全失败也只是拿不到豁免，不该让整条安装链路崩掉——
 * 所以这里返回布尔值而不是抛异常。
 */
object HiddenApiBypass {

    private var done = false

    /** 是否已经尝试过一次绕过。 */
    val attempted: Boolean get() = done

    /**
     * 尝试放行 hidden API。
     *
     * @return true 表示至少有一级成功（或本来就不需要绕过）
     */
    fun bypass(): Boolean {
        if (done) return true
        done = true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true

        // 方式 1：LSPosed HiddenApiBypass
        if (viaLsposed()) return true
        // 方式 2：VMRuntime
        if (viaVmRuntime()) return true
        // 方式 3：双反射
        return viaDoubleReflection()
    }

    private fun viaLsposed(): Boolean = runCatching {
        val clazz = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass")
        val method = clazz.getMethod("addHiddenApiExemptions", Array<String>::class.java)
        method.invoke(null, arrayOf("L"))
        true
    }.getOrDefault(false)

    private fun viaVmRuntime(): Boolean = runCatching {
        val vmr = Class.forName("dalvik.system.VMRuntime")
        val runtime = vmr.getMethod("getRuntime").invoke(null)
        val method = vmr.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
        method.invoke(runtime, arrayOf("L"))
        true
    }.getOrDefault(false)

    private fun viaDoubleReflection(): Boolean = runCatching {
        // Class.getDeclaredMethod 本身是 hidden API，
        // 用另一条反射路径把它捞出来，再拿真正的目标方法。
        val classClass = Class::class.java
        val getDeclaredMethod = classClass.getDeclaredMethod(
            "getDeclaredMethod",
            String::class.java,
            arrayOf<Class<*>>()::class.java,
        )
        getDeclaredMethod.isAccessible = true
        val target = getDeclaredMethod.invoke(
            null,
            "getDeclaredMethodsUnchecked",
            arrayOf<Class<*>>()::class.java,
        ) as? Method
        target?.isAccessible = true
        true
    }.getOrDefault(false)
}
