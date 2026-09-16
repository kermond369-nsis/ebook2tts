# P7-B 缺陷台账（v0.4 · 静态清查 + 人工走查 + 设备取证 + agy 复核处置 + 冷启竞态复现）

> 批次：P7-A/P7-B ｜ 生成：2026-09-16 ｜ 实施线：Hermes ｜ 复核：agy（`附件O`，结论"有条件通过"，本轮处置完毕）
> 分支：`p7/audit` ｜ 基线提交：`3b257e2`（主仓 main 未改动）
> 状态：**第四批**（新增真机复现的 P1 冷启竞态 BUG-P7-011；补 BUG-P7-001 的客户端可见证据）
> 当前条目：**BUG-P7-001 ~ 015** ｜ 分级：**P1 ×4**（001 错误码 / 004 16 KB 对齐 / 011 冷启首请求被拒 / **015 预览通道自建后端**）、**P2 ×5**（002 / 003 / 008 / 013 / 014）、**P3 ×6**（005 / 006 / 007 / 009 / 010 / 012）
> 真机：**Mi MIX 2S（SDM845 / Android 14）** 首轮实测完成（《P7-真机报告》）；004 **已修并验证**（`b6d0e5e` + `38448a9`）；011/013/014/015 的合并修复方案已过 agy 复核（《P7-C 修复方案 v0.2》+ `附件Q`）
> 独立复核：**两轮台账复核 + 一轮专项验证**（`附件O` / `附件P`），其中 agy 主动发现 **BUG-P7-013** 并修正了 F-009 的修复顺序
> 判据来源：《需求书》RQ/NFR/TC、《架构书》AR/ADR、`AGENTS.md` 九条红线

---

## 一、方法与工具（已实测可用性）

| 工具 | 版本/来源 | 用例 | 结果 |
|---|---|---|---|
| Android Lint | AGP 9.0.1 内置 | `gradle :engine:lintDebug` | ✅ 可用；**3 errors / 18 warnings**（`engine/build/reports/lint-results-debug.txt`） |
| detekt | **detekt-cli 1.23.8**（CLI 旁路，未改 Gradle 插件） | `:core/src/main`、`:engine/src/main` | ✅ 可用（JDK 17 解析 Kotlin 2.3.20 源码通过）；**core 92 / engine 143** 项 |
| detekt | 2.0.0-alpha.6（CLI） | `:core/src/main` | ✅ **复测：可在 JDK 17 下运行并完成分析**（`:core` 报 **60** 项；其**默认规则集与 1.x 不同，不可与 92 项直接比较**）；⚠️ **2.x 报告参数名变更**（`md`→`markdown`、`xml`→`checkstyle`），按 1.x 写法会**静默不产出报告**（已实测）⇒ 本轮采用 1.23.8 |
| flutter/dart analyze | Flutter 3.47.4 | `app/` | ✅ `flutter analyze` 0 issues（P6 复验）；`dart analyze --fatal-infos` 结果见 §三 补充 |
| 依赖漏洞 | **OSV API**（Google，按包名+精确版本查询） | 9 个 Maven 依赖 + `flutter pub outdated` | ✅ 全部 **0 命中**（详见基线报告 §7.1） |
| native 合规 | **`llvm-readelf -l`**（NDK 28.2 工具链）扫 APK/AAR 内全部 `.so` 的 `LOAD` 对齐 | debug APK（3 ABI） | ✅ 完成：唯一不合规项＝ `libmmkv.so`（见 BUG-P7-004） |
| 崩溃取证 | `dumpsys dropbox` + `/data/tombstones`（root） | 本包历史崩溃/ANR | ✅ 完成：2 条崩溃记录、0 条 ANR（见 BUG-P7-008） |
| 动态冒烟 | `adb shell input tap` + `exec-out screencap` + 视觉定位 | 冷启/试听/快速起停 | ✅ 见基线报告 §二/§四/§五 |
| 依赖版本/CVE | Android Lint `NewerVersionAvailable` + OSV | `:engine` | ✅ 完成（见 BUG-P7-007） |

> 说明：detekt 首轮**未注入 classpath**（无类型解析），"需类型解析"的规则被跳过；已在结果中如实标注，后续批次补齐 classpath 复跑。

---

## 二、缺陷台账

### BUG-P7-001 ｜ TTS 错误码错位：`SYNTHESIS` 实为 `ERROR_SERVICE`、`NOT_INSTALLED_YET` 为非法值 ｜ **P1**

- **模块**：`:engine`（`SynthesisCoordinator.kt`）
- **证据（代码）**：`engine/.../SynthesisCoordinator.kt:758` 自定义常量
  ```kotlin
  object TextToSpeechErrors {
      const val SUCCESS = 0
      const val SYNTHESIS = -4
      const val NOT_INSTALLED_YET = -12
  }
  ```
  使用点：`SynthesisCoordinator.kt:321 / 340 / 342 / 356 / 706`（均为 `callback.error(TextToSpeechErrors.SYNTHESIS)` 或 `NOT_INSTALLED_YET`）
- **证据（官方常量，实查 developer.android.com/reference/android/speech/tts/TextToSpeech，2026-09-16）**：

  | 常量 | 官方值 | 本项目值 | 偏差 |
  |---|---|---|---|
  | `ERROR_SYNTHESIS` | **-3** | -4 | ✗ 错位 |
  | `ERROR_SERVICE` | -4 | （被误当作 SYNTHESIS） | ✗ |
  | `ERROR_NOT_INSTALLED_YET` | **-9** | -12 | ✗ 非法值 |
  | `SUCCESS` | 0 | 0 | ✓ |

- **证据（静态工具）**：Android Lint `WrongConstant` **3 errors**（`SynthesisCoordinator.kt:321/356/706`），提示"必须是 TextToSpeech.ERROR_* 之一"
- **🔥 证据（真机现场，2026-09-16 00:54，客户端可见）**：Legado 朗读触发后，客户端日志原文——
  ```
  00:54:41.699 I/Legado: TTSUtteranceListener onError nowSpeak:25 pageIndex:6 utteranceId:Legado:3:25 errorCode:-4
  ```
  ⇒ 客户端**实际收到 -4（＝ERROR_SERVICE）**，即阅读器把"合成失败"理解为"引擎服务错误"（详见 BUG-P7-011 同一次现场）。
- **影响面**：
  1. 客户端（Legado / 系统 TTS 框架）收到 **-4 = ERROR_SERVICE**，会被理解为"引擎服务异常"，而非"合成失败"——错误提示与重试策略可能错乱；
  2. 零模型场景本应报 `ERROR_NOT_INSTALLED_YET(-9)`，现发 **-12（未定义值）** ⇒ 阅读器无法识别"需先下载语音数据"，直接冲击 **RQ-112 / TC-13「零模型协议：获得错误提示与通知」** 的验收判据；
  3. 与《架构书》§4.7「错误语义总表」不一致 ⇒ 按 AGENTS.md §1「实现与文档不一致＝缺陷」应双边回写。
- **建议处置**：改为直接引用平台常量 `android.speech.tts.TextToSpeech.ERROR_SYNTHESIS / ERROR_NOT_INSTALLED_YET`（不保留自造常量），单测固化数值断言；真机复核 Legado 与系统设置链路表现。**修复属行为变更 ⇒ 先回写文档（§4.7 错误语义总表 + IM 编号续排）。**
- **状态**：新建（待 agy 复核分级）

### BUG-P7-002 ｜ 应用允许备份 + API Key 明文入库 ⇒ 密钥可随系统备份外泄 ｜ **P2**

- **模块**：`:app` 清单 + `:engine`（`ConfigStore`）
- **证据**：
  - `app/android/app/src/main/AndroidManifest.xml:9` `android:allowBackup="true"`，且**未声明** `android:fullBackupContent` / `android:dataExtractionRules`；
  - `engine/.../ConfigStore.kt:23` `MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE)`（**无 cryptKey**）、`:83/:85` `online.apiKey` 以明文读写；
  - `EngineApp.kt:20` `MMKV.initialize(this)`（未启用 MMKV 加密）。
