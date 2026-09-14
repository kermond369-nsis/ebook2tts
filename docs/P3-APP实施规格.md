# P3 实施规格 — Flutter `:app`（App 侧与引擎侧的接口边界）

> 版本 v1.0 · 2026-09-14 · 甲方授权 8 小时自主推进
> 上游：`决议台账-在线后端与P3-20260914.md`、`弹窗文本-TokenPlan风险确认.md`、ADR-005（Riverpod + Pigeon）
> **硬约束**：Flutter 侧**不得**发明新接口——一律通过下方 `EngineService` 协议访问引擎；引擎侧实现由实施线（Hermes）提供。

---

## 1. 工程形态

| 项 | 取值 |
|---|---|
| 位置 | 仓库 `app/`（替换原原生 Android App；旧代码留在 git 历史） |
| 包名 / applicationId | **`com.kermond.ebook2tts`**（与 `:engine` 同包，故 TTS 引擎与界面同属一个 App） |
| 依赖 | 必须 include 仓库根的两个模块：`:core`、`:engine` |
| 状态管理 | `flutter_riverpod` |
| 桥接 | 本阶段先实现 `EngineService` 的 **Mock 实现**（可跑、可点、有假数据）；真机桥接由实施线随后替换为 Pigeon 实现（接口签名不变） |
| 最低/目标 | minSdk 27、compileSdk 35、Kotlin/AGP 与根工程一致（Gradle 8.5 / AGP 8.7.x） |

`app/android/settings.gradle.kts` 需追加（示例）：

```kotlin
include(":core")
project(":core").projectDir = File("../../core")
include(":engine")
project(":engine").projectDir = File("../../engine")
```

`app/android/app/build.gradle.kts`：`implementation(project(":engine"))`（`:core` 随 `:engine` 传递）。

## 2. 设计令牌（禁止偏离；对比度已实测）

| 用途 | 值 |
|---|---|
| 页面背景 | `#0F1419`（墨蓝） |
| 卡片/表面 | `#1A2332` |
| 主强调 | `#7DCEA0`（薄荷绿）——**其上必须用深色字 `#0F1419`**（实测 8.2–9.6:1）；**禁止**薄荷绿底 + 白字（1.87:1 不达标） |
| 主文字 | `#F4EFE6` |
| 次要文字 | `#8A93A6` |
| 危险/警示 | `#E2725B` |
| 圆角/间距 | 卡片 16、按钮 12；页面边距 16；卡片间距 12 |

**文案纪律**：界面不出现英文/工程黑话（如 "token""RTF""chunk""P95"）；模型/文件等专有名词除外。

## 3. 页面清单（IM-301~307）

| 页 | 要点 |
|---|---|
| **引导** | 三步说明（离线隐私 / 下载模型 / 系统 TTS 设置跳转）；无模型时主按钮"去下载模型" |
| **模型管理** | 三档卡片（名称、体积实测值、说明、"已安装/下载/删除/使用中"状态、下载进度、**自定义镜像地址**输入）；**已安装需显示"使用中"并禁用重复安装** |
| **音色库** | **中文音色优先、英文折叠**；每行「试听 / 设旁白」**两个独立按钮**（试听**不得**改全局旁白）；当前旁白有标记 |
| **示例朗读** | 可编辑文本 + 播放/停止 + 当前音色/语速显示；用于验收试听链路 |
| **设置** | ① 语速 ② **在线朗读总开关（默认关）** ③ 在线密钥类型（按量计费 / Token Plan）④ API Key 输入 ⑤ Base URL（自动跟随密钥类型，可高级覆盖）⑥ **「允许使用数据流量」开关（默认关）** ⑦ 自定义下载镜像 ⑧ 入口：系统 TTS 设置 / 完整免责声明 |
| **诊断** | 引擎状态、模型、**最近一次** 性能数据（首块/总时长/丢块，**可清零**）、日志复制 |
| **Token Plan 弹窗** | 严格按 `docs/弹窗文本-TokenPlan风险确认.md`：三句白话**置顶、最大字号、加粗**；正文可滚动；**滑到底才能点同意**；同意后才解锁 `tp-…` 输入 |

## 4. `EngineService` 协议（App 侧按此实现；引擎侧同名实现）

```dart
enum EngineState { noModel, loading, ready, reloading, error }
enum OnlineState { off, ready, noKey, error }

class EngineStatus { EngineState state; String? modelId; int sampleRate; int speakers; OnlineState online; OnlineState onlineState; }
class ModelInfo { String id; String label; String note; int downloadBytes; int extractedBytes; bool installed; bool active; bool recommended; }
class DownloadProgress { String modelId; int received; int total; String phase; }  // phase: idle/probe/download/verify/extract/promote/done/error
class VoiceInfo { String id; String name; String lang; bool isNarrator; }
class AppConfig { double speed; bool onlineEnabled; bool tokenPlanAccepted; String keyKind; // 'billing'|'plan'
                   String apiKeyMasked; String baseUrl; bool allowMobileData; String customMirror; }
class EngineEvent { String type; Map<String, dynamic> data; }  // type: status/progress/model/error

abstract class EngineService {
  Future<EngineStatus> status();
  Future<List<ModelInfo>> models();
  Future<void> startDownload(String modelId);
  Future<void> cancelDownload();
  Future<void> deleteModel(String modelId);
  Future<void> setActiveModel(String modelId);
  Future<List<VoiceInfo>> voices();
  Future<void> setNarratorVoice(String voiceId);
  Future<void> preview({required String text, required String voiceId}); // 不改全局旁白
  Future<void> stopPreview();
  Future<AppConfig> config();
  Future<void> updateConfig({double? speed, bool? onlineEnabled, bool? allowMobileData,
                             String? keyKind, String? apiKey, String? baseUrl, String? customMirror,
                             bool? tokenPlanAccepted});
  Future<List<String>> recentLogs();
  Future<void> clearPerfCounters();
  Stream<EngineEvent> events();
}
```

**校验密钥**：`Future<String?> validateKey({required String keyKind, required String apiKey})` —— 返回 `null` 表示通过，否则返回平台方原始错误摘要。

## 5. Mock 阶段要求

- 全页面可跑通、可点击、状态可切换；模型/音色用**真实清单文案**（Kokoro int8 147MB/215MB、VITS zh-ll 118MB/135MB、Kokoro fp32 364MB/426MB；音色含 晓晓/晓伊/晓妮/云希… 与英文音色）；
- 下载进度用假进度模拟（probe→download→verify→extract→promote）；
- 弹窗门槛逻辑**必须真实生效**（这是法律门槛，不是装饰）。

## 6. 验收（实施线核对）

1. `flutter build apk --debug` 成功，产物可安装（MuMu `[LAN-WIN-HOST]:7555`）；
2. 六个页面均可到达、无空白页、无英文残留；
3. Token Plan 门槛：未滑到底不能同意；取消不落状态；同意后解锁输入；
4. 「允许使用数据流量」默认关；在线总开关默认关；
5. 试听不改全局旁白（界面可见旁白标记不变）。

## 7. 构建命令（本机）

```bash
export JAVA_HOME=[TOOLS-DIR]/jdk-17
export ANDROID_HOME=[USER-HOME]/inbox/output/[OTHER-PROJECT]/android-sdk
export https_proxy=http://[LAN-GATEWAY]:7890 http_proxy=http://[LAN-GATEWAY]:7890
cd [PROJECT-DIR]/app && [TOOLS-DIR]/flutter/bin/flutter build apk --debug
```
