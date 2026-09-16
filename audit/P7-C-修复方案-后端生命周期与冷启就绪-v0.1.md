# P7-C 修复方案（v0.2 · 已并入 agy 独立复核）— 后端生命周期、冷启就绪、重载收敛与错误码

> 2026-09-16 ｜ 实施线：Hermes ｜ **复核：agy（有条件通过，见 §五）** ｜ 分支 `p7/audit`（私密仓）
> v0.1 → v0.2 变更：采纳 agy 的 4 条 MUST（含**新根因** `reloadInternal()` 无条件 release+load）、2 条 SHOULD/MAY；**修正**草案中的释放策略与等待预算数值；补真机决定性证据（见 §六）
> 覆盖缺陷：**BUG-P7-001**（错误码错位）、**BUG-P7-011**（冷启首请求被拒）、**BUG-P7-013**（中止不唤醒等待）、**BUG-P7-014**（试听后整模型重载）、**新发现 BUG-P7-015**（预览通道自建后端，每次试听都整模型加载+释放）
> 约束：九条红线（尤其 1/2/4/5/7）、JNI 契约不得变、改行为先改文档

---

## 一、根因链（本轮新查明，代码级）

`PreviewPlayer.preview()`（`engine/.../PreviewPlayer.kt:59-128`）：

```kotlin
val backend = SherpaBackend(dir, spec, ConfigStore.threads())   // :85  每次试听都新建
backend.load()                                                  // :86  每次试听都整模型加载（真机 12.09 s）
...
backend.release()                                               // :119 每次试听结束即释放
```

⇒ **每一次试听 = 一次完整模型加载 + 一次释放**；而 `SynthesisCoordinator` 另持有自己的后端实例（系统通道用）。
两条通道各自加载同一模型，且预览通道在每次播放后释放 ⇒ 真机上表现为：

| 现象 | 解释 |
|---|---|
| 试听冷加载 **12.09 / 12.24 s**（稳定复现） | `backend.load()` 每次重来 |
| 试听结束后又出现 `SherpaBackend: loaded … / SynthCoord: reload ok` | 系统通道（或下一次预览）再次加载 |
| 连续试听总时长 **37 s / 82 s**（同一段 24 字，模拟器仅 11.4 s） | 加载 + 释放 + 再加载串行叠加 |
| 系统通道首个请求被拒（`SYNTH_REJECT reason=backend_not_ready`） | `awaitBackend()` 预算 **1500 ms** 远小于真机冷加载 12 s |

**新增缺陷 BUG-P7-015（P1）**：预览通道自建后端且每次释放 ⇒ 冷启成本翻倍、内存反复起落、与"预热可让首请求命中已就绪后端"的设计目标直接冲突。**这是真机性能问题的根因**。

---

## 二、修复设计（R1~R7，**建议同批实施**）

| 编号 | 改动 | 对应缺陷 | 风险与红线 |
|---|---|---|---|
| **R1** | **后端单实例化**：`SherpaBackend` 由 `SynthesisCoordinator` 独占持有（`backendProvider`），`PreviewPlayer` 改为**借用**同一实例（不再 new/load/release） | 015 | 并发语义变化 ⇒ 必须复核 NativeGate 串行化仍成立；**不得**使试听中断在途朗读（红线 1/2） |
| **R2** | **服务创建时异步预热**：`LocalTextToSpeechService.onCreate()` 触发一次后台 `load()`（不阻塞 `onCreate`；失败不抛） | 011 | 冷启多占 CPU/内存（预期收益：首请求命中已就绪后端）；预热失败必须**静默降级**为按需加载 |
| **R3** | **试听不再释放后端**：仅释放音频轨（`releaseTrack()`）；后端释放只发生在**模型切换 / 服务销毁 / 空闲超时**三个明确时机 | 015 | 空闲释放需设阈值（建议 ≥5 min 无请求），避免常驻内存与 NFR「引擎 PSS」冲突 |
| **R4** | **中止必须唤醒等待者**：`requestStop()` → `signalReady()`（或为停止引入独立条件变量） | 013 | **R5 的前置条件**：不先做这一步，禁止提高预算 |
| **R5** | **等待预算与排队**：预算 1500 → **3000 ms**（预热后应足够；保留兜底）；**首个请求在等待窗口内不回落 error**，改为等待至就绪或超时（超时才报错） | 011 | 红线 3（绝不假成功）不受影响；必须保留"客户端中止 ⇒ 立即静默退出" |
| **R6** | **重载收敛**：`ConfigStore.notifyReload()` 写入前**比对旧值**（无变化不发广播）；`scheduleReload` 增加"距上次重载 < N 秒则合并"；桥接层 `bridge_active_model/bridge_narrator/bridge_config` 只在值真变时触发 | 014 | 保守优先：宁可少重载，不可漏生效；变更生效 ≤3 s 的既定口径须回归验证（TC-15） |
| **R7** | **错误码统一平台常量**：删除 `TextToSpeechErrors` 自造常量；`PreviewPlayer` 的 `-12` / `-4` / `-1` 一并改为平台常量（`ERROR_NOT_INSTALLED_YET` / `ERROR_SYNTHESIS`） | 001 | 客户端可见行为变更 ⇒ 文档回写 + 真机复核（零模型场景应收到 -9） |

