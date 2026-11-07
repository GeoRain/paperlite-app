# SU 管理器 APK 需求清单

PaperLite 现在要当 root 管理器，需要能对接不同 SU 管理器的实现。
下面是我**真正需要的文件**，按优先级排。

## 一、必须：带 `libksud.so` 的 KernelSU 系管理器

判据是 APK 里有 `lib/<abi>/libksud.so`——PaperLite 的一键 root
（DirtyFrag）和 su 代理都依赖它。

| 优先级 | 应用 | 包名 | 要什么 |
| --- | --- | --- | --- |
| P0 | **SukiSU Ultra** | `com.sukisu.ultra` | 你已经给过（在 Downloads 里） |
| P0 | **KernelSU**（官方） | `me.weishu.kernelsu` | 官方版，用于对齐原始实现 |
| P1 | **KernelSU-Next** | `me.weishu.kernelsu.next` | 社区维护的活跃分支 |
| P1 | **KSUNext** | `com.rifsxd.ksunext` | 另一个活跃分支 |
| P2 | **KowSU** | `com.kowx712.kernel` | |
| P2 | **KowSU Next** | `com.kowx712.kernel.next` | |

> 只要 **release 版本的通用 APK**（arm64-v8a 打包的那种），
> 不需要 GKI / 特定内核的专用包。

## 二、建议：APatch

| 优先级 | 应用 | 包名 | 要什么 |
| --- | --- | --- | --- |
| P2 | **APatch** | `me.bmax.apatch` | 它不是 KernelSU 系，`libksud.so` 那条路走不通，<br>但它的 su 实现与授权模型不同，值得单独适配 |

## 三、可选：Magisk（做对照与兼容）

| 优先级 | 应用 | 包名 | 要什么 |
| --- | --- | --- | --- |
| P3 | **Magisk** | `com.topjohnwu.magisk` | 它没有 `libksud.so`，su 是 `/data/adb/magisk/su`。<br>PaperLite 已把 Magisk 的路径写进 root 探测，但**没做适配** |

## 四、给我之后我会做什么

1. **反编译**每个 APK，抽出 `libksud.so` 与授权名单（`.allowlist`）的读写实现
2. 把授权名单的二进制格式统一成 PaperLite 自己的格式，
   实现 **KernelSU ↔ PaperLite 双向导入导出**
3. su 代理（`SuAgent`）改成**能对接多种框架**：识别目标框架 → 用对应协议
4. 「超级用户」页的授权名单支持**同时显示多个框架的名单**，并能切换管理目标

## 五、不需要的

- 不用给 **Root 授权器类**应用（KernelSU 授权器、授权管理类）
- 不用给 **Shizuku**（已经能通过无线调试拿到等价身份）
- 不用给 **Magisk 的 magiskinit / busybox**（除非要做 Magisk 的模块兼容）

## 六、怎么给

直接把 APK 丢到 `C:\Users\Administrator\Downloads\` 就行，
我会自己扫这个目录。反编译产物我会放在
`G:\workbuddysworker\2026-11-01-16-19-57\_apk_analysis\`。

如果某个版本在官方渠道下不到，也可以给我**安装包**（从应用市场下的），
我会从里面提取 so。