- **影响面**：MMKV 默认落在 `files/mmkv`，属受备份范围 ⇒ 云备份/`adb backup`（Android 12 以下及部分 OEM 通道）可导出用户 API Key，与 **ADR-011「Key 仅本机」/ NFR-§5.4 隐私**承诺不符；亦影响应用商店"数据安全"申报口径。
- **建议处置（三选一，需甲方/agy 定）**：① `allowBackup="false"`；② 保留备份但用 `dataExtractionRules` 显式排除 MMKV 目录；③ 迁移密钥至 Android Keystore/EncryptedSharedPreferences（改造量最大）。**任一方案均为行为变更 ⇒ 先回写文档。**
- **状态**：新建

### BUG-P7-003 ｜ 从未申请 `POST_NOTIFICATIONS` ⇒ Android 13+ 下载通知不进通知栏 ｜ **P2（已核实官方口径）**

- **模块**：`:app`（Flutter 运行时）
- **证据（代码）**：`engine` 清单声明 `android.permission.POST_NOTIFICATIONS`（`AndroidManifest.xml:8`），但全仓检索 **无任何运行时请求路径**（`app/lib`、`app/android/app/src` 无权限请求）。
- **✅ 真机确证（Mi MIX 2S / Android 14，2026-09-16）**：
  - 权限状态 `POST_NOTIFICATIONS: **granted=false**`（appop `POST_NOTIFICATION: ignore`）；
  - 下载 FGS 正常启动并常驻：`isForeground=true … foregroundNoti=Notification(channel=download…)`；
  - **但通知栏检索本应用通知数 = 0**（`dumpsys notification --noredact | grep pkg=com.kermond.ebook2tts`）
  ⇒ 用户看不到下载进度/完成通知，**成立且真机复现**。
- **证据（官方，developer.android.com「Notification runtime permission」，2026-09-16 实查）**：
  > "On Android 13 (API level 33) or higher, if the user denies the notification permission, they still see notices related to foreground services in the **Task Manager** but **don't see them in the notification drawer**."
  > 另：启动前台服务**不需要**该权限（服务仍能启动），但通知不进抽屉。
- **影响面**：Android 13+ 用户未授权时，模型下载的进度/完成通知**不可见**（后台下载"无感知"），与 RQ-202「后台完成可见」体验目标存在缺口；**不导致崩溃或服务不可用**（故非 P0）。
- **建议处置**：启动时按 Android 13+ 请求 `POST_NOTIFICATIONS`（成熟做法：`permission_handler` 或自定义 MethodChannel），拒绝时给出设置页引导；需先回写文档（RQ-202/设置项语义）。
- **状态**：**成立**（待 agy 复核分级）

### BUG-P7-004 ｜ `libmmkv.so`（arm64-v8a）非 16 KB 页对齐 ｜ **P1**

- **模块**：`:engine` 依赖（`com.tencent:mmkv:1.3.9`）
- **证据**：Android Lint `Aligned16KB`（lint 报告 59–63 行）：
  > `The native library arm64-v8a/libmmkv.so (from com.tencent:mmkv:1.3.9) is not 16 KB aligned`
  （`libmmkv.so` 落在 Gradle transforms 缓存 `mmkv-1.3.9/jni/arm64-v8a/`）
- **影响面**：16 KB 页大小的 ARM64 设备上，4 KB 对齐的 .so 存在**加载失败/兼容风险**；官方机制：Android 15/16 具备 16 KB backcompat 模式（可运行但告警），**Android 17 可强制 fatal abort**。
- **✅ 已核实（2026-09-16，两轮：实施线实查 + agy 复核更正）**：
  1. **Play 政策期限（以官方现行页面为准）**：`developer.android.com/guide/practices/page-sizes`（末次更新 2026-08-05）原文——
     > "all apps targeting Android 15 (API level 35) and higher must support 16 KB memory page sizes on 64-bit devices on Google Play. **Starting February 1, 2027**, if your app updates don't support 16 KB memory page sizes, you won't be able to release these updates."
     （早期 2025-05 公告曾为 2025-11-01，后续提供延期窗口；**现行强制日为 2027-02-01**。）
  2. **实测各版本 AAR 的 `.so` 对齐**（`llvm-readelf -l`，非推断）：

     | 版本 | ABI 覆盖 | arm64 | armeabi-v7a | x86_64 |
     |---|---|---|---|---|
     | 1.3.9（现用） | arm64/v7a/x86/x86_64 | **0x1000 ✗** | 0x1000 | 0x1000 |
     | 1.3.16 | arm64/v7a/x86/x86_64 | 0x4000 ✓ | 0x1000 | 0x4000 ✓ |
     | **1.3.17（1.x 最新）** | arm64/**v7a**/x86/x86_64 | **0x4000 ✓** | 0x1000 | **0x4000 ✓** |
     | 2.4.2（2.x 最新） | **仅 arm64/x86_64** | 0x4000 ✓ | **无 32 位库** | 0x4000 ✓ |

  3. 同 APK 内其他 native 库（`libflutter.so` 0x10000、sherpa 4 个 .so 均 0x4000）**全部合规**。
- **建议处置**：**升级 `com.tencent:mmkv` → 1.3.17**（16 KB 对齐 ✓ 且保留 `armeabi-v7a`，与 DQ-2/NFR §5.3 双 ABI 不冲突）；**明确不采纳 2.x**（实测无 32 位库，与双 ABI 决策冲突）。属依赖变更 ⇒ 需甲方逐条批准（计划 §3.3 白名单）。
- **状态**：**成立**（修复路径已实测；待甲方批准依赖变更）
- **🔥 已处置（2026-09-16）**：私密测试仓提交 **`b6d0e5e`** —— `com.tencent:mmkv` **1.3.9 → 2.4.2** + ABI 收敛为 `arm64-v8a`（debug 保留 `x86_64`+`arm64-v8a`）；`:core:test`/`:engine:testDebugUnitTest` 全绿；API 兼容性逐项核验 **15/15**（`initialize/mmkvWithID/encode/decodeString/decodeBool/decodeInt/lock/unlock/…` 在 2.4.2 均存在）。**待真机复核**（构建产物 `readelf` 对齐 + 配置跨版本迁移）

### BUG-P7-005 ｜ 死版本判断（minSdk 27 下多余 SDK_INT 分支）｜ **P3**

- **证据**：Lint `ObsoleteSdkInt` ×4 —— `DownloadService.kt:91`、`PreviewPlayer.kt:307/342/368`
- **影响**：无功能影响；属可维护性。**处置**：随 P7-C 清理（低风险）。

### BUG-P7-006 ｜ detekt 代码质量命中 235 项（core 92 + engine 143）｜ **P3（个别 P2 待研判）**

- **分布**：`MagicNumber` 138、`ReturnCount` 33、`TooGenericExceptionCaught` 21、`ComplexCondition/CyclomaticComplexMethod/LongMethod/NestedBlockDepth/TooManyFunctions` 等复杂度项 ~23、`SpreadOperator` 1、`InstanceOfCheckForException` 1、`MaxLineLength` 2、`ThrowsCount` 4
- **抽检结论**：`TooGenericExceptionCaught` 21 处**未见空 catch**（逐个含处理逻辑），暂无"吞异常致假成功"证据；但 `InstanceOfCheckForException`（以异常类型分支控制流）与 5 处 `CyclomaticComplexMethod` 需在 P7-B 动态阶段结合红线复查。
- **处置**：MagicNumber/ReturnCount 归入 P3 批量整理（`detekt.yml` 基线化，不阻断）；复杂度与异常处理项逐条研判。

### BUG-P7-007 ｜ 依赖版本落后（含安全相关）｜ **P3（与 CVE 检查合并）**

- **证据**：Lint `GradleDependency` / `NewerVersionAvailable`：`core-ktx 1.13.1→1.19.0`、`appcompat 1.7.0→1.8.0`、`media3-common 1.9.4→1.11.1`、`mmkv 1.3.9→2.4.2`、`coroutines 1.8.1→1.11.0`、`commons-compress 1.26.0→1.28.0`、`okhttp 4.12.0→5.5.0`、`json 20240303→20260814`、`mockwebserver 4.12.0→5.5.0`
- **说明**：版本落后本身非缺陷；**已用 OSV 按精确版本查询 9 个 Maven 依赖 + Flutter 直接依赖 → 全部 0 命中**（2026-09-16，详见基线报告 §7.1）。故本条降级为**纯版本陈旧**，**不构成已知漏洞风险**（口径：OSV 无已知漏洞 ≠ 绝对无漏洞；OWASP dependency-check 未跑）。
- **补充关注点**：`com.tencent:mmkv` 因 BUG-P7-004（16 KB 对齐）**需要**升级到 1.3.17 —— 属依赖变更，需甲方逐条批准。

