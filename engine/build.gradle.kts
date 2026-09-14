plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kermond.ebook2tts.engine"
    compileSdk = 34

    defaultConfig {
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
        ndk {
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
    kotlinOptions {
        jvmTarget = "17"
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
    // sherpa-onnx 为本地 AAR：库模块若以 api 依赖它，AGP 打 AAR 时会失败
    // （"Direct local .aar file dependencies are not supported when building an AAR"）。
    // 故此处 compileOnly（编译期可见），运行时由宿主 App 模块以 implementation(files(...)) 引入，
    // 产物中仍会正确包含 .so 与类。
    compileOnly(files("libs/sherpa-onnx-1.13.8.aar"))
    api("com.tencent:mmkv:1.3.9")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.apache.commons:commons-compress:1.26.0")
    // IM-202 / ADR-004：多源测速 + 断点续传 + 清单拉取统一走 OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.24")
    // Android 单元测试中提供真实 org.json（android.jar 的 stub 会抛 not mocked）
    testImplementation("org.json:json:20240303")
    // 本地 HTTP 服务器测试替身（IM-202 续传/整包语义；OkHttp 官方测试组件）
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
