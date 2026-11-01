import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.codefox.paperlite"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.codefox.paperlite"
        // 设备所有者 / 无线调试配对 / 无障碍 都需要 29+
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
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

val dexAgent = tasks.register<Exec>("dexAgent") {
    description = "把安装代理打成独立 dex（assets/agent/classes.dex）"
    dependsOn(compileAgentJava)
    val (d8Jar, javaHome) = findD8()
    val out = agentOutDir.get().asFile
    val classesDir = agentClassesDir
    inputs.dir(classesDir)
    outputs.dir(out)
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
        // d8 输出的是 classes.dex；放进 agent/ 子目录，运行时 CLASSPATH 指向它
        val dexFile = File(out, "classes.dex")
        if (!dexFile.exists()) error("d8 没有产出 classes.dex")
        val agentDir = File(out, "agent")
        agentDir.mkdirs()
        dexFile.copyTo(File(agentDir, "classes.dex"), overwrite = true)
    }
}

/** 把打好的 agent dex 作为 assets 参与打包。 */
android.sourceSets.getByName("main").assets.srcDir(agentOutDir.get().asFile.absolutePath)

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(dexAgent)
}