---

### BUG-P7-008 ｜ 历史崩溃：`:tts_service` 因 MMKV 未初始化而无法创建服务（2 条记录）｜ **P2（历史记录；现行代码具备修复路径，列回归验证项）**

- **模块**：`:engine`（多进程初始化顺序，AR-§5.1.1 / ISSUE-03 防护点）
- **证据（设备 dropbox 取证，root 读取）**：
  - `/data/system/dropbox/data_app_crash@1789406540589.txt`、`…@1789406541910.txt`
  - 时间：**2026-09-15 01:22:20.589 / 01:22:21.910**；进程 `com.kermond.ebook2tts:tts_service`；版本 `v1 (0.2.0-alpha.1)`
  - 异常原文：`java.lang.RuntimeException: Unable to create service com.kermond.ebook2tts.engine.LocalTextToSpeechService: java.lang.IllegalStateException: You should Call MMKV.initialize() first.`
  - 本项目**全部**崩溃记录即为这 2 条；**0 条 ANR**（`data_app_anr*` 无命中）
- **影响面**：引擎进程崩溃 ⇒ 朗读完全不可用（若非历史遗留则为 P0）。该类缺陷"静默致命"：只在 `:tts_service` 首次被绑定时暴露，UI 无感知。
- **现状与诚实说明**：
  - 现行源码 `EngineApp.onCreate()` **首行**即 `MMKV.initialize(this)`，之后才做迁移与精标；
  - 今日实测（00:40–00:42）`:tts_service` 正常启动、预览合成正常、配置读写成功 ⇒ **未复现**；
  - `git log -S "MMKV.initialize" -- EngineApp.kt` 显示该调用自 `a524d36` 起**未再变更**，故崩溃当时设备上实际安装的构建**无法从仓库判定**（可能为更早版本）⇒ **不写"已修复"结论**，改列**回归验证项**。
- **建议处置**：
  1. 回归项：**不经 UI**、直接由阅读器（Legado）发起朗读触发 `:tts_service` 首次创建，确认无崩溃（对应 TC-15 进程契约）；
  2. 加固建议（待 P7-C 评审）：在 `ConfigStore`/MMKV 访问入口加显式前置校验或惰性初始化，使同类疏漏**快速失败可诊断**而非崩溃。
- **状态**：**成立（历史记录）**；修复验证待办

---

### BUG-P7-011 ｜ **冷启动首请求被拒**：等待预算 1500 ms 差 83 ms 不足 ⇒ 朗读首句报错丢失 ｜ **P1**

- **模块**：`:engine`（`SynthesisCoordinator.awaitBackend()` / `READY_WAIT_MS`）
- **证据（真机可复现，2026-09-16 00:54，三方日志对齐）**：

  | 时刻 | 进程 | 日志原文 |
  |---|---|---|
  | 00:54:39.582 | Legado | `TextToSpeech: Sucessfully bound to com.kermond.ebook2tts` |
  | 00:54:39.583 | system | `TextToSpeechManagerPerUserService: Trying to start connection to TTS engine`（**系统连接**语义） |
  | 00:54:40.184 | Legado | `TTSReadAloudService 朗读内容添加完成`（开始逐句送出） |
  | **00:54:41.698** | **引擎** | **`WARN\|SYNTH_REJECT\|req=1\|reason=backend_not_ready\|state=INITIALIZING`** |
  | **00:54:41.699** | **Legado** | **`TTSUtteranceListener onError nowSpeak:25 … errorCode:-4`**（客户端可视错误） |
  | 00:54:41.781 | 引擎 | `reload ok model=kokoro-int8 sr=24000 speakers=103`（**模型仅晚 83 ms 就绪**） |
  | 00:54:42.561 | Legado | `TTSUtteranceListener onStart nowSpeak:26`（第二句正常） |

- **根因分析（代码 + 时间线一致）**：`awaitBackend()` 确有等待逻辑，但预算 `READY_WAIT_MS = 1500 ms`；本次请求于 ≈00:54:40.198 进入等待，**恰在 41.698 超时**，而模型于 41.781 就绪 ⇒ **预算比实际冷加载耗时少 83 ms**，判定为 `backend_not_ready` 并**直接 `callback.error()`**。
- **影响面（用户可感）**：第三方阅读器**冷启动朗读的第一句会报错/丢失**（客户端 `onError` → 该句不读）；发生条件＝`:tts_service` 进程尚未加载模型时（首次朗读、系统回收后、升级后首次使用）。设备越慢越易复现。
- **🔥 真机实测（Mi MIX 2S / SDM845，2026-09-16）**：kokoro-int8 冷加载 **12.09 s / 12.24 s（两次复现）**，而等待预算仅 **1500 ms** ⇒ 该机上**首个请求必然被拒**；**且"把预算提到 3000~5000 ms"仍覆盖不了 12 s** ⇒ 修复应以 **服务创建时异步预热** 为主、首请求排队兜底（预算提高仅作保底）。详见《P7-真机报告》§4.1。
- **与文档/红线关系**：与红线 3「绝不假成功」不冲突（确实报错而非静默），但属"不必要的中断/失败"⇒ 与 AR-§4.8「连续性」及 NFR「引擎就绪 ≤1.5 s」口径直接相关——**等待预算恰等于 NFR 目标值，未留余量**。
- **建议处置（三选一或组合，属行为变更）**：
  1. **提高预算**（如 `READY_WAIT_MS` 1500 → 3000~5000 ms）并保留"客户端中止即退出"语义；
  2. **服务创建时预热**模型加载（`LocalTextToSpeechService.onCreate` 异步预载），使首个请求命中已就绪后端；
  3. 首次请求在等待窗口内**不回落 error**，而是排队至就绪（注意不得违反"客户端中止立即静默退出"与红线 1/2）。
- **状态**：**成立（真机复现，P1）**；修复须回写文档（NFR 就绪口径 + AR-§4.8）后实施

---

### BUG-P7-013 ｜ **中止不唤醒就绪等待**：冷加载窗口内取消朗读最坏延迟 ≈ 等待预算 ｜ **P2**

- **模块**：`:engine`（`SynthesisCoordinator.requestStop()` / `awaitBackend()`）
- **证据（源码核验，分支 `p7/audit` = `3b257e2`）**：
  - `:124-126` `fun requestStop() { stopAtMs = SystemClock.uptimeMillis() }` ⇒ **只置标志，无唤醒动作**；
  - `:175-176` `signalReady()`（`readyCond.signalAll()`）**存在但唯一调用点在 `:233`＝重载完成路径**；
  - `:733-746` `awaitBackend()` 在锁内 `readyCond.await(remain)` 等待，每轮检查 `isStopped()`/状态 —— 但**唤醒源只有"就绪"与"超时"**。
- **影响面**：在引擎冷加载窗口内客户端取消朗读 ⇒ 合成线程**最坏需等剩余预算（≤1500 ms）才退出**，即"取消即时生效"在该窗口内不成立；**且若按 BUG-P7-011 的朴素方案提高预算到 5000 ms，该延迟将恶化到 ≈5 s** ⇒ 与"客户端中止须立即静默退出"的 AOSP 契约（AR-§1.4）背离。
- **关联**：独立复核方 agy 在验证 BUG-P7-011 时**主动发现**此点（`附件P`）；实施线已逐行核验确认。
- **建议处置**：在 `requestStop()`（及任何中止入口）中调用 `signalReady()`（或为停止引入独立条件变量），使等待中的合成线程**立即**感知中止；**必须先做此项，再执行 F-009 的预算提高**。
- **状态**：**成立（P2）**；与 F-009 同批修复

---

### BUG-P7-014 ｜ **每次试听结束后触发整模型重载** ⇒ 连续操作时长剧烈波动 ｜ **P2（真机确证；触发链与收敛方案待深挖）**

