# ebook2tts-local · 书声本地

> **v0.1.1Alpha** · 作者 **Kermond**

把 **SherpaONNX 本地小模型**（或系统机械 TTS）封装成 Android 标准 Text-to-Speech 引擎。  
与云端版 [Ebook2tts](../Ebook2tts) **独立仓库、独立包名**，互相借鉴分析逻辑，不共用模块。

```text
小说 App  speak(text)
    → 系统 TTS 框架 (BIND_TTS_SERVICE)
    → LocalTextToSpeechService
         机械分析（说话人 / 情绪）
         角色 → 本地音色分配
         SherpaONNX（Kokoro int8 等）或 系统 TTS 兜底
    → SynthesisCallback 输出 PCM
```

## 特点

| 能力 | 说明 |
|------|------|
| 无需 API Key | 首次下载模型后完全离线朗读 |
| 模型 ≤ 1.5B | 默认 Kokoro int8 ~82M；可选 VITS 中文多音色 |
| 多角色 | 本地规则识别人名/对白，按性别分配音色 |
| 机械兜底 | 低端机可切「系统 TTS」，零下载 |
| 标准引擎 | 任意小说 App 走 `TextToSpeech` |

## 性能要求

- **推荐**：骁龙 7 系 / 天玑 8000 及以上，arm64，≥4GB RAM  
- Kokoro int8：中端机可接近实时；低端机请改用系统 TTS 或 `vits-zh-ll`  
- 模型体积 40–310MB，存在应用私有目录

## 构建

```bash
# Android Studio 打开本目录
# 或
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

依赖：AGP 8.3.x + Gradle 8.5+，compileSdk 34。  
内置 `app/libs/sherpa-onnx-1.13.8.aar`。

## 使用

1. 安装 APK → 打开「书声本地」  
2. 选择模型 → **下载**（推荐 Kokoro int8）  
3. 选择旁白音色、语速、线程  
4. 系统设置 → 文字转语音 → 选择 **书声本地 · 多角色有声**  
5. 小说软件正常朗读  

## 内置中文音色（Kokoro）

| ID | 名称 | 性别 |
|----|------|------|
| zf_xiaoxiao | 晓晓 | 女 |
| zf_xiaoyi | 晓伊 | 女 |
| zf_xiaoni | 晓妮 | 女 |
| zf_xiaobei | 晓北 | 女 |
| zm_yunyang | 云扬 | 男（旁白默认） |
| zm_yunjian | 云健 | 男 |
| zm_yunxi | 云希 | 男 |
| zm_yunxia | 云夏 | 男 |

## 架构

```
app/src/main/java/com/mimo/ebook2tts/local/
  LocalPrefs.kt
  analysis/          # 借鉴云端版：说话人/情绪/角色档案
  buffer/SegmentCache.kt
  model/             # 模型目录、下载解压
  voice/             # LocalVoice + 分配
  tts/
    LocalTextToSpeechService.kt
    LocalSynthesisEngine.kt
    SherpaBackend.kt
    SystemTtsBackend.kt
  ui/LocalSettingsActivity.kt
```

## 与云端版关系

| | Ebook2tts（云） | ebook2tts-local（本仓库） |
|--|----------------|---------------------------|
| 包名 | `com.mimo.ebook2tts` | `com.mimo.ebook2tts.local` |
| 合成 | MiMo API | SherpaONNX / 系统 TTS |
| Key | 需要 | 不需要 |
| 角色精标 | mimo-v2.5 | 仅本地规则 |
| 网络 | 合成必须联网 | 仅首次下载模型 |

分析规则（引号对白、XX说、跨句说话人）**逻辑同源**，代码各自独立拷贝演进。

## 致谢

- [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)  
- [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M)  
- 云端版 Ebook2tts 分析管线设计  

## License

MIT
