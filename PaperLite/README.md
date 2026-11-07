# PaperLite

设备管理 / 提权工具。**小米蓝**主色，两套可切换的界面风格：
**澎湃风格**（MIUI 观感）与 **Material 3**（Google 规范）。

## 界面风格

在「设置 → 常规 → 外观」里切换，**即时生效，无需重启**。
切换只换视觉语言，功能完全一致。

| | 澎湃风格 | Material 3 |
| --- | --- | --- |
| 卡片 | 大圆角、无描边、纯色底 | 描边 + 色调面分层 |
| 按钮 | 胶囊形 | 圆角 20dp + 涟漪 |
| 开关 | Miuix 开关 | M3 开关 |
| 分段选择器 | 滑动胶囊 | M3 SegmentedButton |
| 对话框 | 自绘面板 | M3 AlertDialog |
| 动作面板 | 自绘底部面板 | M3 ModalBottomSheet |
| 侧边栏 | Miuix NavigationRail | M3 NavigationRail（药丸指示器） |
| 排版 | 澎湃字阶 | M3 Typography 字阶 |

实现方式：组件层统一走 `ui/components/Skin.kt` 的 `Skin*` 组件，
页面代码不感知风格，这样新增页面不用写两遍。

## 动效

所有过渡动画的时长与曲线统一收口在 `ui/theme/Motion.kt`：

- **切页**：淡入 + 1/12 屏宽横向位移（300ms）
- **分组切换**：淡入 + 14dp 纵向位移（300ms）
- **列表项增删**：`animateItem` 淡入淡出 + 位移
- **展开/收起**：高度动画 + 淡入淡出（260ms）
- **汉堡菜单**：自绘 Popup，弹出=淡入+缩放+下滑，收起=淡出+缩回+上收
- **按下反馈**：按钮缩放、入口行右移

## 构建

```bash
./build.sh :app:assembleDebug     # 产物：app/build/outputs/apk/debug/app-debug.apk
```

JDK / Android SDK / Gradle 都在 `E:\pl_tools` 下，`build.sh` 会自己固定环境变量。

## 版本号

**每次更新版本号 +0.1**（1.0 → 1.1 → 1.2 …），同时 `versionCode` +1。

交付新版本前跑一次：

```bash
./bump.sh          # 自动 +0.1
./bump.sh 2.3      # 或直接指定
```

改的是 `app/build.gradle.kts` 里的 `versionCode` / `versionName`，
应用内「设置 → 关于」显示的版本取自 `BuildConfig.VERSION_NAME`，会自动跟着变。

## 权限模型

| 身份 | 取得方式 | 能做什么 |
| --- | --- | --- |
| 设备所有者 | root 后自动 `dpm set-device-owner`，失败则拉起系统激活页 | 静默安装、单独应用防卸载、临时停用 |
| Root | DirtyFrag 一键 root（临时，重启失效）或设备自带 | 全部 shell 能力 |
| adb shell | 无线调试配对（应用内三步向导） | pm install、停用应用、执行命令 |
| 作业帮系统服务 | 作业帮 / AIbangOS ROM 自带 | 无声安装，绕过 PadMS 拦截 |

## 目录

```
app/src/main/kotlin/com/codefox/paperlite/
  core/      通道、安装器、root / Shizuku / 作业帮接口
  data/      设置、应用条目、安装选项
  ui/        页面与组件（StatusHero 是 SukiSU 形态的信息卡）
app/src/main/dfroot/   DirtyFrag 载荷（GPL-2.0，见其中的说明文件）
```