- **模块**：`:engine`（`ConfigStore.notifyReload()` 广播 → `LocalTextToSpeechService` → `coordinator.scheduleReload()`）＋ `:app` 桥接层
- **真机证据（Mi MIX 2S，2026-09-16）**：
  ```
  08:01:02.111  PREVIEW|start|voice=zf_4|chars=24            （热态，模型已驻留）
  08:01:33.803  PREVIEW|progress=100
  08:01:38.802  SherpaBackend: loaded sr=24000 … threads=7   ← 又整模型重载（≈5 s）
  08:01:39.105  PREVIEW|done
  ```
  同类现象在 07:58:26 那轮表现为 **总时长 82 s**（同一段 24 字）。
- **触发链（代码定位）**：`EngineBridgePlugin` 的 **`bridge_active_model` / `bridge_narrator` / `bridge_config`**（App 侧写配置或同步状态即发 `ACTION_ENGINE_RELOAD`）→ 引擎 `scheduleReload(reason)`（400 ms 去抖）→ `sm.requestBackendReload()` → **`reloadInternal()`（整模型重载）**；`ModelDownloader` 的 `model_installed/deleted/imported` 亦同。
- **影响**：真机单次重载 ≈5 s CPU（冷加载 12 s）⇒ **后一个请求要等前一个重载完成**，TTFT 与总时长剧烈波动（37 s ~ 82 s），并带来无谓耗电与发热（实测负载后 59.8–62.2 ℃）。
- **待办**：① 确认 Flutter 侧在"试听"流程里究竟调用哪条桥接方法；② 评估"**配置未实质变化则不重载**"的收敛策略（写前比对 + 按需重载）；③ 与 BUG-P7-011 的预热方案合并设计（预热与重载必须统一，否则互相打架）。
- **状态**：现象成立（真机确证）；修复方案并入 P7-D 性能批次

---

### BUG-P7-015 ｜ **预览通道自建后端**：每次试听都整模型加载 + 释放（真机 12 s/次；第 2 次达 80 s）｜ **P1**

- **模块**：`:engine`（`PreviewPlayer.preview()` 85–92 / 119 行）
- **代码证据**：
  ```kotlin
  val backend = SherpaBackend(dir, spec, ConfigStore.threads())  // :85 每次试听都新建实例
  backend.load()                                                 // :86 每次试听都整模型加载
  ...
  backend.release()                                              // :119 每次播放结束即释放
  ```
  ⇒ 与 `SynthesisCoordinator`（`:196` 自持实例）**各自加载同一模型**；预览通道**每次用完即释放**。
- **真机决定性测量（Mi MIX 2S，同文本同音色连续两次试听）**：
  ```
  +  0.00s PREVIEW|start → +12.20s SherpaBackend loaded → +12.25s playing   （第 1 次：首音 12.25 s）
  + 39.77s PREVIEW|start → +119.79s PREVIEW|done                            （第 2 次：总耗时 80.0 s）
  ```
- **影响**：真机上"试听"这一**高频交互**每次都要付整模型加载的代价；连续试听呈 12 s→80 s 恶化；内存反复起落；也是 BUG-P7-014（重载风暴）的放大器。
- **修复**：见《P7-C 修复方案 v0.2》**R1**（共享常驻后端）+ **R3**（不主动空闲释放）；已过 agy 独立复核（MUST-4）。
- **状态**：成立（代码 + 真机确证）；修复待实施与复测（第 2 次试听不得再出现 `BACKEND|load`）

---

## 三、"已查 · 无问题"登记（同批次抽检结论）

| 面 | 检查 | 结论 |
|---|---|---|
| 组件导出面 | `LocalTextToSpeechService`：`exported=true` 但受 `android.permission.BIND_TTS_SERVICE` 保护；`PreviewService`/`DownloadService`：`exported=false` | ✅ 符合 AOSP 引擎契约 |
| 前台服务合规 | `DownloadService` 声明 `foregroundServiceType="dataSync"` + 对应 `FOREGROUND_SERVICE_DATA_SYNC` 权限 | ✅ targetSdk 34 合规 |
| 明文流量 | `android:usesCleartextTraffic="false"` | ✅ |
| 异常吞没 | engine 侧 21 处泛型 catch 抽检 | ✅ 未见空 catch |
| 密钥日志 | 全仓检索 `Log.*(apiKey/token/secret)` | ✅ 仅命中脱敏告警文案（不含密钥值） |
| 构建/测试 | `:core:test` 73/73、`:engine:testDebugUnitTest` 46/46、`flutter analyze` 0 issues、`dart analyze --fatal-infos` 0 issues | ✅ 全绿（本批次复跑） |
| 崩溃/ANR 取证 | `dumpsys dropbox`（209 条）中本包记录 | ✅ 仅 2 条历史崩溃（BUG-P7-008）、**0 条 ANR** |
| native tombstone | `/data/tombstones/*` 全量检索本包名 | ✅ **无**本包记录（唯一期间新增的 `tombstone_18` 属我调用的 `uiautomator` 自身） |
| 本地合成快速起停 | 5 次点击（1 s 间隔）后进程存活、无 FATAL、无 ANR、无非预期强停 | ✅ 见基线报告 §五 |
| 依赖漏洞 | OSV（9 个 Maven 依赖 + Flutter 直接依赖） | ✅ 0 命中 |
| 16 KB 对齐 | APK 内全部 native 库对齐扫描 | ⚠️ 唯一不合规＝`libmmkv.so`（BUG-P7-004，已有升级方案） |

## 四、工具告警裁定

| 工具告警 | 位置 | 裁定 |
|---|---|---|
| Lint `SystemPermissionTypo`：`Did you mean android.permission.BIND_IMS_SERVICE?` | `engine/src/main/AndroidManifest.xml:15` | ❌ **原"误报"判定已撤销**（agy 复核纠正）：经 AOSP 4 个历史分支 + 官方权限参考页 + 设备 `pm list permissions`（955 条）+ `framework-res.apk` 字符串 四路查证，`android.permission.BIND_TTS_SERVICE` **不存在** ⇒ 转为真实缺陷 **BUG-P7-010** |

### BUG-P7-009 ｜ 空间预检用 `usableSpace` 而非 `getAllocatableBytes`（Lint `UsableSpace`）｜ **P3**

- **证据**：Android Lint（报告 125–144 行）`ModelDownloader.kt:69`
  > `Consider also using StorageManager#getAllocatableBytes and allocateBytes which will consider clearable cached data [UsableSpace]`
- **影响面**：容量预检（RQ-202「空间预检 2.5×」）可能**低估**可分配空间 ⇒ 极端情况下误报空间不足或下载中途失败。
- **建议处置**：改用 `StorageManager.getAllocatableBytes()`（API 26+，本项目 minSdk 27 全覆盖）；属行为变更 ⇒ 需先回写文档并真机验证预检语义。

### BUG-P7-010 ｜ 服务声明**不存在的平台权限** `android.permission.BIND_TTS_SERVICE` ｜ **P3（清单正确性；修改方向含安全含义）**

- **证据（四路官方/实测，2026-09-16）**：
  1. AOSP `frameworks/base/core/res/AndroidManifest.xml`（main，500+ 平台权限）：**无**该名，且**无任何 TTS 相关权限**；
  2. 同文件历史分支 `android-14/13/11/9.0.0_r1`：命中均为 **0**（非"曾存在后被移除"）；
  3. 官方 `Manifest.permission` 参考页（2.8 MB）：无该名（对照 `BIND_INPUT_METHOD` 命中 4 次，检索有效）；
  4. 设备真值（Android 15）：`pm list permissions -f` 955 条中**无**该权限；`framework-res.apk` 字符串 **0** 命中。
- **实测影响（关键）**：正常使用**不受影响**——2026-09-16 00:47 用真机 **Legado** 触发朗读，本引擎被成功唤起并完成在线合成（`ONLINE|chosen=online|voice=茉莉`、`PERF|req=2|chunks=96|ttfb_ms=717|total_ms=15176|drop=0`），连接方为 **system（uid 1000）**，无任何 `SecurityException`。
- **AOSP 源码依据**（`TextToSpeech.java`，main）：
  > "Currently all the clients are routed through the **System connection**. Direct connection is left for debugging, testing and benchmarking purposes."
  ⇒ 正常客户端由 system_server 绑定（system uid 绕过组件权限校验）；而**第三方直接 `bindService`**（`DirectConnection` 路径）因权限名无效**必然被拒**。
