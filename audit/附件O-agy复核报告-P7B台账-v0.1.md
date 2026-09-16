# 附件O · agy 独立复核报告 — P7-B 缺陷台账（v0.1）及实施线逐条处置

> 复核方：**agy**（Google Antigravity CLI，`gemini-3.1-pro-high`，headless，只读）
> 复核对象：`P7-B 缺陷台账 v0.1`（+ 原始证据 `p7-evidence/static/`）
> 复核时间：2026-09-16 ｜ 复核人（线）：Hermes ｜ 基线提交 `3b257e2`（分支 `p7/audit`）
> agy 结论：**有条件通过（需修整）**
> 处置结论：**4 条 MUST 中 1 条采纳、2 条部分采纳、1 条技术前提纠正**；2 条 SHOULD 与 1 条 MAY 采纳；**agy 指出的一处实施线事实错误已确认并更正**（Play 16 KB 期限）。
> 本附件为 agy 复核**原文**（未删改）＋ 实施线逐条处置。

---

## 一、逐条处置表（实施线裁定）

| 复核项 | agy 意见 | 实施线处置 | 依据 / 说明 |
|---|---|---|---|
| **MUST-1** | 修复 BUG-P7-001（错误码），引用平台常量并回写文档 | ✅ **采纳** | 与实施线独立结论一致；已列入「待批准修复方案」（台账 §六），修复须先回写《架构书》§4.7 |
| **MUST-2** | **推翻**台账对 `BIND_TTS_SERVICE` 的"误报"判定，转为真实缺陷并**从清单移除** | ⚠️ **部分采纳（判定采纳、处方驳回）** | 见 §二 专项裁定 |
| **MUST-3** | 补录 Lint `UsableSpace` 告警（`ModelDownloader.kt:69`） | ✅ **采纳** | 属实遗漏——Lint 报告 125–144 行确有此告警；已新增 **BUG-P7-009**（P3） |
| **MUST-4** | 升级 MMKV（建议 LTS `1.3.14`，或直接 `2.4.2`） | ⚠️ **部分采纳** | 升级**采纳**；**版本按实测选定 1.3.17**：实测 `1.3.17` arm64/x86_64 达 0x4000 且**保留 armeabi-v7a**；实测 `2.4.2` **无 armeabi-v7a 库**（仅 arm64-v8a + x86_64）⇒ 与 DQ-2/NFR §5.3 双 ABI 决策冲突，**不采纳 2.x**；`1.3.14` 未实测（`1.3.16/1.3.17` 已实测合规） |
| **SHOULD-1** | 补 Flutter 侧 `POST_NOTIFICATIONS` 申请链条 | ✅ **采纳** | 即 BUG-P7-003 的处置方向 |
| **SHOULD-2** | 用 `dataExtractionRules` 排除 MMKV 目录（优于迁 Keystore） | ✅ **采纳** | 已作为 BUG-P7-002 的**首选**处置方案（成本最低、保留备份能力） |
| **MAY-1** | P7-C 批量清理死代码与低复杂度问题 | ✅ **采纳** | 归入 BUG-P7-005/006 的 P3 批量处置 |
| 复核方"需实查"① | `commons-compress/okhttp` 是否真有高危 CVE，须跑 OWASP | ✅ **已闭环（提前于复核）** | 已用 **OSV**（Maven 生态、精确版本）查询 9 个依赖 → **0 命中**；台账 BUG-P7-007 据此降为"纯版本陈旧"，并注明"OSV 无已知漏洞 ≠ 绝对无漏洞"与 OWASP 未跑 |
| 复核方"需实查"② | `InstanceOfCheckForException` 改写是否引发异常回抛/吞没 | ⏳ **留待动态阶段** | 已列入台账 §五 未完成项；改写属行为变更，须走 P7-C 评审 |
| 复核方遗漏面：Flutter/pub 侧依赖 | 台账缺跨端依赖面 | ⚠️ **部分已覆盖** | 复核基于 v0.1；v0.2 已含 `flutter pub outdated`（**直接依赖全部最新**）+ Flutter 侧 OSV 核对 |
| 复核方遗漏面：Release/R8 与 JNI | 未验证 Release 包时无法保证 R8 未破坏 JNI | ✅ **采纳登记** | 已属计划 §四「构建与交付」与风险 R5/DQ-P7-4；台账 §五 列为未完成项 |

### ✅ agy 复核的实质贡献（实施线原稿有误，已更正）

