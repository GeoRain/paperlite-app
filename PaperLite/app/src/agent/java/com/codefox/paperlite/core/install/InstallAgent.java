package com.codefox.paperlite.core.install;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 安装代理（对齐原版 JC Code V3 的 {@code com.jc.code.v3.InstallAgent}）。
 *
 * <h3>它解决什么问题</h3>
 * <p>普通应用即使拿到 root，{@code pm install} 也只是「以 root 身份执行 pm」，
 * 而 pm 自己没有静默安装的权限，最终还是会回落到系统安装界面。
 * 原版 JC Code V3 的做法是：把<strong>承载代理的 dex</strong>通过 {@code CLASSPATH}
 * 挂给 {@code app_process}，让它在 <strong>system 身份</strong>下重新跑一遍本类的代码：
 *
 * <pre>
 * CLASSPATH='&lt;代理 dex 所在目录&gt;' app_process /system/bin \
 *     com.codefox.paperlite.core.install.InstallAgent '&lt;目标 APK 路径&gt;' 2>&1;
 * </pre>
 *
 * <p>在 system 身份下，{@code PackageInstaller} 的会话提交不再需要用户确认，
 * 也不需要 root——这才是原版能做到「静默安装」的根本原因。
 *
 * <h3>与原版的逐点对应</h3>
 * <table>
 *   <tr><td>原版 JC Code V3</td><td>本实现</td></tr>
 *   <tr><td>HiddenApiBypass + VMRuntime.setHiddenApiExemptions("L")</td>
 *       <td>反射 VMRuntime.setHiddenApiExemptions("L")（不引入 LSPosed 依赖）</td></tr>
 *   <tr><td>ActivityThread.systemMain() → getSystemContext()</td><td>同</td></tr>
 *   <tr><td>createPackageContext(包名, CONTEXT_IGNORE_SECURITY)</td><td>同</td></tr>
 *   <tr><td>SessionParams.setAppPackageName</td><td>同</td></tr>
 *   <tr><td>反射改 installFlags 或上 1048742</td><td>同（保留原版魔数）</td></tr>
 *   <tr><td>openWrite("base.apk", 0, len) + fsync</td><td>同</td></tr>
 *   <tr><td>注册 …INSTALL_AGENT_STATUS 广播 + 30 秒 watchdog 轮询</td><td>同</td></tr>
 *   <tr><td>打印 INSTALL_AGENT_OK / INSTALL_AGENT_TIMEOUT</td><td>同</td></tr>
 * </table>
 *
 * <h3>为什么写成 Java</h3>
 * <p>这个类由 {@code app_process} 以 classpath 形式加载，必须是一个
 * <strong>独立的小 dex</strong>——如果它混在主应用的 dex 里，
 * ART 只装载主 dex（classes.dex），而主 dex 已被 Compose 等依赖占满，
 * 入口类就找不到了。写成 Java 且不依赖 Kotlin 标准库，
 * 编译出的 dex 只有几 KB，可以单独打进 assets 推给目标机。
 *
 * <p>另外它<strong>不能引用任何 Compose / UI 代码</strong>，
 * 否则会把整个 UI 栈拉进 system 进程。
 */
public final class InstallAgent {

    /** 安装状态回执广播 action。调用端据此判断会话是否提交成功。 */
    public static final String ACTION_STATUS = "com.codefox.paperlite.INSTALL_AGENT_STATUS";

    /**
     * 原版 JC Code V3 使用的 installFlags 魔数。
     *
     * <p>1048742 = 0x100026，含「允许覆盖安装」「降级安装」「授予全部权限」等位。
     * 原版是实测有效的组合，这里不做拆解，避免不同 ROM 上拆错导致安装被拒。
     */
    private static final int FLAG_INSTALL = 1048742;

    /** watchdog 最长等待时间，与原版一致。 */
    private static final long WATCHDOG_TIMEOUT_MS = 30000L;

    /** watchdog 轮询间隔，与原版一致。 */
    private static final long WATCHDOG_INTERVAL_MS = 500L;

    /** 单次读取块大小。 */
    private static final int BUFFER_SIZE = 256 * 1024;