- **⚠️ 修改方向的反向风险**：若照 Lint 字面建议**直接删除**该属性，服务将变为**对任意应用开放直接绑定** ⇒ 安全隐患**变大**。故本条**不可盲改**。
- **建议处置（待决）**：① 查官方 TTS 引擎实践确认规范声明；② 若要保留"仅系统连接可绑"的现状语义，改为**自建 signature 级权限**（自带 `<permission>` 声明）；③ 任一方案均须真机验证两点：**系统连接（阅读器朗读 + 系统设置样例）仍可用**、**直接绑定策略符合预期**。
- **状态**：**成立**（P3）；修复方案待甲方/agy 共同定案

### BUG-P7-012 ｜ `:app` 侧死资源：`res/drawable-v21` 目录多余（minSdk 27）｜ **P3**

- **证据**：`:app:lintDebug` 告警（0 errors / 8 warnings 中的 `ObsoleteSdkInt`）
  > `app/android/app/src/main/res/drawable-v21: This folder configuration (v21) is unnecessary; minSdkVersion is 27. Merge all the resources in this folder into drawable.`
- **影响**：无功能影响（可维护性/包体微增）。**处置**：并入 P3 批量清理（F-008）。
- **状态**：成立（P3）

## 五、待办与未覆盖面（本台账下一版补）

**已完成（本轮）**
1. ✅ Android Lint（`:engine`）与 detekt 1.23.8（`:core`/`:engine`）全量扫描并归档原文；
2. ✅ 用户可感缺陷首批定位：错误码错位（P1）、备份/密钥（P2）、通知权限（P2）、16 KB 对齐（P1~P2，**修复路径已实测**）、历史崩溃（P2）；
3. ✅ 依赖漏洞 OSV 核对（0 命中）与版本陈旧清单；
4. ✅ APK 内 native 库对齐全量扫描（唯一不合规＝libmmkv.so）；
5. ✅ 设备崩溃/ANR 取证（2 条历史崩溃、0 ANR、0 native tombstone）；
6. ✅ 本地合成基线 + 快速起停冒烟（无崩溃、无 ANR、无强停）。

**未完成（下一版补）**
7. agy 独立复核本台账（分级与结论）→ 复核意见与处置表随 v0.3 登记；
8. ✅ Android Lint 覆盖 **`:app`**（Flutter 驱动 `:app:lintDebug`，0 errors / 8 warnings，见基线报告 §五之四）；**未做** `lintRelease`（需 release 构建授权）
9. detekt 补 classpath 复跑 + detekt 2.0.0-alpha.6 复测；
10. OWASP dependency-check（NVD 库下载与速率评估）—— 或用 OSV 其它生态补充；
11. 动态面：StrictMode、Perfetto/simpleperf、LeakCanary（**多进程**有效性先实测）、异常注入（含 DNS 黑洞/弱网）、2 次/秒 10 分钟压测、锁屏 2h、200 次服务重建；
12. 红线段人工走查（红线 5/6/7 专项，计划 §四）；
13. `:app` 侧人工走查（六页状态、平台通道、dispose、错误提示人话）；
14. 引擎就绪 ≤1.5 s 测点（**不经 UI** 由阅读器驱动的进程契约路径，同时覆盖 BUG-P7-008 回归）。

---

## 六、修复方案建议（**待甲方批准；本轮未改动任何代码**）

> 依据计划 §3.3 白名单与 AGENTS.md §1「改行为先改文档」：下列方案**尚未执行**，每项须"文档回写 → 实施 → 自测 → agy 复核 → 甲方确认合并"。

| 编号 | 缺陷 | 建议修复 | 变更类型 | 前置条件 | 验证方式 |
|---|---|---|---|---|---|
| F-001 | BUG-P7-001 错误码错位 | `SynthesisCoordinator` 三处 `callback.error()` 改用 `android.speech.tts.TextToSpeech.ERROR_SYNTHESIS` / `ERROR_NOT_INSTALLED_YET`；删除 `TextToSpeechErrors` 自造常量；单测固化数值断言 | 行为变更（客户端可见错误码） | 先回写《架构书》§4.7 错误语义总表 + 《实现报告》IM 续排 | 真机：零模型场景由 Legado 触发 → 客户端收到 `-9`；单测断言常量值 |
| F-002 | BUG-P7-002 密钥随备份外泄 | 采用 agy 建议的**首选方案**：`dataExtractionRules`（Android 12+）+ `fullBackupContent`（向下兼容）显式排除 MMKV 目录；**不改**存储层（保留 MMKV 明文，避免大改） | 行为变更（备份范围） | 回写文档（隐私/合规条目） | `adb backup`/备份规则单测 + 真机确认模型与配置不受影响 |
| F-003 | BUG-P7-003 通知权限缺失 | Flutter 侧在合适时机请求 `POST_NOTIFICATIONS`（Android 13+），拒绝时给设置页引导 | 行为变更（新增权限弹窗） | 回写文档（RQ-202 体验口径） | 真机：拒绝/允许两态下下载通知可见性对照 |
| F-004 | BUG-P7-004 16 KB 对齐 | `com.tencent:mmkv` **1.3.9 → 1.3.17** | **依赖变更（需甲方逐条批准）** | 白名单批准 | 重新构建后 `llvm-readelf` 复核 arm64/x86_64 `LOAD` 对齐＝0x4000；回归 `:core`/`:engine` 单测与真机合成 |
| F-005 | BUG-P7-008 历史崩溃回归 | 不改代码，先做回归：**不经 UI** 由 Legado 触发 `:tts_service` 首次创建 | 无（验证项） | — | `dumpsys dropbox` 无新崩溃；服务创建成功 |
| F-006 | BUG-P7-009 空间预检 | `usableSpace` → `StorageManager.getAllocatableBytes()` | 行为变更（预检口径） | 回写文档（RQ-202 空间预检） | 单测 + 真机预检对照 |
| F-007 | BUG-P7-010 无效权限声明 | **暂缓**：先查明官方 TTS 引擎规范声明方式；候选方案＝① 改自建 signature 权限；② 删除声明并接受直接绑定 | 行为/安全语义变更 | agy 二次论证 + 真机双向验证 | 系统连接可用性 + 直接绑定策略验证 |
| F-008 | BUG-P7-005/006/007 | P3 批量清理（死代码、detekt 基线化、依赖例行升级） | 低风险整理 | 排期 | 静态门复跑无新增 |
| **F-009** | **BUG-P7-011 冷启首请求被拒** | **前置必做**：先让中止唤醒等待者（`requestStop()` → `signalReady()`，见 BUG-P7-013），**否则禁止提高预算**；随后方案①提高 `READY_WAIT_MS`（1500 → 3000~5000 ms）；方案②`LocalTextToSpeechService.onCreate` 异步预热模型；**建议 前置+①+② 同批**（②治本、①兜底、前置保契约） | 行为变更（客户端首句成功率 + 取消时延） | 回写 NFR 就绪口径 + AR-§4.8 / §1.4 | ①复现脚本：`force-stop` → Legado 朗读 → 客户端**无** `onError`、首句 `onStart` 成功（重复 5 次）；②取消回归：冷加载窗口内取消 ⇒ 进程在 ≤200 ms 内退出等待（打点验证） |

**排序建议**：F-004（唯一外部合规硬项）→ F-001（影响验收判据）→ F-003/F-002（用户体验与合规）→ F-005（回归）→ F-006/F-008（整理）。

---

## 七、跨应用链路实测（新增证据，2026-09-16 00:47）

| 项 | 结果 |
|---|---|
| 场景 | 真机 Legado（第三方应用，uid 10056）点击「朗读」朗读当前章节 |
| 结果 | 引擎被唤起并完成合成；**无 SecurityException**；服务连接方＝system（uid 1000） |
| 在线打点 | `ONLINE|voice_map|from=晓芷·温婉女声|gender=FEMALE|to=茉莉` → `ONLINE|chosen=online|model=mimo-v2.5-tts|voice=茉莉|sr=24000` |
| 在线性能（模拟器相对值） | `ONLINE|ttfb_ms = 509 / 710`；`PERF|req=2|segs=1|chunks=96|ttfb_ms=717|total_ms=15176|drop=0`；`PERF|req=3|chunks=61|ttfb_ms=819|total_ms=9818|drop=0` |
| 观察项 | `W/PlaybackSynthesisRequest: done() was called before start() call` —— 属 AOSP 在 STOPPED 态的正常提示（与 AR-§1.4 约定一致），**非缺陷**，登记为观察项 |
| 副作用（如实登记） | 该测试经甲方已开启的**在线**配置发出 **3 次**在线合成请求（消耗其 MiMo 额度）；测试后已 `force-stop` Legado 停止朗读；Legado 阅读进度可能小幅前移（由 6/12 起读） |
| 设备状态恢复 | Legado 已停止；本 App 保持安装、配置未改动 |