**新增打点（可复算，便于 A/B）**：
`BACKEND|load|reason=preheat|on_demand|model_switch|ms=<耗时>`、`BACKEND|release|reason=idle|shutdown|model_switch`、`RELOAD|skipped|reason=unchanged`、`WAIT|ready|ms=<等待耗时>|result=ok|timeout`

---

## 三、验收方式（真机优先）

| 项 | 判据 |
|---|---|
| 试听首音 | 冷启后**首次**试听 TTFT ≤ 预热完成时间（应在数百 ms 量级）；**第二次**试听不得再出现 `BACKEND\|load` |
| 加载次数 | 一次会话（多次试听 + 多次系统朗读）中 `BACKEND\|load` **仅 1 次**（模型不变时） |
| 首请求成功 | 冷启后由阅读器触发：**无** `SYNTH_REJECT`，客户端**无** `onError` |
| 中止响应 | 冷加载窗口内取消 ⇒ 等待线程 ≤200 ms 退出（`WAIT` 打点） |
| 重载收敛 | 不改变配置时，`RELOAD|skipped` 出现；改变旁白/模型时仍生效 ≤3 s（TC-15） |
| 回归 | `:core:test` / `:engine:testDebugUnitTest` / `flutter test` 全绿；红线 1/2/3 专项复核 |
| 性能 A/B | 真机（MIX 2S）同文本、同温度区间、各 10 次取中位：试听 TTFT、连续试听总时长、引擎 PSS、温度 |

---

## 四、待 agy 复核的问题（请其独立判断）

1. R1「共享后端」是否可能违反红线 1/2（试听与系统朗读并发路径）？有无更安全的替代（如后端缓存池 + 引用计数）？
2. R3 的空闲释放阈值如何定才既不违反 NFR（引擎 PSS ≤220 MB）又不牺牲体验？
3. R5「首请求排队而非立即报错」是否与 AOSP 回调契约或红线 3 冲突？超时值取多少合理？
4. R6「值未变则不重载」的比对范围（哪些键允许触发重载）应如何界定，避免"改了却不生效"的新缺陷？
5. 是否存在本方案未覆盖的根因（例如 `NativeGate` 与后端实例的耦合、MMKV 多进程读导致的重载）？

---

## 五、agy 独立复核处置（v0.2）

agy 结论：**有条件通过**（须按 MUST 修正后实施）。逐条处置：

