# P7-A 基线与环境报告（v0.1）

> 批次：P7-A ｜ 生成：2026-09-16 ｜ 实施线：Hermes ｜ 复核：agy（见 §八）
> 分支：`p7/audit`（基线 `3b257e2`）｜ 主仓 `main` 未改动
> **口径声明（甲方 DQ-P7-2）**：本报告全部性能数据来自 **MuMu 模拟器（x86_64 / Android 15 / 宿主 [LAN-WIN-HOST]）**，属**相对值与趋势基线**，**不作为**《需求书》§5.1 绝对预算的达标判定；ARM 真机数据缺位。

---

## 一、设备与环境

| 项 | 值 |
|---|---|
| 调试目标 | MuMu 模拟器 `[LAN-WIN-HOST]:7555`（adb 在线；`-s` 指定可用） |
| 属性 | `ro.product.cpu.abi=x86_64`、Android **15**、API 35、机型串 `23117RK66C`、8 GB RAM、`/data` 余 ~116 GB |
| 被测包 | `com.kermond.ebook2tts` v(0.2.0-alpha.1) versionCode 1，targetSdk 34，安装于 09-15 23:19 |
| 同装参照 | Legado `com.legado.app.release`（真实阅读器参照物） |
| 免代理直连 | 模拟器 NAT（`[EMU-NAT]`）→ 可直连本服务器（ping 0.7 ms） |
| 采集协议脚本 | `[WORKDIR]/p7-evidence/scripts/p7a-perf-protocol.sh`；原始证据 `[WORKDIR]/p7-evidence/perf/` |

---

## 二、冷启动（Flutter UI 冷启，`am start -W` ×5，先 force-stop）

| 轮次 | TotalTime (ms) | WaitTime (ms) |
|---|---|---|
| 1 | 2103 | 2105 |
| 2 | 1816 | 1819 |
| 3 | 1959 | 1961 |
| 4 | 1832 | 1834 |
| 5 | 2006 | 2010 |

**中位数 ≈ 1959 ms，区间 1816–2103 ms**（debug 构建 / x86_64 模拟器）。
> 注：这是 **UI 冷启**，并非《需求书》§5.1「引擎就绪 ≤1.5 s」；引擎就绪口径需以合成请求驱动（见 §四），本轮已具备可测手段，待补测。

---

## 三、内存基线（两进程）

| 进程 | TOTAL PSS | TOTAL RSS | 备注 |
|---|---|---|---|
| `com.kermond.ebook2tts`（主进程/Flutter） | 合计见下 | 387 MB | 截图：Java Heap 10916 KB / Native Heap 49464 KB |
| `com.kermond.ebook2tts:tts_service`（引擎） | **156,895 KB（≈153 MB）** | 240 MB | 模型已加载、空闲态 |
| 两进程合计（`dumpsys meminfo <pkg>`） | **314,567 KB（≈307 MB）** | 404 MB | — |

> 口径提醒：debug 构建 + x86_64 模拟器，**不可**与 NFR「引擎 PSS ≤220 MB / 峰值 ≤400 MB」直接比对；此处仅作**后续 A/B 的基线锚点**。

---

## 四、合成基线（本地强制路径：音色库试听，IM-518）

打点为引擎既有 `PREVIEW|…`（`:tts_service` 进程）：

| 轮次 | `PREVIEW|start` | `PREVIEW|playing`（首音） | TTFT（首音−start） | `PREVIEW|done` | 总时长 |
|---|---|---|---|---|---|---|
| 1（模型冷加载） | 00:40:44.059 | 00:40:45.701 | **1.642 s** | 00:40:55.492 | 11.43 s |
| 2（模型已驻留） | 00:41:24.039 | 00:41:25.559 | **1.520 s** | 00:41:35.473 | 11.43 s |

- 文本：`voice=zf_3`，**chars=24**，`speed=1.0`；采样率 `sr=24000`；`SherpaBackend … speakers=103 threads=5`（6 核自适应，IM-522 生效）
- 观察：**总时长 ≈ 音频实时播放时长**（11.43 s），即 P6 结论「`total_ms ≈ 音频时长 + ttfb`、瓶颈在首包」在本地路径**再次成立**
- 冷/热差 ≈ 0.12 s（模型加载只占首包的小部分）

---

## 五、快速起停压力（模拟 TC-02 快速停止/切句）

连续 5 次「试听」点击（间隔 1 s，实测命中 3 次有效请求）：

```
PREVIEW|start voice=zf_3 → PREVIEW|stopped_by_user → PREVIEW|start（新请求）
→ PREVIEW|playing → PREVIEW|done   （×2 轮，随后第 3 轮完整播放至 progress=100/done）
```