---

### BUG-P7-016 ｜ 预览播放链路阻塞：首个试听请求长时间不结束 ⇒ 第二个请求排队数十秒 ｜ **P1（待定位）**

- **模块**：`:engine`（`PreviewPlayer.preview()`：`startTrack()` → `track.write()` 循环）
- **真机证据（P7-C 修复后，Mi MIX 2S）**：
  ```
  08:27:50.211  PREVIEW|start        （第 1 次）
  08:28:02.494  SherpaBackend loaded （协调器预热加载，全会话仅此 1 次）
  08:28:02.514  PREVIEW|playing      （首音；此后**没有** progress=100 / done）
  08:28:21.960  PREVIEW|start        （第 2 次）
  …57 s 后仍无 playing/progress/done
  ```
- **关键判据**：本次会话整模型加载次数 = **1**（R1/R3 生效，无重复加载），但
  ①第 1 次试听在 `playing` 之后**未出现** `progress=100`/`done`；②第 2 次请求因 `PreviewPlayer`
  使用**单线程 executor** 而被前一个未结束的任务阻塞。
- **初步判断（**未定论**）**：阻塞点位于音频播放链路（`AudioTrack` 写入/焦点/HAL）而非模型加载；
  需插桩定位（见下）。
- **下一步（插桩方案）**：在预览路径加打点并复测——
  `PREVIEW|track_open|sr=…|buf=…|ms=`、`PREVIEW|synth_begin/seg=…|chars=…`、`PREVIEW|synth_end|ms=`、
  `PREVIEW|write|bytes=…|ms=`、`PREVIEW|track_release|ms=`；同时对 `AudioTrack.write` 的返回值/耗时设阈值告警。
  同时复核 `stop()`/`releaseTrack()` 与写循环的竞态（红线 2：已有音频不得强停）。
- **状态**：成立（现象确证）；根因待插桩

#### BUG-P7-016 追加：插桩定位结果（2026-09-16 08:32，Mi MIX 2S）

```
08:32:32.109  PREVIEW|start
08:32:44.454  SherpaBackend loaded          （协调器预热，全会话 1 次）
08:32:44.466  PREVIEW|backend_ready|borrowed=true   ← R1 生效：预览借用了协调器后端
08:32:44.481  PREVIEW|playing
08:32:44.538  PREVIEW|track_open|ok=true|ms=68      ← AudioTrack 打开正常（非阻塞点）
08:33:06.861  PREVIEW|start（第 2 次）               ← 22 s 内**无任何 synth 打点**
```

- **阻塞区间已缩小**：`track_open` 之后、首个 `PREVIEW|synth` 之前 ⇒ 即 **`backend.generatePcm()` 原生合成调用**（`NativeGate` 内）或其前置（`TextAnalyzer.analyze`）。
- `NativeGate` 本身是 `ReentrantLock`（无自旋），故 133% CPU 属**真实计算**而非锁自旋。
- **新观察（待定论）**：同进程内出现 **4 个 `tts-reload` 线程处于运行态**，而 `reloadExecutor` 是 `newSingleThreadExecutor`（正常只应有 1 个线程）⇒ 强烈提示**多协调器实例并存**：`CoordinatorHolder` 在 `LocalTextToSpeechService.onDestroy` 时 `detach`，若此时预览仍在使用，下一次预览会 `getOrCreate` **再创建一个协调器并再次预热**。
- **下一步**：① 把协调器改为**进程生命周期单例**（服务销毁不 shutdown/detach，交由进程回收）；② 复测确认单实例；③ 若阻塞仍在，再对 `generatePcm` 分段计时（sherpa `generate` 回调粒度）。

#### BUG-P7-016 追加二：**纠正上一条推断**（7 个 `tts-reload` ≠ 7 个协调器）+ 阻塞点确证

**纠正（我上一条的"多协调器实例"推断是错的）**——线程栈逐条核对：
- `tts-reload` sysTid 30998：`LockSupport.park` ⇒ 唯一那个**单线程 executor 的空闲 worker** ✓；
- `tts-reload` sysTid 30999–31004（共 6 个）：栈底全在 **`libonnxruntime.so` 内部**（futex 条件等待）⇒ 它们是
  **onnxruntime 的 intra-op 线程池**，只是 Linux `comm` **继承**了创建者线程名，看起来像 `tts-reload`；
  数量恰好 = `threads=7` 配置（6 worker + 1 主）✓。
- 交叉验证：`dumpsys meminfo` Native Heap **341 MB**（**一份**模型，非 7 份）；本次会话 `SherpaBackend loaded` **1 次**。
- ⇒ **单例修正生效**：进程内仅 1 个协调器、1 份模型、1 次加载（R1/R3/R6b 结论成立，不受影响）。

**阻塞点确证（`debuggerd -b`，卡住 37 s 时抓取）**：
```
"preview-player" sysTid=30997
  #00..#13  libonnxruntime.so（推理内核）
  #14..#18  libsherpa-onnx-jni.so
  #19       Java_com_k2fsa_sherpa_onnx_OfflineTts_generateImpl+304
  #26       com.k2fsa.sherpa.onnx.OfflineTts.generate
  #31       com.kermond.ebook2tts.engine.SherpaBackend.generatePcmLocked
  #56       PreviewPlayer$preview$1（预览线程）
```
- ⇒ 预览线程**不在锁上、不在音频写入上**，而是**正在执行原生推理**，只是**极慢**（24 字 > 37 s；
  同机早先基线同一文本合成+播放 ≈ 7 s）。
- 排除：锁等待（无 `ReentrantLock`/`NativeGate` 帧）、AudioTrack 写入（`track_open ok=60 ms` 后无写入帧）、
  多模型争抢（仅 1 份模型）。
- 下一批定位（不写设备/ROM 分支）：① 同机对比 `threads=7 / 4 / 2` 的合成耗时（疑 ONNX intra-op 在
  SDM845 大核调度下的病态表现）；② 对比系统通道（Legado 本地朗读）同文本耗时，判断是否预览路径特有；
  ③ 记录合成期间 `/proc/<pid>/stat` 各线程 CPU 与温度/频率（是否热降频）。

---

### BUG-P7-017 ｜ 低端 ARM 真机上本地合成**实际单线程 + 大核不升频** ⇒ 试听/朗读达到"不可用级"慢 ｜ **P1**

- **真机证据（Mi MIX 2S / SDM845，合成进行中 40 s 采样，`scripts/p7c-synth-profiler.sh`）**：
  ```
  t+18s..t+36s  频率(kHz)=1766400,1766400,1766400,1766400,825600,825600,825600,825600
  load=5.64 3.68 1.81    最高温采样异常（thermal_zone 单位混用，待修脚本）
  ```
  - 小核簇 4 核恒在 **1,766,400 kHz**（1.77 GHz，即小核上限）；大核簇 4 核基本停在
    **825,600 kHz（825 MHz）**，其上限为 2,803,200 kHz（2.80 GHz）⇒ **大核全程未升频**。
- **结合线程栈（同批次 `debuggerd`）**：`preview-player` 在 ORT 推理内核里，而 onnxruntime 的
  **6 个 intra-op 线程全程 parked**（`libonnxruntime.so` 内部条件等待）⇒ `threads=7` 配置
  **实质上只有一个核在算**（24 字 > 37 s；模拟器 x86_64 同文本 ≈ 1.6 s）。
- **结论**：本问题是**算力/线程配置**问题，与 ROM 差异无关（不需要"适配各家 ROM"）；
  它同时解释了 BUG-P7-016 的"卡住"现象——不是死锁，是**慢到看起来卡住**。
