import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.0"
}

kotlin {
    jvm {
        withJava()
    }
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(libs.miuix.ui)
                implementation(libs.miuix.icons)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.codefox.paperlite.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "PaperLite"
            packageVersion = "1.0.0"
            description = "PaperLite 电脑版 · 手机管理伴侣"
            vendor = "GeoRainP"
            copyright = "GPL-2.0"
        }
    }
}