| 判据 | 结果 |
|---|---|
| 进程存活 | ✅ 两进程均在（`ps -A` 计数 2） |
| `FATAL EXCEPTION` | ✅ **0** |
| ANR | ✅ **0**（`logcat` 与 `dropbox/data_app_anr*` 均无本项目记录） |
| 强停/假成功 | ✅ 未见「已有音频强停」或「非空文本零音频」现象；停止语义为 `stopped_by_user` 且**未被系统强停** |
| tombstone | ⚠️ 期间出现 1 条 `tombstone_18`，经查**属 `uiautomator` 自身 SIGSEGV**（我调用的 `uiautomator dump` 触发，与 App 无关）；`/data/tombstones` 中**无**本项目记录 |

> 工具坑（已登记）：在本机 MuMu 环境执行 `uiautomator dump` 会令 `uiautomator` 进程段错误（Flutter 单视图无语义树，dump 亦无收益）⇒ 后续一律改用 `exec-out screencap` + 视觉定位。

---

## 五之二、持续压测（近似 NFR §5.2 / TC-07）

**脚本**：`[WORKDIR]/p7-evidence/scripts/p7a-stress-2rps.sh` ｜ **时长** 300 s ｜ **实测点击** 560 次（**1.87 次/秒**，目标 2 次/秒）
**路径**：音色库试听（**强制本地合成**，不消耗在线额度）

| 判据 | 结果 |
|---|---|
| `PREVIEW|start` 次数 | **280** |
| `PREVIEW|stopped_by_user` 次数 | **280**（每次请求都被下一请求取代 ⇒ **停止路径被充分压测**） |
| `FATAL EXCEPTION` | **0** |
| `ANR` | **0** |
| 异常关键字（`SIGABRT`/`JNI DETECTED`/`Stop FGS`/`IllegalState`） | **0** |
| 进程存活 | ✅ 两进程均在，无重启抖动 |
| 总 PSS 曲线（两进程合计） | t+60s **280 MB** → t+120s **222 MB** → t+180s **221 MB** → t+240s **226 MB** → 结束 **230 MB** |

⇒ **无单调增长趋势、无泄漏迹象**（NFR §5.2 要求"PSS 较基线增长 ≤10%"——本项**满足**，且基线取 t+60s 时反而**下降**）。

> **与 NFR 原定义的差异（如实登记）**：NFR 要求"2 次/秒**热更配置**并持续合成 10 分钟"；本脚本**未做配置热更**（需可编程驱动），时长 5 分钟而非 10 分钟。故本项**只覆盖"持续合成 + 高频停止"面**，配置热更面待补。

## 五之三、引擎冷启就绪与进程契约回归（对应 TC-15 / BUG-P7-008 回归）

**场景**：`force-stop` 本 App（连引擎进程一并杀死）→ **不打开本 App UI** → 由 **Legado** 发起朗读（真实第三方阅读器链路）。

| 时点 | 事件 | 耗时特征 |
|---|---|---|
| 00:54:39.582 | `TextToSpeech: Sucessfully bound to com.kermond.ebook2tts`（客户端） | — |
| 00:54:39.583 | `TextToSpeechManagerPerUserService: Trying to start connection to TTS engine`（**system_server 代绑**） | — |
| 00:54:39.591 | `Start proc 24528:…:tts_service`（引擎进程冷启） | — |
| 00:54:39.967 | `EngineApp.onCreate process=…:tts_service engine=true` + `MMKV initialize root dir…` | **进程起来 → MMKV 就绪 0.38 s** |
| 00:54:41.781 | `reload ok model=kokoro-int8 sr=24000 speakers=103` | **引擎就绪（进程启动起算）≈ 2.19 s** |
| 00:54:43.294 | `ONLINE|ttfb_ms=715` | 首个成功请求首包 0.72 s |

| 判据 | 结果 |
|---|---|
| TC-15 进程契约（UI 未启动，纯阅读器驱动可用） | ✅ 通过：主进程**未**启动，仅 `:tts_service` 在跑并完成合成 |
| BUG-P7-008 回归（MMKV 初始化崩溃） | ✅ **未复现**（`MMKV initialize` 正常、无 `IllegalStateException`；`dropbox` 崩溃文件数仍为历史 2 条） |
| NFR「引擎就绪 ≤1.5 s」 | ⚠️ **本机实测 ≈2.19 s（x86_64 模拟器）**；同时暴露 **BUG-P7-011**（首请求在预算 1500 ms 处超时被拒，模型仅晚 83 ms 就绪） |
| 首句成功率 | ❌ 本次**首句失败**（客户端 `onError errorCode:-4`），第二句起正常 ⇒ 见台账 BUG-P7-011（P1） |

---

## 五之四、`:app`（Flutter 宿主）静态扫描（补 agy 指出的覆盖缺口）