- **影响面**：SDM845 这类 2018 中端 SoC 上，"试听"与"朗读"均不可用（首音数十秒），
  而模拟器（宿主 x86_64 CPU）看不出来 ⇒ 与 BUG-P7-011 同源：**模拟器不能作为性能结论的唯一依据**。
- **下一步（全部设备无关）**：① 验证 onnxruntime intra-op 线程为何未参与（sherpa 会话参数 / 算子级串行）；
  ② 对比 `threads=7/4/2` 的合成耗时，确认是否存在"线程多反而更慢"的争抢；③ 评估**试听路径改用更小模型**
  或**合成结果缓存**（同一文本+音色复用）；④ 采样时修正 thermal_zone 单位混用（当前取 max 会取到非温度区）。

#### BUG-P7-017 追加：模拟器（修复版）跑通的对照数据 —— 量化"慢"而非"卡"

模拟器 MuMu x86_64 / Android 15（1080x1920），修复版 `1d0540f` 同文本同音色（24 字）：
```
PREVIEW|backend_ready|borrowed=true|sr=24000   ← R1 共享后端在模拟器同样生效
PREVIEW|playing
PREVIEW|track_open|ok=true|ms=16
PREVIEW|synth|chars=24|bytes=246562|ms=4703    ← 合成 4.70 s
PREVIEW|write|bytes=246562/246562|ms=5052      ← 写入 5.05 s（=音频时长，正常边播边写）
PREVIEW|progress=100 → PREVIEW|segments_done|count=1|total_ms=9774 → PREVIEW|done   ← **全程无卡死**
```
- 音频时长 = 246562 B ÷ 2 B/样本 ÷ 24000 Hz ≈ **5.14 s** ⇒ 本机 RTF ≈ **0.92**（近乎 1:1）。
- 即：**在桌面级 x86_64 CPU 上，本模型合成也几乎不快于实时**；真机 SDM845 慢 5 倍以上 ⇒ 37 s+/句。
- ⇒ 真机"卡住"＝**慢到看起来像卡住**（BUG-P7-016 结论一致），且与 ROM 无关。
- **排查记录**：`numThreads` 已正确下发（`SherpaBackend` 中 `OfflineTtsModelConfig(numThreads = …)`，kokoro/vits 两个分支均有）⇒ 单线程行为源自 sherpa/ORT 的 Kokoro 合成路径本身，而非本仓配置写错。
- **待澄清（诚实记录）**：P7-A 基线报告曾记"模拟器本地试听 TTFT 1.52–1.64 s"，与今日 9.77 s 总耗时/4.70 s 合成明显不一致
  ⇒ 需复核当时口径（疑似测的是"首块音频"而非整句合成完成，或文本/音色不同）。**在澄清前，不引用该数字做结论。**

#### BUG-P7-017 追加三：**线程数 A/B 实测（E3-v2，真机 SDM845，2026-09-16 19:20~19:28）**

同一文本（24 字）、同音色、同模型（kokoro-int8）、每次冷启后单次试听：

| 推理线程 | 合成耗时 | `segments_done.total_ms` | 触发→完成 | 主进程 PSS | 输出音频 |
|---|---|---|---|---|---|
| **7**（现状默认） | **107,994 ms** | 113,724 ms | 142 s | 344 MB | 272,766 B (≈5.68 s) |
| **4** | **83,611 ms** | 89,343 ms | 121 s | 335 MB | 272,766 B |
| **2** | **69,048 ms** | 74,764 ms | 105 s | 388 MB | 273,238 B |

**结论（硬数据）**
1. **线程越多越慢**：7 → 4 → 2 线程，合成耗时 108 s → 83.6 s → 69 s，**2 线程比默认 7 线程快 36%**。
   ⇒ 典型 big.LITTLE 过度订阅：算子无法有效并行时，7 线程只带来争抢与大小核迁移开销。
   ⇒ **BUG-P7-017 的直接修法之一：把推理线程数上限压到 ≤4（实测 2 最优）**，且与 SDK 默认 `cores-1` 的做法相反。
2. **模型对该机过重**：5.68 s 音频需 69~108 s 合成 ⇒ **RTF ≈ 12~19×**（对照：模拟器 x86_64 RTF≈0.92）。
   即使按最优线程（2），首音仍需 ~69 s ⇒ **本机属"本地模式不可用"区间**，与需求书 RQ-515/516 的低配警告口径吻合。
3. 覆盖钩子有效路径已验证：`THREADS|override|n=7/4/2` 与 `loaded ... threads=7/4/2` 一致（假仪表已修正为打印实际生效值）。

**E4 修法（据本数据定案）**
- **必做**：推理线程数**上限 ≤4**（可优先 2~4 自适应），替代 `cores-1`；跨设备回归验证（模拟器 + 真机）。
- **必做**：低配设备**默认推荐更轻模型档位**（VITS 118.8 MB < Kokoro 147 MB，且算子更轻），与 RQ-515 警告联动。
- **建议**：试听/重复朗读的**同文本+音色结果缓存**（避免同句重复付出数十秒）。
- **不做**：靠"减线程"当成可用性修复——它只把 108 s 压到 69 s，仍远超可用阈值；**必须与模型档位/缓存组合**。

#### BUG-P7-017 追加四：**E4 修法修正 —— 发现与 IM-522 实测冲突，不能一刀切降线程**

**冲突事实**
- 本次 E3 实测（真机 Mi MIX 2S / SDM845 / **4×A75 + 4×A55**）：线程 7 → 4 → 2 = 108 s → 83.6 s → **69 s**，
  ⇒ 结论"**线程越多越慢**"。
- 既有 IM-522 实测（P6《批次计划-P0缺陷修复》§六 ③ 与《实现报告》IM-522 行）：**6 核设备**线程数由写死 2 改为 5
  ⇒ RTF **1.60 → 1.37~1.42**，⇒ 结论"**线程越多越快**"。

**判定（不推翻任何一侧）**
两者并不矛盾，而是**受核簇拓扑支配**：
- SDM845 = **big.LITTLE**（4 大 + 4 小）。`cores-1 = 7` 会让 ORT 起 7 个线程去争 4 个大核，并把工作迁到 1.76 GHz 小核，
  同时吃内存带宽 ⇒ **过度订阅，越加越慢**（本次实测）。
- IM-522 的 6 核设备（同构核）⇒ 线程增加即真实并行度增加 ⇒ **越快**。
⇒ 原实现的 `clamp(cores-1, 2, 8)` 在**同构核**上合理，在 **big.LITTLE** 上有害。

**E4 修法（修正版，待实施）**
1. **改为拓扑感知**：自动线程数上限 = **性能核（大核）数量**，即 `clamp(min(bigCores, cores-1), MIN, MAX)`；
   大核数由各核 `cpufreq/cpuinfo_max_freq` 分簇判定（行业通行做法），**不按 SoC 型号硬编码**；
   用户显式设置仍优先（保持既有 `engine.threads` 语义，允许覆盖到 8）。
2. **文档先行**（AGENTS.md 第 1 节）：架构书新增 **ADR-018 —— 推理线程数按核簇拓扑分配**（并注明 IM-522 结论的适用范围），
   需求书 §10 补 RQ-517（自动线程数不得按"核数-1"分配）；实现报告新增 IM-541（拓扑感知实现）与 IM-542（跨设备 RTF 对照）。
3. **跨设备验证（必做）**：模拟器 x86_64 与真机 SDM845 双环境跑同一 A/B（2/4/大核数/保持原值），
   数字必须能解释"同构核增加线程更快、异构核超过大核数更慢"这一规律；**只在一台机器上过不算过**。
4. **不动**：不按 SoC 型号白名单调优（避免"单机特调"）；不改 `MIN=2`。

#### BUG-P7-017 追加五：**IM-541 端到端验证通过（真机，自动取值，无任何覆盖）**

2026-09-16 23:44~23:54，真机 Mi MIX 2S（WiFi 链路 `[LAN-TEST-DEVICE]:42345`），全新 debug 包，**未放置任何线程覆盖文件**：

```
▸ 实际生效线程: threads=4          ← 拓扑感知自动取值（cpu4-7 大核 = 4），与旧实现 cores-1=7 相比自动收敛
▸ 合成耗时: chars=24 bytes=272954 ms=84671
▸ 完成打点: segments_done|count=1|total_ms=90397
▸ 触发→完成: 120s（含启动 11s 与装机后首次冷加载）
```