| 项 | 实施线原稿 | 官方实查结论 | 处置 |
|---|---|---|---|
| **Play 16 KB 强制期限** | v0.1 写「**2025-11-01 起**已强制，延期窗口 2026-05-31 **已过期**」⇒ 判为"现行阻断项" | 官方现行页面（`developer.android.com/guide/practices/page-sizes`，末次更新 2026-08-05）明确：**"Starting February 1, 2027**, if your app updates don't support 16 KB memory page sizes, you won't be able to release these updates."（早期 2025-05 公告为 2025-11-01，后续提供延期窗口） | **采纳 agy 口径**：台账 BUG-P7-004 期限更正为 **2027-02-01**；严重性仍为 P1（可用性问题前置：16 KB 设备上老式 4 KB 库存在兼容风险，Android 17 起可直接 abort；且 Play Console 会告警），但**不影响当前发版节奏**的表述已修正 |

---

## 二、MUST-2 专项裁定：`BIND_TTS_SERVICE` 是「无效权限名」，但**不应简单删除**

### 2.1 实施线复核 agy 的判定：**agy 正确，实施线原"误报"结论错误，予以撤销**

| 证据来源（独立三路，2026-09-16 实查） | 结果 |
|---|---|
| AOSP `frameworks/base/core/res/AndroidManifest.xml`（main 分支，含 500+ 平台权限） | **无** `BIND_TTS_SERVICE`；且该文件内**不存在任何 TTS 相关权限** |
| AOSP 同名文件历史分支 `android-14.0.0_r1 / 13.0.0_r1 / 11.0.0_r1 / 9.0.0_r1` | 命中数均为 **0**（并非"旧版本曾有、后来移除"） |
| 官方权限参考页 `Manifest.permission`（2.8 MB 全文） | 无该名（对照 `BIND_INPUT_METHOD` 命中 4 次，确认检索有效） |
| 设备真值（MuMu Android 15）：`pm list permissions -f` 共 955 条 android.permission | **无**该权限；`framework-res.apk` 字符串检索 **0** 命中 |

⇒ 结论：`android.permission.BIND_TTS_SERVICE` **不是平台权限**，清单中的声明属**无效声明**；Lint `SystemPermissionTypo` **不是误报**。实施线原判定错误，**予以撤销并登记**（台账 §四 已改写）。

### 2.2 但「从清单移除」这一处方**不采纳**——有实测与 AOSP 源码反证

1. **实测（2026-09-16 00:47，真机）**：安装的 **Legado**（第三方应用，uid 10056）点击「朗读」后，本引擎被成功唤起并完成在线合成（`ONLINE|chosen=online|model=mimo-v2.5-tts|voice=茉莉`、`PERF|req=2|chunks=96|ttfb_ms=717|total_ms=15176|drop=0`）；服务连接方为 **system（uid 1000）**，无任何 `SecurityException`。
2. **AOSP 源码解释（`TextToSpeech.java`）**：
   > "Currently **all the clients are routed through the System connection**. Direct connection is left for debugging, testing and benchmarking purposes."（`DirectConnection` 才是 `mContext.bindService`）⇒ 正常客户端**不经应用自身绑定**，而由 system_server（uid 1000）绑定；system 绕过组件权限校验，故无效权限声明**不影响正常使用**。
3. **反向风险**：正因为该名字"无效"，**第三方应用直接 `bindService` 的路径被拒**（无效权限 ⇒ 校验必然 DENIED）。若照 Lint 建议**径直删除**该属性，服务将变为对**任意应用开放直接绑定**——安全隐患**变大**。
4. ⇒ 正确做法须**先定论证**，不能一句话删掉：
   - 查官方 TTS 引擎示例/文档确认规范声明方式；
   - 若要保留"仅系统连接可绑"的现状语义，应改为**自建 signature 级权限**（自带 `<permission>` 声明）或按官方约定不声明并接受直接绑定；
   - 无论选哪条，必须**真机验证两点**：① 系统连接（阅读器朗读/系统设置样例）仍可用；② 直接绑定策略符合预期。

### 2.3 最终定级

**BUG-P7-010（新增）**：服务声明无效平台权限 `android.permission.BIND_TTS_SERVICE` ｜ **P3（清单正确性 / 静态门噪声；含潜在反向风险）**
—— 无用户可见故障、无崩溃；但声明语义错误、Lint 报错，且**修改方向具有安全含义，不可盲改**。详见台账。

---

## 三、实施线据本次复核新增/更正的条目（v0.3）

1. **新增 BUG-P7-009**：Lint `UsableSpace`（`ModelDownloader.kt:69` 用 `usableSpace` 而非 `getAllocatableBytes`）｜P3（容量预检口径，涉 RQ-202/空间预检 2.5×）。
2. **新增 BUG-P7-010**：无效权限声明（见 §二）。
3. **更正 BUG-P7-004**：Play 期限改为官方现行口径 **2027-02-01**；补充"16 KB 设备兼容性风险仍即时存在"与"Android 17 起可 fatal abort"的官方机制说明。
4. **撤销台账 §四 的"误报"登记**，改写为上述真实缺陷。
5. **BUG-P7-007** 补注 OSV 0 命中结论（复核方"需实查"①已闭环）。
6. **新增实测证据**：跨应用（Legado）链路可用性、在线路径基线（`ttfb_ms=509~819`、`total_ms=9818~15176`、`drop=0`）、`done() was called before start() call` 属 AOSP 正常提示（AR-§1.4 已约定的 STOPPED 态早退路径，登记为观察项而非缺陷）。

