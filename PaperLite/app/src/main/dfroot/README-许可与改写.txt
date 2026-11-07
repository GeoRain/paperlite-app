DirtyFrag 载荷来源与许可
========================

本目录下的两个二进制文件来自 DFRoot（DirtyFrag，CVE-2026-43284）：

    https://github.com/diabl0w/DFRoot
    取自 release v4.7 的 DFRoot_4.7.apk：
        lib/arm64-v8a/libdfroot.so     → libdfroot.so
        lib/arm64-v8a/libbootstrap.so  → libbootstrap.so

许可：DFRoot 采用 **GPL-2.0**。把这两个二进制打进 PaperLite 的 APK，
意味着 PaperLite 本身也需要以 GPL-2.0 兼容的方式分发。
（PaperLite 目前的 README 只写了「仅供学习研究使用」，未指定许可证。）

若要把 PaperLite 作为非 GPL 的产品发布，请：
  1) 把本目录的两个文件从构建中排除（删掉 app/build.gradle.kts 里的
     patchDfrootAssets 任务，并移除 app/src/main/dfroot 目录），或
  2) 把 PaperLite 整体按 GPL-2.0 发布。

路径改写
--------
这两个文件里把作者自己的应用目录写死了：

    /data/user_de/0/df.root/bootstrap
    /data/user_de/0/df.root/ksud
    /data/user_de/0/df.root/shared_prefs/dfroot.xml

本项目包名不同、槽位也放不下新路径，因此构建时（app/build.gradle.kts 的
patchDfrootAssets 任务）把它们改写到 /data/local/tmp/ 下：

    /data/local/tmp/dfroot_boot
    /data/local/tmp/dfroot_ksud
    /data/local/tmp/dfroot.xml

改写只动字符串，不改指令逻辑；每个 KMI 版本的 dfroot.ko 副本（共 8 处）
都做了替换。
