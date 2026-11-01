# PaperLite G1
# ProGuard 规则（当前 release 未开启 minify，保留占位）
-keep class top.yukonga.miuix.** { *; }
-keep class com.paperlite.g1.data.** { *; }

# 安装代理（app_process 入口）
# 这个类由 `app_process` 通过 CLASSPATH 以字符串方式加载，
# 类名与方法名一旦被混淆，app_process 就找不到入口，安装会直接失效。
-keep class com.codefox.paperlite.core.install.InstallAgent { *; }
-keep class com.codefox.paperlite.core.install.InstallAgent$* { *; }