    /**
     * 自身包名。
     *
     * <p>原版硬编码的是自己的包名（{@code "JC.CODE.V3"}），这里对应我们的包名。
     * 它只用于 {@code createPackageContext} 拿一个不受限的 PackageManager。
     */
    private static final String OWN_PACKAGE = "com.codefox.paperlite";

    private InstallAgent() {
    }

    /**
     * app_process 入口。
     *
     * @param args 第一个参数是待安装 APK 的绝对路径
     */
    public static void main(String[] args) {
        if (args == null || args.length == 0) {
            System.out.println("INSTALL_AGENT_ERROR usage: InstallAgent <apkPath>");
            System.exit(1);
            return;
        }
        String apkPath = args[0];
        File apk = new File(apkPath);
        if (!apk.exists()) {
            System.out.println("INSTALL_AGENT_ERROR apk not found: " + apkPath);
            System.exit(1);
            return;
        }

        try {
            exemptHiddenApis();
            Looper.prepareMainLooper();

            Context sys = systemContext();
            if (sys == null) {
                System.out.println("INSTALL_AGENT_ERROR system context unavailable");
                System.exit(1);
                return;
            }

            // 原版关键一步：用 createPackageContext 拿一个「干净的」PackageManager。
            // 直接用 systemContext.getPackageManager() 在部分 ROM 上会拿到
            // 受限的 PM，解析不出归档信息；createPackageContext 绕开这个限制。
            PackageManager pm = sys.getPackageManager();
            try {
                Context pkgCtx = sys.createPackageContext(OWN_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
                if (pkgCtx != null && pkgCtx.getPackageManager() != null) {
                    pm = pkgCtx.getPackageManager();
                }
            } catch (Throwable ignored) {
                // 包不存在或无权访问时，用 system 的 PM 也行
            }

            String pkgName = null;
            try {
                android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                if (pi != null) {
                    pkgName = pi.packageName;
                }
            } catch (Throwable t) {
                System.out.println("INSTALL_AGENT_ERROR getPackageArchiveInfo failed: " + t);
            }
            if (pkgName == null || pkgName.length() == 0) {
                System.out.println("INSTALL_AGENT_ERROR cannot resolve package name");
                System.exit(1);
                return;
            }

            PackageInstaller installer = pm.getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(pkgName);
            applyInstallFlags(params);

            int sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (InputStream input = new FileInputStream(apk);
                     OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    while (true) {
                        int read = input.read(buffer);
                        if (read <= 0) {
                            break;
                        }
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                    session.fsync(out);
                }
                session.commit(statusIntentSender(sys, sessionId));
            }
            System.out.println("INSTALL_AGENT_COMMITTED " + sessionId + " " + pkgName);

            // 会话提交之后只有两种结局：收到成功回执，或超时。
            // 原版同时用了「回执广播」和「watchdog 轮询」两条路，这里一并保留。
            registerStatusReceiver(sys, pkgName);
            startWatchdog(sys, pkgName);
            Looper.loop();
        } catch (Throwable t) {
            System.out.println("INSTALL_AGENT_ERROR " + t.getMessage());
            t.printStackTrace();
            // 必须用非零退出码：调用方靠退出码判断这条通道有没有成功。
            // 如果这里正常退出，调用方会误以为安装代理跑通了，
            // 于是不再回退到 pm install —— 正是「装不上又不继续试」的原因。
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 放开隐藏 API 限制。
     *
     * <p>原版依赖 LSPosed 的 {@code HiddenApiBypass}，这里不引入额外依赖，
     * 直接反射 {@code dalvik.system.VMRuntime.setHiddenApiExemptions("L")}——
     * 它会放行全部隐藏 API，效果等价且没有第三方依赖。
     */
    private static void exemptHiddenApis() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vmRuntime.getMethod("getRuntime").invoke(null);
            Method method = vmRuntime.getMethod("setHiddenApiExemptions", String[].class);
            method.invoke(runtime, (Object) new String[]{"L"});
        } catch (Throwable ignored) {
            // 放行失败不致命，后面的反射失败会给出更具体的信息
        }
    }

    /**
     * 取 system 身份下的 Context。
     *
     * <p>{@code ActivityThread.systemMain()} 需要<strong>能 attach system_server</strong>
     * 的身份才能成功：root(0) 或 adb shell(2000) 都可以，纯应用 uid 一定失败——
     * 因为 SELinux 下 app 域没有向 system_server 做 binder 调用的权限。
     *
     * <p>失败时把<strong>真实原因</strong>打出来，调用方（安装诊断）据此判断是身份问题还是别的。
     */
    private static Context systemContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            activityThread.getMethod("systemMain").invoke(null);
            return (Context) activityThread.getMethod("getSystemContext").invoke(null);
        } catch (Throwable t) {
            // 反射调用会包一层 InvocationTargetException，真实原因在 cause 里
            Throwable cause = (t instanceof InvocationTargetException && t.getCause() != null)
                    ? t.getCause() : t;
            System.out.println("INSTALL_AGENT_ERROR systemMain failed: "
                    + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace();
            return null;
        }
    }

