import java.util.Properties

plugins {
    id("com.android.application")
    // Flutter Gradle 插件必须在 Android 插件之后应用
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    val requestedPlatforms = providers.gradleProperty("target-platform").orNull.orEmpty()
    val explicitlyUniversal = requestedPlatforms.split(",")
        .any { it.trim().equals("android-arm", ignoreCase = true) } ||
        (requestedPlatforms.contains("arm64", ignoreCase = true) && requestedPlatforms.contains("x64", ignoreCase = true))
    val abiModeRaw = providers.gradleProperty("abiMode")
        .orElse(providers.environmentVariable("EBOOK2TTS_ABI_MODE"))
        .orNull.orEmpty().trim().lowercase()
    val abiMode = when {
        abiModeRaw in setOf("arm64", "universal", "debug") -> abiModeRaw
        explicitlyUniversal -> "universal"
        requestedPlatforms.isNotBlank() && !requestedPlatforms.contains("x64", ignoreCase = true) -> "arm64"
        gradle.startParameter.taskNames.any {
            it.contains("Release", ignoreCase = true) || it.contains("Profile", ignoreCase = true)
        } -> "arm64"
        else -> "debug"
    }

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

    val targetAbis = when (abiMode) {
        "arm64" -> listOf("arm64-v8a")
        "universal" -> listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        else -> listOf("x86_64", "arm64-v8a")
    }

    buildTypes {
        debug {
            ndk {
                abiFilters.clear()
                abiFilters.addAll(targetAbis)
            }
        }
        matching { it.name == "profile" }.configureEach {
            ndk {
                abiFilters.clear()
                abiFilters.addAll(targetAbis)
            }
        }
        release {
            ndk {
                abiFilters.clear()
                abiFilters.addAll(targetAbis)
            }
            signingConfig = if (keyProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "lib/x86/**"
            when (abiMode) {
                "arm64" -> excludes += listOf("lib/armeabi-v7a/**", "lib/x86_64/**")
                "debug" -> excludes += "lib/armeabi-v7a/**"
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
    implementation(project(":engine"))
}

// release 构建签名守卫（任务执行期校验，不影响 debug 与无凭据 CI 配置）
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
