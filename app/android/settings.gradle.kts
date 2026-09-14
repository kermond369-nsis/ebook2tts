pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    // 版本策略：满足本机 Flutter 3.47 的最低构建要求（AGP ≥ 8.11.1、KGP ≥ 2.2.20、
    // Gradle ≥ 8.14），同时保持 AGP 8.x 系列以兼容 :core / :engine 既有构建脚本。
    id("com.android.application") version "8.11.1" apply false
    id("com.android.library") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.20" apply false
}

include(":app")

// P3 规格 §1：App 与引擎同属一个 App，必须并入仓库根的 :core / :engine 两个模块
include(":core")
project(":core").projectDir = File("../../core")
include(":engine")
project(":engine").projectDir = File("../../engine")