**命令**：`app/android` 的 Flutter 驱动 Gradle 构建执行 `:app:lintDebug` ｜ **结果**：`BUILD SUCCESSFUL in 2m 8s`，**0 errors / 8 warnings**（原文归档 `证据/android-lint-app-debug.txt`）

| 告警 | 位置 | 裁定 |
|---|---|---|
| `Aligned16KB` ×3（arm64-v8a `libmmkv.so`） | 依赖 `com.tencent:mmkv:1.3.9` | ⇒ **在 App 变体上端到端复现 BUG-P7-004**（此前仅 `:engine` 变体命中） |
| `ObsoleteSdkInt`：`drawable-v21` 目录多余（minSdk 27） | `app/android/app/src/main/res/drawable-v21` | 新登记 **BUG-P7-012**（P3，死资源） |
| `OldTargetApi`：未针对最新 Android（targetSdk 34） | `app/android/app/build.gradle.kts:21` | **设计决定**（甲方既定 targetSdk 34；compileSdk 36），**非缺陷**，登记为"知情项" |
| `AndroidGradlePluginVersion`：有更新 Gradle 9.7.1 | `gradle-wrapper.properties` | 信息项（版本策略受 P5-B 约束，不动） |
| `NewerVersionAvailable`：coroutines 1.8.1 / okhttp 4.12.0 | `app/android/app/build.gradle.kts:73/75` | 与 BUG-P7-007 同源（已 OSV 0 命中） |

---

## 六、历史崩溃记录（应用侧，dropbox 取证）

以 root 读取设备 `dropbox` 与 `/data/tombstones`，本项目**全部**崩溃记录为 **2 条**（同一根因、相邻 1 秒）：

| 项 | 值 |
|---|---|
| 时间 | 2026-09-15 01:22:20.589 / 01:22:21.910（系统构建 `Redmi/manet/manet:15`） |
| 进程 | `com.kermond.ebook2tts:tts_service` |
| 版本 | `com.kermond.ebook2tts v1 (0.2.0-alpha.1)` |
| 异常 | `java.lang.RuntimeException: Unable to create service …LocalTextToSpeechService: java.lang.IllegalStateException: You should Call MMKV.initialize() first.` |
| 根因类型 | **多进程初始化顺序**（引擎进程内 MMKV 未初始化即被使用）——正是《架构书》§5.1.1 / ISSUE-03 防护点 |
| 当前状态 | 现行源码 `EngineApp.onCreate()` **首行即** `MMKV.initialize(this)`，且今日实测 `:tts_service` 正常启动、配置读写成功（未复现） |
| 诚实说明 | `git log -S "MMKV.initialize" -- EngineApp.kt` 显示该调用自 `a524d36` 起未再变更；崩溃时设备上实际安装的构建**无法从仓库判定**（可能为更早版本）⇒ 记为**回归验证项**而非已修结论 |
| ANR | 无任何本项目 ANR 记录 |

---

## 七、依赖与合规核查（可复算）

### 7.1 依赖漏洞（OSV，Maven 生态，2026-09-16 实查）

| 依赖 | 版本 | OSV 命中 |
|---|---|---|
| com.squareup.okhttp3:okhttp | 4.12.0 | 0 |
| com.squareup.okhttp3:mockwebserver | 4.12.0 | 0 |
| org.apache.commons:commons-compress | 1.26.0 | 0 |
| androidx.media3:media3-common | 1.9.4 | 0 |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.8.1 | 0 |
| androidx.core:core-ktx | 1.13.1 | 0 |
| androidx.appcompat:appcompat | 1.7.0 | 0 |
| com.tencent:mmkv | 1.3.9 | 0 |
| org.json:json | 20240303 | 0 |

> 口径：OSV 查询按「包名+精确版本」；**未**跑 OWASP dependency-check（NVD 库下载与速率限制待评估），故本表结论为「OSV 无已知漏洞」，非「绝对无漏洞」。
> Flutter 侧：`flutter pub outdated` 显示**直接依赖全部最新**（仅 4 个传递依赖有小版本差），无已知风险项。

### 7.2 16 KB 页对齐（Google Play 硬性要求，2026-09-16 实查现行有效）

官方口径（android-developers.googleblog.com，2025-05；Play 支持公告）：**自 2025-11-01 起**，提交至 Google Play 且 targeting Android 15+ 的新应用与更新**必须**支持 16 KB 页大小；可申请延期至 **2026-05-31**（已过期）。

实测——**APK 内全部 native 库的 LOAD 段对齐**（`llvm-readelf -l`，NDK 28.2 工具链）：

