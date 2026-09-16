# P7 真机报告 — Mi MIX 2S（SDM845 / Android 14）

> 生成：2026-09-16 ｜ 实施线：Hermes ｜ 设备：**直接 USB 接入本服务器**（`adb -s [DEVICE-SERIAL]`）
> 定位：**低端锚定机**（NFR §5.1「保底档」参照）｜ 用途：P7-D 性能调优目标机
> 口径声明：被测包为 **debug 构建**（未剥离符号、Flutter debug 引擎）⇒ 绝对值偏保守，**正式包应更优**；同一设备上的前后 A/B 仍有效。

---

## 一、设备画像（实测）

| 项 | 值 |
|---|---|
| 机型 / SoC | Xiaomi **Mi MIX 2S**（`polaris`）/ **sdm845**（`ro.board.platform`） |
| 系统 | **Android 14（SDK 34）** 定制 ROM（`eng.onelot.20240904`，user/release-keys，`ro.debuggable=0`，SELinux Enforcing） |
| CPU | **8 核**：小核 max 1.766 GHz ×4（`cpu0-silver-usr`）＋ 大核 max 2.803 GHz ×4（`cpu3-gold-usr`） |
| ABI | `arm64-v8a`（abilist: arm64-v8a, armeabi-v7a, armeabi） |
| 内存 / 存储 | 7.82 GB RAM ／ `/data` 余 ~120 GB |
| 屏幕 | 1080×2160 @ 440 dpi |
| 温度探针 | `cpu0-silver-usr` / `cpu3-gold-usr` / `gpu0|1-usr` / `aoss*`（**空闲 40–42 ℃**，压测后见 §四） |
| 已装参照 | **Legado** `io.legado.app.release`（注意：与模拟器包名 `com.legado.app.release` 不同） |
| root | **无**（shell uid；`dropbox`/tombstone 取证受限） |

**测量环境处置（已记录，便于还原）**：`window_animation_scale / transition_animation_scale / animator_duration_scale` 由 1.0 置 **0**；`svc power stayon true`；**默认 TTS 引擎设为 `com.kermond.ebook2tts`**（供阅读器链路测试）。

---

## 二、冷启动与内存（debug 构建）

| 指标 | 真机（MIX 2S / arm64） | 模拟器（MuMu x86_64） |
|---|---|---|
| UI 冷启动 `am start -W` ×5 | 3285 / 3154 / 3158 / 3164 / 3153 → **中位 3158 ms** | 1816–2103 → **中位 1959 ms** |
| 主进程 PSS | **305 MB** | — |
| 两进程合计 PSS | （引擎未载入时）约 305 MB | 314 MB（含引擎） |

⇒ 真机比模拟器慢 **≈1.6×**（UI 冷启）；量级差异说明**模拟器数据不能替代真机**（与 AR-§1.6 一致）。

---

## 三、模型下载（真机直连外网，无代理）

| 项 | 实测 |
|---|---|
| 目标 | `kokoro-int8`（147,031,220 B） |
| 选源打点 | `picked|https://ghproxy.net/…|**rate=37.8KB/s**` |
| 实际吞吐（progress 打点推算） | ~93 KB→44 MB 期间峰值 **≈1.15 MB/s**，稳态 **≈1.1 MB/s** |
| 整包耗时 | ≈3.5 分钟（07:51:23 → 07:55） |
| **探测 vs 实下** | **低估约 30 倍** ⇒ **确证 P6 结论「64KB Range 探测严重低估」**（该条从"推测"升级为"真机实测"） |
| 结果 | SHA/解压/`.completed` 哨兵均正常；解压后 **207 MB** |

---

## 四、🔴 真机暴露的关键问题（按严重度）

### 4.1 BUG-P7-011 严重性**升级**：冷加载 12 秒，且"提高等待预算"**不足以解决**

| 轮次 | `PREVIEW|start` | 模型就绪（`loaded … threads=7`） | 冷加载耗时 |
|---|---|---|---|
| 第 1 次（干净复测） | 08:00:15.855 | 08:00:27.949 | **12.09 s** |
| 第 2 次（另一轮） | 07:57:49.018 | 07:58:01.258 | **12.24 s** |

- **稳定复现 ≈12.1 s**（kokoro-int8 / SDM845）——NFR「引擎就绪 ≤1.5 s」在该机**差 8 倍**；
- 而系统 TTS 通道的等待预算仅 **1500 ms** ⇒ **首个请求必然被拒**（比模拟器的"差 83 ms"严重得多）；
- **重要推论**：上一轮给 agy 的方案①"把预算提到 3000~5000 ms"**无法覆盖 12 s** ⇒ 修复必须以 **②服务创建时异步预热** 为主，辅以"首请求排队而非立即报错"，预算提高只作兜底。

