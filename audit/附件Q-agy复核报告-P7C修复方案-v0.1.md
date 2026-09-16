# 附件Q · agy 独立复核 — P7-C 修复方案 v0.1（后端生命周期/冷启就绪/重载收敛/错误码）

> 复核方：agy（gemini-3.1-pro-high，headless，只读）｜ 2026-09-16 ｜ 结论：**有条件通过**（4 MUST / 1 SHOULD / 1 MAY）
> 实施线处置见《P7-C 修复方案》**v0.2 §五**；本附件为复核**原文**（未删改）。

**实施线核验结论**：MUST-1 的「`reloadInternal()` 无条件 release+load」经代码核验**成立**（已并入 R6b）；MUST-2/MUST-3/MUST-4 全部采纳；agy 未核实②（SherpaBackend 是否受 NativeGate 保护）由实施线核验为**是**（6 处全部 `NativeGate.withLock`）。

---

## 复核原文

### 1. 结论
**有条件通过**（须按照下述 MUST 列表修正方案后实施）。

### 2. 逐题回答

1. **R1 后端共享**
   - **不违反红线 1/2**。因为 `NativeGate` 的互斥锁设计（同一把锁）只会让并发的“试听”与“系统朗读”产生**排队阻塞**，并不会导致抛出错误或主动强停已在途的朗读，因此不构成红线所指的“为内部原因中止/强停”。
   - **取舍**：**必须共享唯一实例，且用后不释放**。若“每通道独立实例但不释放”，双通道分别加载同一模型，内存会立刻增加 150MB~200MB（总 PSS 突破 500MB），严重违反 NFR `≤220 MB`；若维持原状“用后释放”，每次试听都会重新遭受 12s 的真机冷启代价。共享是唯一可行解。

2. **R3 空闲释放阈值**
   - **建议直接废弃“空闲释放”（不设阈值）**。
   - **依据与理由**：《真机报告》明确指出 SDM845 机型冷加载需要 **12.09 s**。若设 5 分钟空闲释放，用户暂停阅读 5 分钟后恢复，将面临长达 12s 的“静默假死”；这极有可能超出 AOSP 或阅读器客户端的 TTS 超时限制，导致彻底断连。在 12s 的唤醒代价面前，应依赖系统级回收（如进程杀死或 `onTrimMemory`）和 `onDestroy`，而不是主动牺牲核心可用性去迎合 220MB 的 PSS。

3. **R5 首请求排队**
   - **不冲突**。AOSP 对 `start()` 并没有极短的硬性耗时契约。等待超时后，只要回落调用 `callback.error()` 然后 `done()`，就符合红线 3（绝不假成功）。
   - **超时值建议：15000 ms（15 秒）**。原草案提议的 3000 ms 在真机实测 12.24s 的冷启面前依然形同虚设（100% 被拒）。排队预算必须能包络住底端真机的最差加载时间。

4. **R6 重载收敛**
   - **新风险**：如果“仅当值改变才重载”，当引擎因为初始模型未下载而处于 `NO_MODEL` 或 `ERROR` 态时，此时即便传入相同的配置键（但文件已就绪），引擎也会因为“配置未变”而忽略重载，导致永久卡死在错误态。
   - **建议的分类白名单**：
     - **硬重载**（执行 release+load，耗时 12s）：仅当 `modelId`、`threads` 变更，或当前引擎状态为 `ERROR`/`NO_MODEL` 时允许。
     - **软重载**（零耗时）：`narratorVoice`、`roleMode` 变更只应重新构建内存中的 `RoleAssigner`，**绝对禁止**触发后端 release/load。

5. **未覆盖根因**
   - **漏掉了代码中无条件释放的致命缺陷**。在 `SynthesisCoordinator.kt` 第 182 行的 `reloadInternal()` 中，没有任何配置比对逻辑，第一步就是无条件调用 `oldRelease()` 然后重新 `load()`。如果 Flutter 侧（`EngineBridgePlugin`）在试听完成后，因同步 UI 状态发送了任何 `bridge_*` 广播，哪怕配置一模一样，也会触发 12s 的全量重载。**这才是 BUG-P7-014 (试听后频繁重载) 的最核心根因**。

### 3. 必须改 (MUST)
- **MUST-1**: 修改 `SynthesisCoordinator.reloadInternal()`（约 179 行），在进入 `oldRelease()` 前增加短路拦截：若 `modelId`、`threads` 与现存一致，且 `backend.isReady()`，则只需更新 `RoleAssigner`/`voicePool` 并直接 `return`，阻断 12s 的重建动作。
- **MUST-2**: 首请求排队预算 `READY_WAIT_MS`（`SynthesisCoordinator.kt` 58 行）必须提升至 **15000L**，以覆盖真机实测。
- **MUST-3**: 彻底放弃草案 R3 中关于“空闲超时释放”的规划。
- **MUST-4**: `PreviewPlayer.kt` 必须移除所有的 `SherpaBackend` new/load/release（85-92 行，119 行），改为通过 `SynthesisCoordinator` 安全借用同一个常驻后端实例。

### 4. 建议改 (SHOULD)
- **SHOULD-1**: 在 `SynthesisCoordinator.awaitBackend()` 的 15s 长排队等待循环中（734-746 行），务必保证高频响应 `isStopped()`（或被 `requestStop()` 中断），防止长时间阻塞引发客户端 ANR。

### 5. 可选 (MAY)
- **MAY-1**: 可为 `PreviewPlayer` 提供一个“无等待借用”或 `tryLock(短时间)` 的机制。当系统通道正在连续高负载朗读时，试听请求可以快速失败提示“引擎忙”，而不是挂起死锁等待。

### 6. 我无法确认/需实查的点
- **未核实**：Flutter 端的 `EngineBridgePlugin` 究竟在试听完成后触发了哪一条具体指令？需要查阅 `:app` 侧的 Dart 源码才能确证重载风暴的触发源。
- **未核实**：`SherpaBackend.kt` 的实际实现源码（假定它内部已经受 `NativeGate` 完全保护）。
- **未核实**：目标阅读器（如 Legado）本身的 TTS 请求超时上限。若客户端自身超时设为（如）5 秒，那么即使本引擎放宽到 15 秒，客户端也会在中途主动强行掐断 Binder 连接。