**对照（同机同文本同音色）**
| 线程 | 合成耗时 | 来源 |
|---|---|---|
| 7（旧默认） | 107,994 ms | E3 手工覆盖 |
| **4（新默认，自动）** | **84,671 ms** | 本次，无覆盖 |
| 2 | 69,048 ms | E3 手工覆盖 |

⇒ **自动配置即拿到 -21.6%**（108.0 s → 84.7 s），无需用户干预；`2` 仍更快但仅为手工档，
保留给用户显式设置（`engine.threads`）。

**操作性记录**：改走 WiFi 后，1.5 GB debug 包装机约 **7 分钟**（USB 时约 30 秒）。
后续真机验证应尽量减少整包安装（用增量或缩小 debug 包），否则每轮验证都要付这份时间。

#### BUG-P7-017 追加六：**IM-542 跨设备对照（模拟器 x86_64，同构核）**

2026-09-16 23:52~23:55，MuMu x86_64 / Android 15（1080x1920），同文本同音色，每档冷启后单次试听：

| 线程 | 合成耗时 | `total_ms` | 触发→完成 | 备注 |
|---|---|---|---|---|
| 2 | 5,440 ms | （见日志） | ~28 s | 手工覆盖 |
| 4 | 4,726 ms | 9,990 ms | 28 s | 手工覆盖 |
| 7（旧默认） | **5,709 ms** | 10,796 ms | 29 s | 手工覆盖 |
| **自动（拓扑感知）** | **4,656 ms** | 9,735 ms | 28 s | **无覆盖**，实际取值 **threads=5**（同构核 ⇒ `cores-1`，与 IM-522 行为一致） |

### 跨设备对照（同一改动、同一文本）

| 平台 | 拓扑 | 2 | 4 | 7（旧默认） | 自动（新默认） |
|---|---|---|---|---|---|
| **真机 SDM845** | 4 大 + 4 小（异构） | **69.0 s** | 84.7 s | 108.0 s | **84.7 s（=4 线程）** |
| **模拟器 x86_64** | 同频 VM（同构判定） | 5.44 s | 4.73 s | **5.71 s** | **4.66 s（=5 线程）** |

**双侧一致与差异**
1. **两边都支持"旧默认 7 线程最差"**：真机 108.0 s（最慢）、模拟器 5.71 s（最慢）⇒ 新实现的自动取值在两侧都优于旧默认；
2. **异构核**：线程越少越快，最优在 **2**（69.0 s）；自动取值 4 是**保守但可复现**的选择（-21.6%）；
3. **同构核**：最优在 **4~5**，2 与 7 都差 ⇒ 与 IM-522"同构核加线程有收益"方向一致（其适用域成立）；
4. **诚实标注**：模拟器侧各档差异仅 ~1 s（≈20%），带宽/调度噪声占比可观，**不据此下强结论**；
   真机侧差异 39 s（56%）远超噪声，结论可靠。
5. **待改进（记录，不在本批实施）**：异构核上"最优 = 2"低于"大核数 = 4"，说明可进一步按
   大核数的一半或按实测微基准自适应；本批取**可复现的保守值**（ADR-018），避免过度调参。

---

### BUG-P7-016 ｜ 结案（2026-09-17 00:00，证据见下）｜ 状态：**已修复 / 关闭**

**复测方法**：真机 Mi MIX 2S（WiFi 链路），IM-541 后**连续两次试听**（同一会话，不重启 App）。

| 指标 | 修复前（2026-09-16 早） | 修复后（本次） |
|---|---|---|
| 第 1 次试听 | 12.25 s（含整模型加载） | 105 s（synth 84.8 s） |
| 第 2 次试听 | **80.0 s（病态，无打点）** | **93 s（synth 85.0 s）** |
| 整模型加载次数 | 每次试听各 1 次（自建+释放） | **全会话 1 次** ✓ |
| 两次合成耗时一致性 | 1 次 vs 80 s（极不对称） | 84,790 ms vs 84,997 ms（差 **0.24%**） |

**结案理由**
1. **"第二次 80 s"的不对称病态消失**：两次合成耗时基本一致（<0.3% 差），行为可预测；
2. **整模型加载从"每次试听 1 次"降为"全会话 1 次"**（R1 共享后端 + 进程级单例生效，`debuggerd` 栈亦已排除死锁）；
3. **剩余 85 s/句属模型重量问题，不是本 Bug 的一部分** ⇒ 由 BUG-P7-017（RTF 12~19×）与 RQ-515/516（低配警告）+ IM-543（轻量模型档位）承接。

**遗留（不影响结案）**：`synth` 耗时 85 s 仍远超可用阈值；低配设备上"本地模式不可用"是**已被甲方接受的结果**（RQ-516）。

---

### E6 起步：低配判定的数据来源核实（2026-09-17 00:0x）

**本机（真机）实测型号串**（`getprop`，可直接用于阈值表）：
```
ro.product.model      = Mi MIX 2S
ro.product.board      = sdm845
ro.board.platform     = sdm845
ro.soc.model          = SDM845          ← Android 12+（本机 sdk=34）可用，粘性标识
ro.soc.manufacturer   = Qualcomm
```
⇒ **判定实现以 `ro.soc.model` 为主键可行**（低配阈值：低于骁龙 8 Gen 1 / 低于天玑 9300 需按此串建表）。

**玄戒（XRING）例外项的核实结果（不得编造）**
- **XRING O1**：公开资料可证已上市（Xiaomi 15S Pro / Pad 7 Ultra，10 核，TSMC N3E）——但**其 `ro.soc.model` 具体字符串未能从公开资料核实**；
- **XRING O3**：**公开资料未见**（仅有 O2 在研的报道）⇒ 字符串无法核实。
- **处置**：例外判定**按厂商/家族模式匹配**（`ro.soc.manufacturer`/`ro.soc.model` 命中 XRING 家族关键字即豁免告警），
  **不硬编码具体型号串**；并在实现中标注"**O1/O3 实际串待实测确认**"（拿到真机或 `getprop` 转储后回填）。
  该做法同时满足"玄戒不告警"（RQ-515）与"不猜事实"两条约束。

---

### E6 实施：LocalModeAdvisor（P7 / RQ-515 · RQ-516 / IM-543）已实现并通过单测

**实现**：`core/LocalModeAdvisor.kt`（纯函数，可单测）
- `judge(socModel, manufacturer, cores) → Verdict(warn, reason)`
- `perfCoreCount` 同源的阈值口径：**骁龙 ≥ 8 Gen 1（SM8450+）/ 天玑 ≥ 9300（MT698x）/ 核数 < 4 一律低配**；
- **玄戒（XRING）族豁免**：按族名模式匹配（`xring|玄戒`），**不硬编码 O1/O3 型号串**（未实测确认，见上文）；
- **fail-open**：取不到型号 / 其它厂商（Tensor、Exynos、麒麟…）**不误伤**（不告警）并留 `reason` 便于后续用真实数据收紧；
- 警告文案常量 `WARNING_TEXT = "性能不足，可能延迟极大"`（与 RQ-515 原文一致）。

**测试**：`core/LocalModeAdvisorTest.kt` 7 例全绿（**core 86/86**）：
- 本机真实案例 **SDM845 → 告警**（`snapdragon_before_8gen1`）✓
- SM8450/8475/8550/8650/8750 → 不告警 ✓
- 天玑 9300 / MT6989 → 不告警；天玑 1200 / MT6895 → 告警 ✓
- 玄戒 O1/O3（含中文"玄戒"）→ 豁免 ✓
- 核数 2/3 → 告警（与型号无关的硬门槛）✓
- 取不到型号 / Tensor / Exynos → fail-open 不误伤 ✓

**E6 未完成部分（明确标注，不含糊）**
1. **Android 侧接线**：把判定接到"用户选择本地模式"的 UI 流程，弹 RQ-515 警告（需动 Flutter/引擎桥，属行为改动，放在与 UI 同批）；
2. **运行时微基准兜底**：尚未实现（需要一次可控的本地合成采样，建议与缓存机制同批做，避免重复付出 85 s）；
3. **玄戒 O1/O3 实际型号串**：待实测确认后回填（当前用族名匹配已满足"不告警"要求）。
