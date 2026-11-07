import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// 注意：不能写 java.util.Arrays —— Gradle 脚本里的 `java` 指的是 JavaPluginExtension
import java.util.Arrays

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // 应用商店要解析 F-Droid 索引，@Serializable 需要这个插件
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.codefox.paperlite"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.codefox.paperlite"
        // 设备所有者 / 无线调试配对 / 无障碍 都需要 29+
        minSdk = 29
        targetSdk = 37
        versionCode = 9
        versionName = "1.8"
    }

    androidResources {
        // 只保留中英文资源，减小包体
        localeFilters += listOf("zh", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 只需要 arm64 与 armeabi-v7a，减小体积
    splits {
        abi {
            isEnable = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}

/* ==========================================================================
   安装代理（app_process 入口）的独立 dex
   --------------------------------------------------------------------------
   `app_process` 用 `CLASSPATH=<apk>` 加载入口类时，ART 只装载**主 dex**
   （classes.dex）里的类。主应用的 classes.dex 已被 Compose / Miuix 占满
   （9000+ 类），安装代理无论怎么放都进不了主 dex。

   所以这里把代理单独编译成一个几 KB 的 dex，打进 `assets/agent/classes.dex`，
   运行时推到 /data/local/tmp，CLASSPATH 指向那个目录即可。

   效果上与原版 JC Code V3 等价（原版只有 2 个 dex，代理恰好在主 dex 里），
   但对本项目这种多 dex 体积要稳得多——app_process 只需加载几 KB，
   而不是背着 47MB 的主包去解析所有 dex。
   ========================================================================== */

val agentSrcDir = layout.projectDirectory.dir("src/agent/java")
val agentOutDir = layout.buildDirectory.dir("generated/agentDex")
val agentClassesDir = layout.buildDirectory.dir("agentClasses")

/** 找到本机 SDK 的 android.jar（代理只编译这一个文件，需要它做 classpath）。 */
fun findSdkDir(): String {
    val localProps = rootProject.file("local.properties")
    val fromProps = if (localProps.exists()) {
        val text = localProps.readText()
        Regex("sdk\\.dir=(.+)").find(text)?.groupValues?.get(1)?.trim()
            // properties 里的路径会转义反斜杠与冒号（E\:/pl_tools/sdk）
            ?.replace("\\:", ":")?.replace("\\\\", "\\")
    } else {
        null
    }
    return fromProps
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("找不到 Android SDK：请在 local.properties 里配置 sdk.dir")
}

fun findAndroidJar(): File {
    val sdkDir = findSdkDir()
    val platforms = File(sdkDir, "platforms")
    val best = platforms.listFiles()
        ?.filter { File(it, "android.jar").exists() }
        ?.maxByOrNull { it.name }
        ?: error("SDK 里没有 android.jar：$platforms")
    return File(best, "android.jar")
}

/** 找到 d8（SDK build-tools 自带）。 */
fun findD8(): Pair<String, String> {
    val sdkDir = findSdkDir()
    val buildTools = File(sdkDir, "build-tools")
    val best = buildTools.listFiles()
        ?.filter { File(it, "lib/d8.jar").exists() }
        ?.sortedByDescending { it.name }
        ?.firstOrNull()
        ?: error("SDK 里没有 d8：$buildTools")
    val javaHome = System.getenv("JAVA_HOME")
        ?: projectDir.resolve("../../pl_tools/jdk-21.0.13+9").absolutePath
    return File(best, "lib/d8.jar").absolutePath to javaHome
}

val compileAgentJava = tasks.register<JavaCompile>("compileAgentJava") {
    description = "编译安装代理（app_process 入口）"
    source(agentSrcDir)
    classpath = files(findAndroidJar())
    destinationDirectory.set(agentClassesDir)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-nowarn")
}

// 注意：这里注册的是普通任务，不是 Exec。
// 曾经写成 tasks.register<Exec>，但任务体只放在 doFirst 里、从未设置 commandLine，
// Gradle 会在执行阶段试图启动一个「command null」的进程并一直挂死（构建停在 :app:dexAgent 不动）。
val dexAgent = tasks.register("dexAgent") {
    description = "把安装代理打成独立 dex，并同步到 src/main/assets/agent/classes.dex"
    dependsOn(compileAgentJava)
    val (d8Jar, javaHome) = findD8()
    val out = agentOutDir.get().asFile
    val classesDir = agentClassesDir
    // 直接落到源集里：assets 走的是「普通资源目录」这条最稳的路，
    // 不再依赖 sourceSets 追加目录 + 任务依赖的时序，避免出现
    // 「dex 编出来了，但 APK 里没有 assets/agent/classes.dex」这种隐性失效
    val assetsDir = layout.projectDirectory.dir("src/main/assets/agent").asFile
    val assetDex = File(assetsDir, "classes.dex")
    inputs.dir(classesDir)
    outputs.dir(out)
    outputs.file(assetDex)
    doFirst {
        out.deleteRecursively()
        out.mkdirs()
        val classes = classesDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { it.absolutePath }
            .toList()
        if (classes.isEmpty()) error("没有编译出安装代理的 class 文件")
        val cmd = listOf(
            File(javaHome, "bin/java").absolutePath,
            "-cp", d8Jar,
            "com.android.tools.r8.D8",
            "--min-api", "29",
            "--lib", findAndroidJar().absolutePath,
            "--output", out.absolutePath,
        ) + classes
        providers.exec {
            workingDir = out
            environment("JAVA_HOME", javaHome)
            commandLine(cmd)
        }.result.get().assertNormalExitValue()
        // d8 输出的是 classes.dex
        val dexFile = File(out, "classes.dex")
        if (!dexFile.exists()) error("d8 没有产出 classes.dex")
        val agentDir = File(out, "agent")
        agentDir.mkdirs()
        dexFile.copyTo(File(agentDir, "classes.dex"), overwrite = true)
    }
    doLast {
        val produced = File(out, "agent/classes.dex")
        if (!produced.exists()) error("d8 没有产出 classes.dex")
        assetsDir.mkdirs()
        // 内容没变就不动文件，避免每次都触发 assets 重新合并
        val same = assetDex.exists() && assetDex.length() == produced.length() &&
            runCatching { assetDex.readBytes().contentEquals(produced.readBytes()) }.getOrDefault(false)
        if (!same) produced.copyTo(assetDex, overwrite = true)
    }
}

// 打包前一定先把代理 dex 放好
tasks.named("preBuild") {
    dependsOn(dexAgent)
}

/* ==========================================================================
   DirtyFrag 载荷（libdfroot.so / libbootstrap.so）的路径改写
   --------------------------------------------------------------------------
   这两个二进制来自 DFRoot（GPL-2.0），里面把作者自己的应用目录写死了：

       /data/user_de/0/df.root/bootstrap
       /data/user_de/0/df.root/ksud
       /data/user_de/0/df.root/shared_prefs/dfroot.xml

   本应用包名不同，且那些槽位放不下新路径，所以统一改写到
   /data/local/tmp/ 下（adb shell / root 可写，载荷以 uid 0 执行时可读）。

   原始文件放在 src/main/dfroot/（不改），改写后的产物落到
   src/main/assets/dfroot/，由 assets 源集直接打进 APK。
   ========================================================================== */

val dfrootSrcDir = layout.projectDirectory.dir("src/main/dfroot")
val dfrootAssetDir = layout.projectDirectory.dir("src/main/assets/dfroot")

val patchDfrootAssets = tasks.register("patchDfrootAssets") {
    description = "把 DirtyFrag 载荷里的硬编码路径改写到 /data/local/tmp"
    inputs.dir(dfrootSrcDir)
    outputs.dir(dfrootAssetDir)
    doLast {
        val outDir = dfrootAssetDir.asFile
        outDir.mkdirs()
        // 定长槽位替换表：原路径 → 新路径
        val replacements = listOf(
            "/data/user_de/0/df.root/bootstrap" to "/data/local/tmp/dfroot_boot",
            "/data/user_de/0/df.root/ksud" to "/data/local/tmp/dfroot_ksud",
            "/data/user_de/0/df.root/shared_prefs/dfroot.xml" to "/data/local/tmp/dfroot.xml",
        )
        listOf("libdfroot.so", "libbootstrap.so").forEach { name ->
            val src = dfrootSrcDir.file(name).asFile
            if (!src.exists()) {
                error("缺少 DirtyFrag 载荷：$src")
            }
            val bytes = src.readBytes()
            val patched = bytes.copyOf()
            replacements.forEach { (from, to) ->
                val srcBytes = from.toByteArray(Charsets.US_ASCII)
                val dstBytes = to.toByteArray(Charsets.US_ASCII)
                require(dstBytes.size <= srcBytes.size) { "替换路径过长：$to" }
                var i = 0
                while (i >= 0 && i <= patched.size - srcBytes.size) {
                    var hit = true
                    for (j in srcBytes.indices) {
                        if (patched[i + j] != srcBytes[j]) { hit = false; break }
                    }
                    if (hit) {
                        Arrays.fill(patched, i, i + srcBytes.size, 0.toByte())
                        System.arraycopy(dstBytes, 0, patched, i, dstBytes.size)
                        i += srcBytes.size
                    } else {
                        i++
                    }
                }
            }
            outDir.resolve(name).writeBytes(patched)
            logger.lifecycle("已改写 DirtyFrag 载荷路径：$name")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(patchDfrootAssets)
}