| 库 | arm64-v8a | armeabi-v7a | x86_64 | 结论 |
|---|---|---|---|---|
| `libflutter.so` | 0x10000 | 0x10000 | 0x10000 | ✅ |
| `libonnxruntime.so`（sherpa 1.13.8） | 0x4000 | 0x4000 | 0x4000 | ✅ |
| `libsherpa-onnx-c-api.so` | 0x4000 | 0x4000 | 0x4000 | ✅ |
| `libsherpa-onnx-cxx-api.so` | 0x4000 | 0x4000 | 0x4000 | ✅ |
| `libsherpa-onnx-jni.so` | 0x4000 | 0x4000 | 0x4000 | ✅ |
| **`libmmkv.so`（1.3.9）** | **0x1000** | **0x1000** | **0x1000** | ❌ **不合规（唯一）** |

**升版验证（实测 AAR，非推断）**：

| MMKV 版本 | 提供 ABI | arm64 对齐 | armeabi-v7a 对齐 | x86_64 对齐 |
|---|---|---|---|---|
| 1.3.9（现用） | arm64-v8a / armeabi-v7a / x86 / x86_64 | **0x1000** | 0x1000 | 0x1000 |
| **1.3.17（1.x 最新）** | arm64-v8a / **armeabi-v7a** / x86 / x86_64 | **0x4000 ✅** | 0x1000 | **0x4000 ✅** |
| 2.4.2（2.x 最新） | **仅 arm64-v8a / x86_64** | 0x4000 ✅ | **无 32 位库** | 0x4000 ✅ |

⇒ **结论：升级到 1.3.17 即可同时满足 16 KB 对齐（arm64/x86_64）与保留 `armeabi-v7a`**（NFR §5.3 正式包双 ABI 不受影响）；**不需要**跨到 2.x（2.x 已无 32 位库，会与 DQ-2/NFR 冲突）。arm64 是 16 KB 设备的主战场，armeabi-v7a 仍为 4 KB（Google 要求针对 64 位 ABI，32 位库不受该硬性检查约束）。

### 7.3 包体基线（debug，仅记录）

`app-debug.apk` = **1.5 GB**（含三 ABI + Flutter debug + GPU 验证层 `libVkLayer_khronos_validation.so`）。
> 与 NFR「单 ABI ≤35 MB / 双 ABI ≤60 MB」**不可直接比对**（NFR 指正式发布包）；release 包体测量属 P7-D（需甲方授权 release 构建与签名，DQ-P7-4）。

---

## 八、本批次未执行项（如实登记）

| 项 | 原因 | 后续 |
|---|---|---|
| 引擎就绪 ≤1.5 s 测点 | 需合成请求驱动，本轮已打通本地强制路径 | P7-A 补测（同一脚本扩一项） |
| 2 次/秒 热更配置 + 10 min 持续合成（NFR §5.2 / TC-07） | 需可编程驱动（instrumented 或桥接脚本），尚未落地 | P7-A 工具落地后执行 |
| 锁屏 2 h 连续朗读（TC-10） | 耗时长，且需长文素材与真实阅读器驱动 | P7-A/P7-B 排期 |
| 200 次服务强杀重建（AR §10 会话销毁专项） | 同上 | 工具落地后执行 |
| 功耗（≤8 %/h，NFR §5.1 标准条件） | 模拟器功耗无代表性 | 真机口径（DQ-P7-2） |
| Perfetto / simpleperf / StrictMode / LeakCanary（多进程） | 工具接入尚未实测（计划 §2.1 已列兼容性风险） | P7-A 继段（首个动作） |
| release 包（R8/剥离/双 ABI/签名） | 需甲方 keystore 授权（DQ-P7-4） | 待裁决 |
| ARM 真机全项 | 无真机 | 待裁决（DQ-P7-2） |

---

## 九、结论（P7-A 阶段小结）

1. **工具链可用**：Android Lint（AGP 内置，`:engine` 3E/18W；`:app` 0E/8W）✅、detekt-cli 1.23.8 ✅（JDK 17 跑通）；**detekt 2.0.0-alpha.6 复测结论：JDK 17 可运行**（无需官方标注的 JDK 25），但报告参数名在 2.x 已变更，采用 1.x 参数会静默无报告 ⇒ 本轮采 1.23.8。
2. **基线已立**：冷启中位数 1959 ms、引擎进程 PSS 153 MB、本地试听 TTFT 1.52–1.64 s（24 字）、快速起停无崩溃无 ANR。
3. **唯一合规硬伤定位到依赖一处**：`libmmkv.so` 非 16 KB 对齐，升版到 **1.3.17** 即可解（已实测证据）。
4. **历史崩溃 1 类 2 条**（`:tts_service` MMKV 未初始化）已定位，现行代码具备修复路径，列为回归验证项。
5. **依赖漏洞（OSV）为 0**；Flutter 直接依赖均为最新。
6. 未执行项已逐条登记（§八），不冒充实测。
