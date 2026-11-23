plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kermond.ebook2tts.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // 正式包支持 Arm64（主推单包）与 Universal（arm64-v8a + armeabi-v7a + x86_64 兼容包）；
            // 具体变体裁剪在 :app 的 packaging.jniLibs 与 abiFilters 控制。
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true // android.util.Log 等在 JVM 单测中返回默认值
    }
}

dependencies {
    api(project(":core"))
    // MMKV 1.3.17 同时具备 16 KB 页对齐（arm64/x86_64 0x4000）与 armeabi-v7a 32 位兼容库（供通用发布包使用）
    api("com.tencent:mmkv:1.3.17")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // ADR-014 / RQ-308：在线语速＝客户端时域伸缩，复用 androidx.media3 的 Sonic 实现
    // （Apache-2.0、AOSP/ExoPlayer 同源；不自造 DSP、不引 FFmpeg）。
    implementation("androidx.media3:media3-common:1.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.20")
    // Android 单元测试中提供真实 org.json（android.jar 的 stub 会抛 not mocked）
    testImplementation("org.json:json:20240303")
    // 本地 HTTP 服务器测试替身（OkHttp 官方测试组件）
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
