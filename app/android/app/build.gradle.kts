import java.util.Properties
plugins {
    id("com.android.application")
    // Flutter Gradle 插件必须在 Android 插件之后应用
    // （Kotlin 插件由 Flutter Gradle 插件在 AGP 8.x 下自动加载，见 FlutterPluginUtils）
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    val requestedPlatforms = providers.gradleProperty("target-platform").orNull.orEmpty()
    val explicitlyUniversal = requestedPlatforms.split(",")
        .any { it.trim().equals("android-arm", ignoreCase = true) } ||
        (requestedPlatforms.contains("arm64", ignoreCase = true) && requestedPlatforms.contains("x64", ignoreCase = true))
    val abiModeRaw = providers.gradleProperty("abiMode").orNull.orEmpty().trim().lowercase()
    val abiMode = when {
        abiModeRaw in setOf("arm64", "universal", "debug") -> abiModeRaw
        explicitlyUniversal -> "universal"
        requestedPlatforms.isNotBlank() && !requestedPlatforms.contains("x64", ignoreCase = true) -> "arm64"
        gradle.startParameter.taskNames.any {
            it.contains("Release", ignoreCase = true) || it.contains("Profile", ignoreCase = true)
        } -> "arm64"
        else -> "debug"
    }

    // P7：发布签名（存在 key.properties 时用发布密钥；否则回退调试密钥，保证他人 clone 后可构建）
    val keyProps = Properties().apply {
        val f = rootProject.file("key.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keyProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }
    namespace = "com.kermond.ebook2tts"
    // P3 规格 §1：minSdk 27；P5-A：compileSdk 35 → 36（甲方 2026-09-15 决定）
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.kermond.ebook2tts"
        minSdk = 27
        targetSdk = 34
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    fun applyAbiFilters(filter: com.android.build.api.dsl.Ndk) {
        filter.abiFilters.clear()
        when (abiMode) {
            "arm64" -> filter.abiFilters.add("arm64-v8a")
            "universal" -> filter.abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
            else -> filter.abiFilters.addAll(listOf("x86_64", "arm64-v8a"))
        }
    }

    buildTypes {
        debug {
            // 默认保留 x86_64 + arm64-v8a 供本地/CI 调试；传 -PabiMode=arm64/universal 时按需收敛
            ndk { applyAbiFilters(this) }
        }
        matching { it.name == "profile" }.configureEach {
            ndk { applyAbiFilters(this) }
        }
        release {
            ndk { applyAbiFilters(this) }
            // 正式签名由发布线配置；当前阶段沿用调试签名，保证 `flutter run --release` 可用
            // P7 复核整改（agy 2026-09-17）：release 缺凭据的**失败判定放在任务执行期**（见文件末尾
            // 的 assembleRelease 守卫），此处只在有凭据时切到发布签名。
            // ！注意：此处是**配置期**代码，绝不能 throw —— 否则 CI（无 key.properties）连 debug 构建都无法配置。
            signingConfig = if (keyProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            // 压缩 .so 并放行 AGP/NDK 剥离 DWARF 调试符号，消除 377MB libflutter.so / 237MB libVkLayer 膨胀
            useLegacyPackaging = true
            // 始终排除 32 位 x86；按 abiMode 精准裁剪 armeabi-v7a / x86_64（对抗本地 AAR 带入多余 ABI）
            excludes += "lib/x86/**"
            when (abiMode) {
                "arm64" -> excludes += listOf("lib/armeabi-v7a/**", "lib/x86_64/**")
                "debug" -> excludes += "lib/armeabi-v7a/**"
                // "universal": 保留 arm64-v8a / armeabi-v7a / x86_64
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    // P3 规格 §1：App 通过 :engine 依赖本机引擎（:core 由 :engine 传递，api 依赖）
    implementation(project(":engine"))
    // sherpa-onnx 本地 AAR：:engine 以 compileOnly 引用（避免库模块打 AAR 失败），
    // 运行时由本模块引入，保证 .so 与类进入 APK。
    implementation(files("../../../engine/libs/sherpa-onnx-1.13.8.aar"))
    // Pigeon 桥接生成代码（app 模块内）所需的协程运行时：
    // :engine 以 implementation 引入，不外露给宿主编译类路径，故此处显式声明同版本。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 桥接 validateKey 的网络调用走 OkHttp（与 :engine 同版本，避免版本漂移）。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

// ── P7 / E8：release 构建的签名守卫（**任务执行期**校验，不影响 debug 与 CI 配置阶段）──
// 目的：严禁在没有发布签名凭据时静默产出"伪 release"包（无法覆盖升级）。
tasks.matching { it.name == "assembleRelease" }.configureEach {
    doFirst {
        val f = rootProject.file("key.properties")
        if (!f.exists()) {
            throw GradleException(
                "缺少 key.properties：release 构建必须提供发布签名凭据（调试请用 debug 变体；CI 请注入 secrets）",
            )
        }
    }
}
