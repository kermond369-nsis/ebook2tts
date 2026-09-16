plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kermond.ebook2tts.engine"
    // compileSdk 与 App 侧对齐为 36（P5-A，甲方 2026-09-15 决定；仅编译期 API 级别，targetSdk 仍 34）。
    // 说明：不为 `shared_preferences` 之类依赖抬到 36（甲方 2026-09-15 判定 36 过度激进）。
    compileSdk = 36

    defaultConfig {
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // ABI 收敛（P7 / RQ-512）：放弃 32 位（armeabi-v7a）；arm64-v8a 为正式目标，
            // x86_64 仅用于模拟器调试链路。
            abiFilters += listOf("arm64-v8a", "x86_64")
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
    // sherpa-onnx 为本地 AAR：库模块若以 api 依赖它，AGP 打 AAR 时会失败
    // （"Direct local .aar file dependencies are not supported when building an AAR"）。
    // 故此处 compileOnly（编译期可见），运行时由宿主 App 模块以 implementation(files(...)) 引入，
    // 产物中仍会正确包含 .so 与类。
    compileOnly(files("libs/sherpa-onnx-1.13.8.aar"))
    api("com.tencent:mmkv:2.4.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // ADR-014 / RQ-308：在线语速＝客户端时域伸缩，复用 androidx.media3 的 Sonic 实现
    // （Apache-2.0、AOSP/ExoPlayer 同源；不自造 DSP、不引 FFmpeg）。
    // 版本约束：media3-common 1.9.4 的 minCompileSdk=35（1.10+ 要求 36，与"不升级 36"的决策冲突）。
    implementation("androidx.media3:media3-common:1.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.apache.commons:commons-compress:1.26.0")
    // IM-202 / ADR-004：多源测速 + 断点续传 + 清单拉取统一走 OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.20")
    // Android 单元测试中提供真实 org.json（android.jar 的 stub 会抛 not mocked）
    testImplementation("org.json:json:20240303")
    // 本地 HTTP 服务器测试替身（IM-202 续传/整包语义；OkHttp 官方测试组件）
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

/**
 * sherpa-onnx JNI 以**精确方法签名**反射回调 Kotlin 函数：native 侧实查
 * `sherpa-onnx/jni/offline-tts.cc` 的 `CallCallback()` 用
 * `GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;")` 查找回调；
 * 查不到时该分支**不清理 pending 异常**，直接表现为合成线程
 * `NoSuchMethodError ... invoke([F)Ljava/lang/Integer;` → JNI DETECTED ERROR → SIGABRT。
 *
 * 实测（Kotlin 2.3.20，kotlinc 编译 + 反射等价于 GetMethodID）：
 * - 默认 invokedynamic：运行时类只有 `invoke(Object)Object` ⇒ **JNI 查找必挂**；
 * - `-Xlambdas=class`：类继承 `kotlin.jvm.internal.Lambda` 且声明 `invoke(float[]) -> Integer` ⇒ 查找成功。
 * 故 **`-Xlambdas=class` 是唯一必须保留项**（约 2.3.0 起 JVM 的 1.9 语言级别已进入弃用倒计时，
 * 按"弃用即升级"原则不再固定 languageVersion/apiVersion；jvmTarget 走 compilerOptions DSL）。
 */
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xlambdas=class")
    }
}