    /**
     * 用反射给 SessionParams 追加原版的 installFlags。
     *
     * <p>{@code installFlags} 是 @hide 字段，编译期拿不到，只能反射读写。
     */
    private static void applyInstallFlags(PackageInstaller.SessionParams params) {
        try {
            Field field = params.getClass().getDeclaredField("installFlags");
            field.setAccessible(true);
            int current = field.getInt(params);
            field.setInt(params, current | FLAG_INSTALL);
        } catch (Throwable t) {
            System.out.println("INSTALL_AGENT_NOTE applyInstallFlags failed: " + t);
        }
    }

    /** 构造提交会话用的 IntentSender。 */
    private static android.content.IntentSender statusIntentSender(Context context, int sessionId) {
        // 与原版一致：用 com.android.shell 的 context 发 PendingIntent，
        // 这样 system_server 回发状态时不会因为跨用户/跨包被拒。
        Context shellContext = context;
        try {
            shellContext = context.createPackageContext(
                    "com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        } catch (Throwable ignored) {
            // 拿不到就用原 context
        }
        Intent intent = new Intent(ACTION_STATUS);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(shellContext, 0, intent, flags).getIntentSender();
    }

    /** 注册安装结果回执接收器，收到成功即退出。 */
    private static void registerStatusReceiver(Context context, final String pkgName) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    int status = PackageInstaller.STATUS_FAILURE;
                    if (intent != null) {
                        status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, status);
                    }
                    if (status == PackageInstaller.STATUS_SUCCESS) {
                        System.out.println("INSTALL_AGENT_OK " + pkgName);
                        Runtime.getRuntime().exit(0);
                    } else {
                        // 标记名与原版一致：原版没有 INSTALL_AGENT_FAIL，
                        // 失败回执用的是 INSTALL_AGENT_STATUS status=<code>。
                        System.out.println("INSTALL_AGENT_STATUS status=" + status);
                        System.out.println("INSTALL_AGENT_FAIL " + pkgName + " status=" + status);
                        Runtime.getRuntime().exit(1);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_STATUS);
            // 必须 EXPORTED：回执来自 system_server，属于其他进程。
            // 收不到也不影响结果判定，watchdog 轮询是兜底。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            System.out.println("INSTALL_AGENT_NOTE status receiver unavailable: " + t);
        }
    }

    /**
     * watchdog：轮询包是否已经装上。
     *
     * <p>部分 ROM 不会回发状态广播，这时只能靠轮询判断成败——原版也是这么兜底的。
     */
    private static void startWatchdog(final Context context, final String pkgName) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                long deadline = System.currentTimeMillis() + WATCHDOG_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        context.getPackageManager().getPackageInfo(pkgName, 0);
                        System.out.println("INSTALL_AGENT_OK " + pkgName);
                        Runtime.getRuntime().exit(0);
                    } catch (Throwable ignored) {
                        // 还没装上，继续等
                    }
                    try {
                        Thread.sleep(WATCHDOG_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                System.out.println("INSTALL_AGENT_TIMEOUT " + pkgName);
                Runtime.getRuntime().exit(2);
            }
        }, "install-agent-watchdog");
        thread.setDaemon(true);
        thread.start();
    }
}