### 4.2 BUG-P7-003 真机确证（Android 13+ 通知权限）

| 证据 | 值 |
|---|---|
| 权限状态 | `POST_NOTIFICATIONS: **granted=false**`（`cmd appops … POST_NOTIFICATION` = `ignore`） |
| 下载 FGS | `Background started FGS: Allowed … cmp=…/DownloadService`；`isForeground=true … foregroundNoti=Notification(channel=download…)` |
| 通知栏可见性 | `dumpsys notification --noredact | grep pkg=com.kermond.ebook2tts` → **0** |

⇒ 下载在后台正常进行，但用户**看不到任何通知**（进度/完成均不可见），与 RQ-202 体验目标不符——**真机确证成立**。

### 4.3 🔴 新发现（待深挖）BUG-P7-014：**每次试听结束后触发整模型重载**

现象（真机连续试听）：

```
08:01:02.111  PREVIEW|start|voice=zf_4|chars=24          ← 热态（模型已驻留）
08:01:33.803  PREVIEW|progress=100
08:01:38.802  SherpaBackend: loaded sr=24000 … threads=7  ← 又一次"重载"（≈5 s）
08:01:39.105  PREVIEW|done
```

- 同一现象在 07:58:26 那轮表现为 **总时长 82 s**（24 字）；
- **触发源（代码定位）**：`ConfigStore.notifyReload()`（广播 `ACTION_ENGINE_RELOAD`）→ `LocalTextToSpeechService` 收广播 → `coordinator.scheduleReload(reason)`（400 ms 去抖后）→ `sm.requestBackendReload()` → `reloadInternal()`；调用点集中在 **`EngineBridgePlugin`** 的 `bridge_active_model` / `bridge_narrator` / `bridge_config`（App 侧写配置/同步状态即触发）；
- **影响**：真机单次重载 ≈5 s CPU（冷加载 12 s）；**连续操作时后一个请求要等前一个重载完成**，造成 TTFT 剧烈波动与无谓耗电；
- **待办**：确认 Flutter 侧在"试听"流程里究竟调用了哪条桥接方法（候选 `bridge_active_model`），并评估"配置未实质变化时不重载"的收敛策略。
- **状态**：成立（现象确证；触发链与收敛方案待 P7-D 深挖）

### 4.4 其他观察（真机）

- `sherpa-onnx` 日志出现 `Unknown token: ❓`（词表缺该符号，属上游告警，非崩溃）——登记观察项；
- 引擎线程数 `threads=7`（8 核 → `clamp(核数-1,2,8)` ✓ IM-522 生效）；
- 温度：空闲 40–42 ℃ → 下载/合成负载后 **59.8–62.2 ℃**，随后回落 44–45 ℃；**热漂移显著 ⇒ 性能 A/B 必须随记温度**（agy MAY-1 的提醒在真机上必要）。

---

## 五、本机已完成的修复验证（在真机上）

| 项 | 结论 |
|---|---|
| 模型下载全链路（多源探测→续传→解压→哨兵→可用） | ✅ 真机通过（147 MB，~3.5 min） |
| 试听本地合成链路（`PREVIEW|playing`，`threads=7`） | ✅ 出声正常（但受 §4.3 重载影响，时长波动） |
| 应用启动/UI/徽标三态 | ✅ 正常（"未安装模型"→安装后转"待朗读时启动"） |
| MMKV 2.4.2 升级（`b6d0e5e`） | ⏳ 已提交到私密仓；真机复核待装新包（见 §六） |

---

## 六、下一步（真机）

1. 装新包（MMKV 2.4.2 + 双 ABI debug）→ 校验配置**跨版本迁移**（1.3.9 写入的 `ebook2tts_cfg` 能否被 2.4.2 读取）；
2. 复现并修 BUG-P7-011/014（预热 + 重载收敛）后，用本机做**前后 A/B**（同文本、同温度区间、各 10 次取中位）；
3. 保底档补测 **VITS zh-ll**（NFR 保底档推荐模型）在本机的 TTFT/RTF/内存；
4. 功耗按 NFR §5.1 标准条件（灭屏、飞行模式、耳机 50% 音量、1h 连续朗读）——需约 1 小时独占设备。
