# PaperLite PC

电脑端辅助软件。手机上的 PaperLite 负责**在设备本地**提权、装应用、起 Shizuku；
电脑端负责「坐在电脑前更顺手」的那部分：

- 设备管理：数据线即插即用，无线调试配对 / 连接 / 断开，局域网服务扫描
- 安装：拖一个 APK 进来直接 `adb install -r`，可选授予权限、允许降级
- 推送：把任意文件推到手机 `/sdcard/Download`
- 终端：任意 `adb shell` 命令，原文回显，带历史记录
- 截屏：`exec-out screencap -p`，支持每 3 秒自动刷新
- 应用：列出第三方应用，一键 `adb uninstall`
- 日志：按行数拉取 logcat，不跟流

## 运行要求

- Windows 7+ / macOS / Linux（Windows 上体验最好）
- Python 3.9+，**只用标准库，不需要 pip 装任何东西**
- adb（platform-tools），三个来源任选：
  1. 已装 Android SDK / Android Studio，会自动找到；
  2. 把 `platform-tools` 文件夹整个拷到本目录下（`desktop/platform-tools/adb.exe`）；
  3. adb 在 PATH 里也行。

## 启动

双击 **`启动 PaperLite PC.bat`**（Windows）。

```
python paperlite_pc.py          # 其他系统
PAPERLITE_PC_PORT=17321 python paperlite_pc.py   # 自定义端口
```

启动后会自动用 Edge / Chrome 的「应用窗口」打开界面——没有地址栏，
关掉窗口就是退出；也可以在界面右上角点「退出」。

界面只监听 `127.0.0.1`，不对外开端口，不联网，不上报任何数据。

## 结构

```
desktop/
├── paperlite_pc.py        # 后端：adb 包装 + 本地 HTTP + App 窗口
├── ui/index.html          # 前端：单文件界面（Material 风格，深浅色）
└── 启动 PaperLite PC.bat  # Windows 启动器
```