| agy 意见 | 实施线处置 | 说明 |
|---|---|---|
| **MUST-1**：`reloadInternal()`（≈179 行）**无条件** `oldRelease()` + `load()`，无配置比对 ⇒ 任何 `bridge_*` 广播（哪怕值没变）都会触发 **12 s 全量重载**，**这是 BUG-P7-014 的最核心根因** | ✅ **采纳**（已代码核验：`guard.withLock { oldRelease(); … }` 确无比对） | 新增 **R6b**：进入 release 前短路——`modelId`/`threads` 未变且 `backend.isReady()` ⇒ 仅刷新 `RoleAssigner`/`voicePool` 后 `return` |
| **MUST-2**：`READY_WAIT_MS` 提升到 **15000 ms**（草案的 3000 ms 在真机 12.24 s 冷启前形同虚设） | ✅ **采纳**（R5 数值由 3000 → **15000 ms**） | 与 R2 预热配合后，正常情况下首请求几乎不需等待，15 s 仅作兜底 |
| **MUST-3**：**放弃"空闲超时释放"**（12 s 唤醒代价 > 内存收益） | ✅ **采纳**（R3 改为：**不主动空闲释放**；释放仅发生在模型切换 / 服务销毁 / 系统回收） | 边界补充：`onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)` 时可择机释放（登记为可选） |
| **MUST-4**：`PreviewPlayer` 移除自有 `SherpaBackend` 的 new/load/release，改为借用常驻实例 | ✅ **采纳**（＝本方案 R1） | 并发安全依据：所有 native 调用均经 `NativeGate.withLock`（已核验 6 处） |
| **SHOULD-1**：15 s 等待循环内必须高频响应 `isStopped()`，防客户端 ANR | ✅ **采纳**（R4 的组合要件） | 等待循环已在锁内轮询，需确保粒度（≤50 ms）并能被 `requestStop()` 唤醒 |
| **MAY-1**：试听可"快速失败（引擎忙）"而非死等 | ⏳ **登记为可选** | 需与红线 1/2 一起评估，暂不实施 |
| agy 未核实①：Flutter 端究竟哪条指令触发重载 | ⏳ **继续排查**（已定位 Dart 侧候选：`voices_page.dart:257 setNarratorVoice`、`models_page.dart:232 setActiveModel`） | 引擎侧"无条件重载"机制已确证，即使调用方是空操作也会付 12 s 代价 |
| agy 未核实②：`SherpaBackend` 是否受 `NativeGate` 保护 | ✅ **已核验**：`load/generateStreaming/generatePcm/numSpeakers/release` 全部 `NativeGate.withLock` | — |
| agy 未核实③：阅读器自身 TTS 超时上限可能 < 15 s | ⏳ **登记**：Legado 侧超时未知；**以上线前真机实测为准**（若客户端提前掐断，则以"预热 + 软重载"把常态等待压到 ≈0） | — |

### 修正后的实施方案（R1–R7 + R6b）

| 编号 | 最终口径 |
|---|---|
| R1 | 后端单实例化：`PreviewPlayer` 借用 `SynthesisCoordinator` 的常驻后端（不 new/load/release） |
| R2 | 服务创建时异步预热（不阻塞 `onCreate`，失败静默降级） |
| R3 | **不主动空闲释放**；释放仅限模型切换 / 服务销毁 / 系统级回收 |
| R4 | `requestStop()` → `signalReady()`（唤醒等待者）；等待循环轮询粒度 ≤50 ms |
| R5 | `READY_WAIT_MS = 15000`；首请求排队至就绪（超时才 `error`） |
| R6 | 配置写入前比对（无变化不发广播）；`scheduleReload` 合并窗口 |
| **R6b** | **`reloadInternal()` 短路**：硬重载仅当 `modelId`/`threads` 变更或状态为 `ERROR`/`NO_MODEL`；`narratorVoice`/`roleMode` 走**软重载**（只重建 `RoleAssigner`/`voicePool`，零 native 代价） |
| R7 | 错误码统一平台常量（含 `PreviewPlayer` 的 `-12`/`-4`/`-1`） |

---

## 六、真机决定性证据（2026-09-16，同文本同音色连续两次试听）

```
+   0.00s  PREVIEW|start        （第 1 次）
+  12.20s  SherpaBackend loaded （整模型加载）
+  12.25s  PREVIEW|playing      （首音；24 字）
+  39.77s  PREVIEW|start        （第 2 次）
+ 119.79s  PREVIEW|done         （第 2 次总耗时 80.0 s，期间无 loaded/playing 日志）
```

| 事实 | 含义 |
|---|---|
| 第 1 次首音 **12.25 s** | 冷加载代价（＝`PreviewPlayer` 每次 new+load） |
| 第 2 次 **80.0 s** 且无 `loaded`/`playing` 打点 | 初步判断为释放后再初始化/资源回收路径异常 ⇒ **根因待插桩确认**（已列入 R1/R3 的收益验证项，不预设结论） |
| 引擎进程 CPU 峰值 153–193% | 非空闲挂起，而是持续计算 |

> 结论：真机上"每次试听付一次加载 + 释放后再加载代价极高"已被量化；**R1/R3/R6b 的收益将用同一实验复测**（目标：第二次试听不再出现 `load`，总时长回到与音频时长相称的量级）。