---

## 四、复核原文

依据实施线提供的凭据与文件，我已完成独立的静态证据复核。复核过程严格遵循“只读且基于官方权威”的纪律，以下是复核报告：

### 1. 复核结论
**有条件通过（需修整）**
条件：必须修正对 `BIND_TTS_SERVICE` 的所谓“误报”误判，台账中必须补充遗漏的 `UsableSpace` Lint 缺陷记录，并补齐 Flutter 侧依赖与 Release 构建的清查面。

### 2. 逐条判定表

| 编号 | 成立性 | 你的分级 | 理由（含依据来源） |
|---|---|---|---|
| BUG-P7-001 | **成立** | **P1** | 传入非法值 `-12` 会导致 Android 框架原样透传，而客户端（如 Legado）无法识别其为“需下载语音数据”，表现为未定义行为或静默失败，**直接阻断 TC-13 验收**。传入 `-4(ERROR_SERVICE)` 同样属于语义错乱，会误导客户端执行服务解绑等引擎重连策略。依据：[AOSP TextToSpeech.java 源码](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/speech/tts/TextToSpeech.java)中，明确定义 `-3`为合成错误，`-9`为未安装，**不存在 `-12` 枚举**。 |
| BUG-P7-002 | **成立** | **P2** | MMKV 落盘文件支持系统备份，导致明文 API Key 会随 Android 备份出端，直接违反 NFR-§5.4 隐私承诺与架构规定。属于高危合规隐患。 |
| BUG-P7-003 | **成立** | **P2** | 在 Android 13+ 系统上，如果用户拒绝 `POST_NOTIFICATIONS`，前台服务 (FGS) 的常驻通知**不会显示在顶部通知抽屉（状态栏）中**（仅在下拉菜单中的“活动应用/任务管理器”可见）。这导致“后台下载无感知”，违背 RQ-202。依据：[Android 13 通知权限官方文档](https://developer.android.com/develop/ui/views/notifications/notification-permission)。 |
| BUG-P7-004 | **成立** | **P1** | Google Play 官方政策要求：针对 API 35+ (Android 15) 的应用，自 **2027 年 2 月 1 日** 起强制支持 16KB 页对齐，否则无法发版。此外 4KB 对齐库在 16KB 机型上运行必崩。MMKV 在 **v2.0.0** 及其 LTS 版本 **v1.3.14** 才正式引入 16KB 对齐。依据：[Google Play 16KB页支持](https://developer.android.com/guide/practices/page-sizes)、[MMKV Releases](https://github.com/Tencent/MMKV/releases)。 |
| BUG-P7-005 | **成立** | **P3** | 冗余的 SDK_INT 检查属于死代码逻辑，维护性缺陷，暂无引发功能性 Bug 的风险。 |
| BUG-P7-006 | **需实查** | **P3** | detekt 中的 `MagicNumber`、`ReturnCount` 等属代码规范问题 (P3)；但包含的异常控制流（`InstanceOfCheckForException`）及高圈复杂度需在 P7-B 后续动态注入阶段去观察是否存在隐性的崩溃掩盖行为。 |
| BUG-P7-007 | **需实查** | **P3** | 仅版本落后不足以定级。依赖如 `commons-compress` / `okhttp` 曾经出过较多安全通报，在未经 `OWASP dependency-check` 筛查证实存在历史 CVE 前，维持 P3 例行升级。 |

### 3. 必须改 MUST
- **MUST-1**: 修复 BUG-P7-001。代码必须直接引用 `android.speech.tts.TextToSpeech.ERROR_SYNTHESIS` 与 `ERROR_NOT_INSTALLED_YET`，并将修复方案回写至《架构书》§4.7 错误语义总表及《实现报告》IM 编号续排（此为行为变更）。
- **MUST-2**: 推翻台账 §四 对 `BIND_TTS_SERVICE` 的“误报”判定。将其转为**真实缺陷**（该权限并不存在），并移除清单中的无效权限声明。
- **MUST-3**: 将遗漏的 Lint 告警（`UsableSpace`）补入 v0.2 台账，并按警告建议修改空间核算方式（`getAllocatableBytes`）。
- **MUST-4**: 升级 MMKV 依赖（推荐 LTS 分支 `1.3.14`，若无痛可直接上 `2.4.2`），彻底根除 16KB 内存页对齐合规与硬崩溃问题。

### 4. 建议改 SHOULD
- **SHOULD-1**: 解决 BUG-P7-003 漏掉通知权限的问题，在 Flutter 侧补齐 `POST_NOTIFICATIONS` 的申请链条。
- **SHOULD-2**: 解决 BUG-P7-002。建议保留备份能力，但采用 `dataExtractionRules` (XML 规则) 显式把 MMKV 的落地目录排除在备份外，这比迁移 Android Keystore 成本更低。修改后需回写架构决议 ADR。

### 5. 可选 MAY
- **MAY-1**: 在 P7-C 批次的批量优化中统一清理 BUG-P7-005 / 006 指出的死代码与低级圈复杂度问题。

### 6. 官方依据清单

| 结论 | 来源链接 |
|---|---|
| Android TTS 错误码体系（不含 -12） | [TextToSpeech.java (AOSP Master)](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/speech/tts/TextToSpeech.java) |
| MMKV 16K 页对齐支持版本（v2.0.0 / v1.3.14） | [MMKV Github Release v2.0.0](https://github.com/Tencent/MMKV/releases/tag/v2.0.0) |
| Google Play 16KB 页支持强制时间线（2027.02.01） | [Android Developer: 16KB Page Sizes](https://developer.android.com/guide/practices/page-sizes) |
| Android 13+ 拒绝通知权限后前台服务通知的去向 | [Notification Permission Documentation](https://developer.android.com/develop/ui/views/notifications/notification-permission) |
| `BIND_TTS_SERVICE` 为无效平台权限 | [AOSP AndroidManifest.xml](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/res/AndroidManifest.xml)（该文件内无此定义，Lint Typo 判断完全正确） |

### 7. 遗漏清查面清单

| 面 | 子项 | 方法 | 判据来源 |
|---|---|---|---|
| **静态扫描遗漏** | Lint 告警: `UsableSpace` | 查阅 `android-lint-engine-debug.txt` | 日志里明确指出了 `ModelDownloader.kt:69` 处使用了 `usableSpace` 而未用 Android 26 要求的 `getAllocatableBytes`，台账漏抄。 |
| **安全审查遗漏** | Flutter / Dart 侧 pub 包版本与漏洞核对 | 执行 `flutter pub outdated` / `dart pub audit` | 本台账（BUG-P7-007）只进行了原生/JVM侧的 Gradle 依赖检查，跨端业务侧的依赖面全盘缺失。 |
| **运行时结构面** | Release 构建下的混淆映射与反射失效 | 观察 Release Apk 混淆后的 JNI 及动态逻辑 | §三 称 `testDebugUnitTest` 与分析全绿，但在未构建并验证 Release 包时，无法保证代码缩减器（R8）未破坏 JNI 符号。 |

### 8. 我无法确认/需实查的点
1. BUG-P7-007 中点名的老旧依赖（如 `commons-compress` 1.26.0 升 1.28.0），是否真的带有对应执行路径的高危 CVE 漏洞。这无法靠眼睛看，需跑一轮工具 `OWASP dependency-check`。
2. BUG-P7-006 中列举的 `InstanceOfCheckForException` 如果改写，是否会连带触发异常回抛或吞没逻辑？需实施线在代码级验证影响。

### 9. 九条防呆红线逐条核验表

| 红线要求 | 核验结论 | 说明 |
|---|---|---|
| 1. 绝不为内部原因中止在途朗读请求 | ✅ 未触碰 | 本批次缺陷主要集中在外围（权限、常量定义、依赖审计），无中止流式请求告警。 |
| 2. 绝不在已有音频时调用 error() 强停 | ✅ 未触碰 | 未暴露不合规的强停处理（静态面）。 |
| 3. 绝不"假成功" (非空文本零音频必须error可见) | ✅ 未触碰 | 针对 `TooGenericExceptionCaught` 的21处抽检表明均为有效兜底，未因吞没异常产生假成功。 |
| 4. 主进程零推理；`:tts_service` 进程零 Flutter | ✅ 未触碰 | 组件面、权限面静态扫描均未检测到越界引用。 |
| 5. native释放只允许在守卫锁内、无任何持有者时 | ✅ 未触碰 | 缺陷日志不涉及 native 竞态问题。 |
| 6. 微淡化仅限请求首块头部 / 末块尾部，严禁下沉 | ✅ 未触碰 | 缺陷台账及日志均无此音频分片处理告警。 |
| 7. 合成线程严禁 IO、去抖或锁长等待 | ✅ 未触碰 | 代码复杂度告警不在此类线程执行域。 |
| 8. 存量模型回填 `.completed` 哨兵前必须通过清单校验 | ✅ 未触碰 | 尚未复盘模型下载阶段逻辑，留待 P7-B 动态面核验。 |
| 9. SAF 离线导入必须强制经 SafeExtractor | ✅ 未触碰 | 暂未在相关日志中发现解压层代码变更告警。 |
