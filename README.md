# ebook2tts-local · 书声本地

> **v0.2.0-alpha.1** · 作者 **Kermond** · LICENSE **kermond369-nsis** (MIT)

把 **SherpaONNX 本地小模型**封装成 Android 标准 Text-to-Speech 引擎。  
全离线、零成本、开放接入任意「选择 TTS 引擎」的阅读 App（阅读 / Legado 等）。

## 模块

```
core/     纯 Kotlin/JVM：分句、角色分配、音色表、语速、状态机、安全路径（CI 单测）
engine/   Android Library：TTS 服务(:tts_service)、Sherpa、下载、SafeExtractor、迁移
app/      配套 UI：引导 / 模型 / 音色库 / 示例朗读 / 设置 / 诊断
```

## 关键能力（对齐需求书 v1.1 / 架构书 v0.3）

| 能力 | 说明 |
|------|------|
| 流式合成 | `generateWithCallback` 逐段推流，首段微切降 TTFT |
| 多角色 | 智能多角色（默认）/ 尊重阅读器单音色 |
| 双进程 | UI 被杀不中断朗读（`:tts_service`） |
| 模型下载 | FGS dataSync · 断点续传 · SHA-256 · 空间预检 2.5× |
| 安全解压 | Zip Slip 防御 · 体积/条目上限 · 原子落盘 `.completed` |
| 迁移 | SP→MMKV · `zm_058`→`zm_58` · 存量 `.completed` 回填 |
| 协议 | TTS_SERVICE / CHECK / GET_SAMPLE / INSTALL + Voice API |

## 模型体积（勘误单 ERR-001 实测）

| 模型 | 下载 | 解压 |
|------|------|------|
| kokoro-int8（主推） | 147.0 MB | 215.3 MB |
| vits-zh-ll（保底） | 118.8 MB | 135.5 MB |
| kokoro-fp32（高端） | 364.8 MB | 426.7 MB |

模型**不内置**，首次使用引导下载。

## 构建

```bash
# 需要 JDK 17 + Android SDK 34
./gradlew :core:test
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

- minSdk **27** / targetSdk **34**
- ABI：debug 含 arm64-v8a + armeabi-v7a + x86_64

## 使用

1. 安装 APK → 打开「书声本地」→ 下载模型  
2. 系统设置 → 文字转语音 → 选择 **书声本地 · 多角色有声**  
3. 阅读 / Legado 正常朗读  

## CI/CD

- PR：`:core:test` + `:engine:testDebugUnitTest` + `assembleDebug`
- main push：自动 `build-<shortsha>` Release（`gh release`）
- `v*` tag：与 versionName 对齐发版

## 版本

本重构目标 **0.2.0**（当前 `0.2.0-alpha.1`）。基线 0.0.2 仅作历史对照。

## License

MIT · kermond369-nsis
