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
    // 版本策略（P5-A）：满足 Flutter 3.47 的 AGP ≥ 9.0.1 / Gradle ≥ 9.1.0 门槛；
    // 暂保留 KGP（android.builtInKotlin=false）以使 -Xlambdas=class 与 sherpa JNI 契约不变；
    // built-in Kotlin 迁移单列为 P5-B，前置条件见《P5-批次计划》。
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
}

include(":app")

// P3 规格 §1：App 与引擎同属一个 App，必须并入仓库根的 :core / :engine 两个模块
include(":core")
project(":core").projectDir = File("../../core")
include(":engine")
project(":engine").projectDir = File("../../engine")
