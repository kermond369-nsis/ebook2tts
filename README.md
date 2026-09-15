# 书声本地 · ebook2tts-local

> **v0.2.0-alpha.1** ｜ 作者 **Kermond** ｜ LICENSE **MIT** ｜ 包名 `com.kermond.ebook2tts`

把「本机离线 TTS」与「在线大模型 TTS」合成**一个 Android 系统朗读引擎**：
装上它，任何支持「选择 TTS 引擎」的阅读软件（阅读/Legado 等）都能朗读，**默认完全离线、零成本、零上报**；
需要更自然的声音时，可自备密钥开启在线合成，按**角色**逐段造音色。

```
┌─────────── 阅读软件（阅读 / Legado / 任意 TextToSpeechService 调用方）───────────┐
│                        标准 android.speech.tts 协议                            │
└───────────────────────────────────┬───────────────────────────────────────────┘
                                    ▼
                    ┌────────────────────────────────┐
                    │  书声本地 · 系统 TTS 引擎        │
                    │  :tts_service 独立进程（零 UI） │
                    ├────────────────┬───────────────┤
     默认，永远可用  │  本机引擎       │  在线引擎      │  开启需单独同意 + 自备密钥
                    │  SherpaONNX    │  小米 MiMo     │
                    │  中英 103 音色  │  预置 + 造音色  │
                    └────────────────┴───────────────┘
```

## 两条路线，一个引擎

| | 本机引擎（默认） | 在线引擎（可选） |
|---|---|---|
| 模型 | SherpaONNX：`kokoro-int8`（主推，103 音色） / `vits-zh-ll`（保底） / `kokoro-fp32`（高端） | 小米 MiMo-V2.5-TTS 系列（`mimo-v2.5-tts` / `-voicedesign` / `-voiceclone`） |
| 联网 | **完全不需要** | 仅发送**待朗读文本** |
| 音色 | 103 个（中文/英文，男女声） | 预置 8 音色 + **按角色文本造音色** |
| 语速 | 合成时按语速出音 | 客户端**变速不变调**（Sonic 时域伸缩） |
| 失败 | —— | 任何失败**静默回落本机**，朗读不中断 |

## 关键能力

| 能力 | 说明 |
|---|---|
| 标准系统引擎 | 实现 `TextToSpeechService`，任意阅读 App 可直接选用；**阅读/Legado 真机实测** |
| 双进程 | 推理全部在 `:tts_service`，界面进程零推理；**UI 被杀朗读不中断** |
| 流式合成 | `generateWithCallback` 逐段推流 + 首段微切降低首包延迟；有音频后绝不强停 |
| 多角色 | 默认「智能多角色」：文本缓存 → LLM 精标角色档案 → 逐段 `voicedesign` 造音色（**允许前后偏差**，只要可区分）；亦可「尊重阅读器音色」 |
| 在线音色映射 | 本地音色/旁白 → MiMo 预置音色（按**语言+性别**）：女声旁白得女声、男声旁白得男声；阅读器传未知音色名时按旁白性别回落 |
| 语速 | 本机按语速合成；在线走 `androidx.media3` Sonic（采样率不变、不变调） |
| 模型下载 | 前台服务 · 多源**并行测速**选优 · 断点续传 · SHA-256 校验 · 空间预检 2.5× · **取消即时生效** · 恢复时重新测速 |
| 安全解压 | Zip Slip 防御 · 体积/条目上限 · 原子落盘（`.completed` 哨兵） |
| 原生守卫 | `NativeGate` 单一互斥实现点：native 释放只允许在无持有者时；网络调用在锁外 |
| 性能 | 推理线程**自适应** `clamp(核数-1, 2, 8)`；音频推流与合成分离线程 |
| 状态可见 | 引擎状态徽标三态（未安装 / 已装未载入 / 就绪）自动刷新 |

## 隐私与合规（默认离线）

- **默认完全离线**：不开在线时，App 不发起任何网络请求。
- 开启在线合成需要用户在弹窗中**单独同意**，可随时关闭以**撤回同意**。
- 在线时仅将**待朗读文本**发送至第三方服务商（北京小米移动软件有限公司及其关联公司），用于在线语音合成；
  App **不上传音频、不转发、不使用账号体系**。
- API 密钥（若使用）**仅保存在本机**（MMKV），仅在直接请求平台方时使用；密钥类型与应用域名相互绑定。
- 「完整免责声明」与「Token Plan 风险说明」在 App 内设置页可随时查看。

## 模块与两套构建

```
core/     纯 Kotlin/JVM —— 分句与说话人抽取、角色分配、音色表、语速映射、
          在线路由与音色映射、状态机、安全路径（CI 全量单测）
engine/   Android Library —— TTS 服务(:tts_service)、Sherpa 后端、在线后端、
          角色精标、PcmSpeedStretcher(Sonic)、模型下载/解压/迁移、NativeGate
app/      Flutter 界面 —— 引导 / 模型 / 音色库 / 示例朗读 / 设置 / 诊断
```

> ⚠️ **两套 Gradle 构建，勿混**：仓库根只含 `:core` / `:engine`；
> `:app` 只存在于 `app/android` 这套**独立**构建（其 `settings.gradle.kts` 反向 include 根模块）。
> **APK 必须由 Flutter 工具产出**，不要在仓库根调用 `:app:*`。

## 工具链

| 组件 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | **9.1.0**（根 wrapper 与 `app/android` wrapper 均已锁） |
| Android Gradle Plugin | **9.0.1** |
| Kotlin (KGP) | 2.3.20（AGP 9 下保留 KGP：`android.builtInKotlin=false`） |
| compileSdk / minSdk / targetSdk | **36** / **27** / **34** |
| Flutter | 3.47.4 |
| ABI | debug 保留 `x86_64`（模拟器链路）；release 出 `arm64-v8a + armeabi-v7a` |

## 构建

```bash
# 单测（仓库根；需 Gradle 9.1.0）
./gradlew :core:test :engine:testDebugUnitTest

# 调试 APK（必须由 Flutter 驱动）
cd app && flutter pub get && flutter build apk --debug
# 产物：app/build/app/outputs/flutter-apk/app-debug.apk
```

## 模型体积（实测）

| 模型 | 下载 | 解压 |
|---|---|---|
| kokoro-int8（主推） | 147.0 MB | 215.3 MB |
| vits-zh-ll（保底） | 118.8 MB | 135.5 MB |
| kokoro-fp32（高端） | 364.8 MB | 426.7 MB |

模型**不内置**，首次使用由 App 引导下载（可配置自定义镜像）。

## 使用

1. 安装 APK → 打开「书声本地」→ 按引导下载一个模型；
2. 系统设置 → 文字转语音 → 选择 **书声本地**；
3. 打开任意阅读软件朗读即可（音色库可试听、可设旁白；试听只播样音，不改全局旁白）。

## CI / CD

| 工作流 | 触发 | 内容 |
|---|---|---|
| `ci.yml` | push / PR | `:core:test` + `:engine:testDebugUnitTest` + Flutter 构建 debug APK + 产物上传 |
| `release.yml` | push main / `v*` tag | 同上单测 + 构建，自动发布 `build-<shortsha>`（tag 时用 tag 名） |

## 版本与文档

- 当前 `0.2.0-alpha.1`（重构目标 **0.2.0**）。
- **约束类文档（需求书 / 架构书 / 实现报告 / 批次计划 / 《AGENTS.md》等）不在本仓库分发**，按交付渠道单独提供。

## License

MIT · kermond369-nsis
