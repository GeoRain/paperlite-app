# PaperLite

> 一个面向 Android 的应用管理与静默安装工具，对齐 JC Code V3 的安装机制。

<p align="center">
  <img src="assets/logo.svg" alt="PaperLite" width="120">
</p>

## 目录结构

```
PaperLite/      Android 应用源码（Kotlin + Compose + Miuix）
desktop/        电脑端辅助软件（Python 标准库，包一层 adb）
docs/           官网（GitHub Pages：https://georain.github.io/paperlite/）
```

## 功能

- **静默安装**：`app_process` 安装代理，在 system 身份下提交 PackageInstaller 会话，真正无确认框
- **多通道调度**：Root / 设备所有者 / 无线调试 / 本地回环 / 本地 ADB 直连，按优先级自动回退
- **应用管理**：安装、卸载、停用、启用、应用列表与详情
- **本地公告**：内置公告 + 可选远程 JSON 源，离线可用
- **终端**：在设备上直接执行 shell 命令
- **超级用户**：对齐 SukiSU 的管理面，ksud 随包内置，一键 DirtyFrag 临时 Root；进门有风险确认
- **Shizuku**：启动器与 rish 随包内置，不要求另装 Shizuku
- **应用商店**：解析 F-Droid 镜像索引，真实图标展示，可静默安装（第三方源内容未经审查，有明确提示）
- **外观**：澎湃 / Material 3 双皮肤即时切换，莫奈取色 + 应用内壁纸
- **电脑端**：PaperLite PC 辅助软件（连设备、装包、终端、截图，只监听 127.0.0.1）
- **官网**：静态站点，含公告页、电脑端介绍与 APK 下载

## 安装机制（本项目与 JC Code V3 的对应关系）

JC Code V3 的静默安装靠把自身 dex 挂给 `app_process`，让代码在 **system 身份**下重跑一遍：

```
CLASSPATH='<APK 路径>' app_process /system/bin com.jc.code.v3.InstallAgent '<目标 APK>'
```

在 system 身份下，`PackageInstaller` 的会话提交不再需要用户确认。

本项目对这条链路做了两处必要调整（**机制等价**）：

| 项 | JC Code V3 | PaperLite | 原因 |
| --- | --- | --- | --- |
| CLASSPATH 载体 | 自身 APK（内含 `classes.dex` 的 zip） | 单独编译的 9.5KB dex，打成 zip（entry 名 `classes.dex`）后推送到 `/data/local/tmp` | 本项目主 dex 已被 Compose/Miuix 占满，安装代理进不了主 dex，而 `app_process` 只装载主 dex |
| 入口类 | `com.jc.code.v3.InstallAgent` | `com.codefox.paperlite.core.install.InstallAgent` | 包名不同 |

安装代理本体位于 `PaperLite/app/src/agent/java/`，由 `app/build.gradle.kts` 里的
`dexAgent` 任务用 SDK 自带的 `d8` 单独编译，打进 `assets/agent/classes.dex`。

### 身份要求

`app_process` 必须在 **root(0)** 或 **adb shell(2000)** 身份下运行——纯应用 uid
会被 SELinux 拦掉对 system_server 的 binder 调用。因此 `execAsShell()` 刻意
**不做「本进程」兜底**，只走 root、无线调试、本地回环、本地 ADB 直连四条通道。

### 已知陷阱

- **不能用 `cat` 推送代理文件**：shell 在 Android 11+ 读不到 `/data/user/0/<pkg>/cache`，
  而 `>` 重定向会先建出空文件，`ls` 照样能看到文件名——推过去的是空 dex，
  最终表现为 `ClassNotFoundException`，极难排查。实现改用 base64 分块推送并校验字节数。
- **代理失败必须非零退出**：否则调用方会误判这条通道成功，从而不再回退到 `pm install`。

## 构建

需要 JDK 17+、Android SDK（compileSdk 37）。

```bash
cd PaperLite

# 配置 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 构建
./gradlew :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

## 技术栈

- Kotlin 2.4.20 / AGP 9.4.1
- Jetpack Compose + Miuix
- kotlinx.coroutines / kotlinx.serialization

## 许可

仅供学习研究使用。
