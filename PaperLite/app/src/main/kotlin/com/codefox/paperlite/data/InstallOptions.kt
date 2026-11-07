package com.codefox.paperlite.data

/**
 * 安装选项。
 *
 * 对齐 **InstallerX** 的 `config_*` 系列开关（见它的 `values-zh-rCN/strings.xml`）：
 * 允许降级、允许测试包、多用户安装、安装成功后自动删除、声明安装者（安装来源）。
 *
 * 这些不是装饰——它们会真的改写 `pm install` 的命令行参数。
 */
data class InstallOptions(
    /** 允许降级安装低版本（`-d`）。 */
    val allowDowngrade: Boolean = false,
    /** 允许安装带 TestOnly 标记的包（`-t`）。 */
    val allowTestOnly: Boolean = false,
    /** 多用户安装（`--user all`）。 */
    val allUsers: Boolean = false,
    /** 安装成功后自动删除安装包。 */
    val autoDelete: Boolean = false,
    /** 声明安装者（安装来源）包名（`-i`）。 */
    val installerPackage: String = "",
    /** 安装方式。 */
    val installMode: InstallMode = InstallMode.Global,
) {
    /**
     * 把选项翻译成 `pm install` 的附加参数。
     *
     * 注意 `-i`（声明安装者）需要 INSTALL_PACKAGES 权限，
     * shell（uid 2000）本身就有，普通应用没有。
     */
    fun pmFlags(): String = buildString {
        append(" -r")
        if (allowDowngrade) append(" -d")
        if (allowTestOnly) append(" -t")
        if (allUsers) append(" --user all")
        if (installerPackage.isNotBlank()) {
            append(" -i '").append(installerPackage.replace("'", "")).append("'")
        }
    }.trim()

    companion object {
        val DEFAULT = InstallOptions()
    }
}

/**
 * 安装方式。
 *
 * 对齐 InstallerX 的 `config_install_mode_*`：
 * 跟随全局 / 对话框 / 通知栏 / 对话框自动 / 通知栏自动 / 忽略。
 */
enum class InstallMode(val value: String, val title: String, val desc: String) {
    Global("global", "跟随全局", "用当前授权器的默认行为"),
    Dialog("dialog", "对话框", "安装前弹出确认对话框"),
    Notification("notification", "通知栏", "在通知栏里确认安装"),
    DialogAuto("dialog_auto", "对话框自动", "对话框显示后自动确认"),
    NotificationAuto("notification_auto", "通知栏自动", "通知栏显示后自动确认"),
    Ignore("ignore", "忽略", "不提示，直接安装"),
    ;

    companion object {
        fun of(raw: String?): InstallMode = entries.firstOrNull { it.value == raw } ?: Global
    }
}

/**
 * 安装失败原因的中文对照。
 *
 * 直接取自 InstallerX 的 `exception_install_failed_*` 文案——
 * 它把 `PackageInstaller` 的每一个 `INSTALL_FAILED_*` 常量都翻译了，
 * 这比让用户盯着 `INSTALL_FAILED_VERSION_DOWNGRADE` 强得多。
 */
object InstallErrors {

    /** 错误码 → 中文说明。 */
    private val TABLE: Map<String, String> = mapOf(
        "INSTALL_FAILED_ALREADY_EXISTS" to "应用已安装（包名相同、版本相同），请先卸载再安装",
        "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE" to "CPU 不受支持，请下载支持当前 CPU 的安装包",
        "INSTALL_FAILED_DUPLICATE_PACKAGE" to "签名冲突（包名相同、签名不同），请先卸载原应用",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" to "存储空间不足，请先清理空间或卸载无用应用",
        "INSTALL_FAILED_INVALID_APK" to "无效的 APK（安装包已损坏），请重新下载安装包",
        "INSTALL_FAILED_INVALID_INSTALL_LOCATION" to "无效的安装路径",
        "INSTALL_FAILED_MISSING_FEATURE" to "安装包使用了一个无效特性",
        "INSTALL_FAILED_NEWER_SDK" to "系统版本过新，安装包不支持当前系统",
        "INSTALL_FAILED_NO_SHARED_USER" to "共享 UID 的程序不存在，请先下载主 UID 应用",
        "INSTALL_FAILED_PACKAGE_CHANGED" to "调用程序预期调用的包装改变了",
        "INSTALL_FAILED_TEST_ONLY" to "用于测试的安装包，请打开「允许测试包」",
        "INSTALL_FAILED_UID_CHANGED" to "UID 发生改变，请先卸载后再安装",
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to "更新失败（签名或其它原因），请卸载后安装",
        "INSTALL_FAILED_VERIFICATION_FAILURE" to "无法安装，因为验证安装包失败",
        "INSTALL_FAILED_VERIFICATION_TIMEOUT" to "无法安装，因为验证安装包超时",
        "INSTALL_FAILED_VERSION_DOWNGRADE" to "应用降级失败，请先卸载后再安装",
        "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE" to "与已安装应用的共享 UID 签名不一致",
        "INSTALL_FAILED_CONFLICTING_PROVIDER" to "与已安装应用的 Provider 冲突",
        "INSTALL_FAILED_INVALID_URI" to "安装包路径无效",
    )

    /**
     * 从 `pm install` 的输出里解析出可读原因。
     *
     * `pm install` 失败时输出形如
     * `Failure [INSTALL_FAILED_VERSION_DOWNGRADE: ...]`
     * 或 `Failure [INSTALL_FAILED_ALREADY_EXISTS]`。
     */
    fun explain(output: String): String? {
        if (output.isBlank()) return null
        val hit = TABLE.keys.firstOrNull { output.contains(it) } ?: return null
        val extra = output.substringAfter(hit, "")
            .trimStart(':', ' ', '[')
            .trimEnd(']', ' ')
            .trim()
        val base = TABLE.getValue(hit)
        return if (extra.isNotEmpty()) "$base（$extra）" else base
    }
}
