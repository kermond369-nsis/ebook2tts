# P7-D 评估稿｜密钥与本地存储加密（结合本项目实证 + 社区通行做法）v0.1

> 状态：草案，待 agy 复核 + 用户确认。**本文档不含任何密钥值。**
> 实证来源：2026-09-16 真机（Mi MIX 2S）+ 模拟器（MuMu x86_64）现场验证。

## 一、本项目现状（实证，不是推测）

| 项 | 实测结论 | 证据 |
|---|---|---|
| 用户 API Key 存储位置 | `files/mmkv/ebook2tts_cfg`（MMKV），键名 `online.apiKey` | root 下 `strings` 直接读出键名与值 |
| 该 MMKV 是否加密 | **否 —— 明文**（键名、值均可读；MMKV 加密后连键名都不可读） | 同上；对照 `model.id`/`status.*` 同为明文 |
| 密钥可提取条件 | ① 设备 root；② **debug 构建可用 `run-as` 读取**（无需 root）；③ `allowBackup=true` 时可随备份外流 | 本次用 `adb root` 提取成功；同时安装了 1.5 GB debug 包 |
| 备份设置 | `allowBackup=true`（BUG-P7-002） | Manifest |
| 传输 | HTTPS（`api.xiaomimimo.com` / `token-plan-cn.xiaomimimo.com`） | 代码 + 实测 |
| 密钥类型识别 | 已有 `online.keyKind` + `keyMismatchWarning`（billing `sk-` / plan `tp-`） | `OnlineSettings.kt` |
| 仓库内是否含密钥 | **无**（`.env`、`local.properties` 类文件均不在仓内） | 全仓检索 |
| APK 内是否内置密钥 | **无**（BYO-Key：密钥由用户在 App 内填写） | 代码路径 `ConfigStore.onlineApiKey()` |

**结论**：本项目在"**不进版本库、不内置密钥**"这两条上是对的；问题集中在"**密钥落地后的本地保护**"。

## 二、社区/官方做法（可查证）

1. **Google 对构建期注入的官方定性**（secrets-gradle-plugin README 原文）：
   *"Since your key is part of the static binary, your API keys are still recoverable by decompiling an APK."*
   ⇒ 构建期注入**只防进版本库，不防逆向**；本项目**连这一步都不需要**（用户自带密钥）。
2. **Google Cloud 的 key 限制**（包名 + SHA-1）可加，但 [Guardsquare 分析](https://www.guardsquare.com/blog/google-api-key-restirctions-mobile-app-security)指出其*易绕过、不能有效阻止请求伪造*，混淆仅能挡自动化爬取。
3. **Android 官方推荐**：不要把 API key 放进版本库；用 gitignore 的 properties 文件 + CI Secrets 注入；仓库内只提交占位 `*.defaults`。
4. **面向"用户自己付费服务"的开源客户端（与本项目同类）通行形态 = BYO-Key**：App 不带密钥，用户填写，**存进加密存储**；项目若想提供免费额度，必须走**自建后端代理**，绝不把维护者密钥打进 APK。
5. **测试侧**：单测用 MockWebServer 打桩，不碰真密钥（**本项目已符合**：`OnlineBackendTest` 即 MockWebServer）；集成测试用环境变量 + 缺失即 skip；CI 用 Secrets。
6. **防呆**：开 GitHub secret scanning / push protection（今日事故证明其必要性）。

## 三、建议整改（按优先级）

### P0 —— 密钥落地保护（对应 BUG-P7-002，建议纳入已批准的文档批次）
1. **密钥改存 Keystore 支撑的加密存储**，二选一（实现难度差异小，择一即可）：
   - 方案 A：MMKV 用 `cryptKey`（32 字节随机密钥，由 **Android Keystore** 生成/保管，不落盘明文）；
   - 方案 B：仅把 `online.apiKey` 单独放进 `EncryptedSharedPreferences`（其余配置留在 MMKV）。
   > 现状是**整份配置明文**（含模型状态、音色、密钥），改后即使 `strings` 也只能看到密文。
2. **`android:allowBackup="false"`**，并显式给出 `android:dataExtractionRules`（Android 12+）与 `android:fullBackupContent`（≤11），
   确保密钥文件既不进云备份也不进 `adb backup`。
   （若坚持保留备份：则必须把 MMKV 目录**排除**在备份之外。）
3. **debug 构建纪律**：debug APK 可被 `run-as` 读取私有目录 ⇒
   - 明确 debug 包**不得外发**（今天的 1.5 GB debug 包仅用于本机调试）；
   - release 保持 `debuggable=false`（默认）。
4. **不在任何日志/错误串里回显密钥**：`OnlineBackend` 现在的错误串会带 HTTP 状态与响应体片段（≤200 字符）——
   需确认服务端错误体不会回显 Authorization；建议对错误串做一次"敏感模式"过滤（`sk-`/`tp-`/`Bearer` 等一律打码）。**这条与今日我方事故同源。**

### P1 —— 配套与流程
5. **`.gitignore` 覆盖面**：`local.properties`、`secrets.properties`、`*.jks`、`key.properties`、`.env*` 全部纳入；仓库内提供 `*.defaults` 占位示例。
6. **CI 开 secret scanning + push protection**；提交历史做一次密钥扫描（今日已发生一次泄露，轮换已完成）。
7. **release 签名密钥**（与 API key 无关但同类）：`key.properties` + keystore 不进仓库、不进 CI 日志（B1 阻塞项）。
8. **UI 侧**：密钥输入框遮蔽显示、提供"清除密钥"、保留现有 `keyKind` 自动识别与类型不匹配告警（已有 ✓）。

### 不做（明确排除）
- ❌ **不加"debug-only 从设备文件读密钥"这类后门**：开发者调试便利不应改变产品代码路径；开发者自己的调试密钥走构建期注入或 CI Secrets（社区通行）。
- ❌ **不把密钥写进 `BuildConfig`**：对 BYO-Key 形态无收益，反而固化进二进制。
- ❌ **不做证书固定（pinning）**：公开 API 场景收益低、维护风险高。
- ❌ **不做自研加密**：只用平台 Keystore + 成熟库。

## 四、威胁模型（说清楚"防什么、不防什么"）

| 场景 | 现状 | 整改后 |
|---|---|---|
| 非 root 设备被普通恶意应用读取 | 不泄漏（`/data/data` 隔离） | 不泄漏 |
| **debug 包用 `run-as` 读取** | **泄漏** | 仍泄漏（属构建纪律，非加密能解）⇒ 靠不外发 debug 包 |
| **云备份 / `adb backup` 外流** | **泄漏**（allowBackup=true） | 不泄漏 |
| 设备 root / 取证工具 | 泄漏 | 需 Keystore 才能解（且用户凭证仍可被内存抓取） |
| 服务端被冒用额度 | 取决于 key 泄露 | 同前；**额度安全最终靠服务端限额 + 用户可撤销** |

> 诚实结论：**客户端不存在"绝对安全"**；本轮整改的目标是"把一次性、低成本、批量化的泄露路径全部堵掉"（备份、debug、明文落盘），而不是对抗具备 root/取证的对手。

## 五、待 agy 复核的点
1. P0 两条方案（MMKV cryptKey vs EncryptedSharedPreferences）在**本仓现有依赖（MMKV 2.4.2 / minSdk 27）**下的取舍；
2. `androidx.security:security-crypto` 当前维护状态（是否已废弃/是否应改用 Keystore + AES-GCM 直接实现）——**我未确认，标为待核实，请 agy 一并核对**；
3. 备份排除写法（`dataExtractionRules` 与 `fullBackupContent` 的字段口径）是否覆盖 MMKV 目录；
4. 是否有被我遗漏的落地路径（例如 WebView/导出/剪贴板/通知栏回显）。

---

# 附录 A：agy 复核意见（2026-09-16，gemini-3.1-pro-high，headless 只读）

**agy 采纳的项**
1. 备份策略：**首选 `allowBackup="false"`**（"一剑封喉"，同时掐断 `adb backup` / 云备份 / D2D 换机迁移）；若必须保留备份，
   排除口径须写成：≤11 `<exclude domain="file" path="mmkv"/>`；≥12 在 `<cloud-backup>` **与** `<device-transfer>` **两个节点下都要写**。
2. **把"密钥输入框遮蔽 + 页面 `FLAG_SECURE`"从 P1 提到 P0**：理由——可同时封堵"最近任务快照"泄露（系统会把配置页缩略图存进系统目录）。
3. **否掉我的 P0-4（用正则给日志里的 `sk-`/`tp-`/`Bearer` 打码）**：指出这正是今日事故的同源反模式（"大海捞针式的救火"，
   服务端换前缀/换业务线即失效）。改为**防线前移**：
   - OkHttp `HttpLoggingInterceptor.redactHeader("Authorization")`；
   - 凭证类错误**不透传服务端字符串**，统一抛 `AuthException("认证失败，请检查密钥")`。
4. 补充禁止项：**严禁用 `Intent`(Extras) 跨组件传递密钥**（可被 logcat/第三方嗅探）。

**agy 主动补的威胁路径**：最近任务快照（Task Snapshot）、第三方输入法/剪贴板历史、OOM/崩溃转储与第三方崩溃上报（Tombstone、Crashlytics 类）可能带出内存中的明文。

**我方对 agy 的纠正（附证据）**
- agy 称 "`androidx.security:security-crypto` **未被废弃**，可放心使用" —— **此条不成立**，已核实：
  - Android 官方 Cryptography 文档：Jetpack Security Crypto 的**全部 API 已于 1.1.0 起废弃、不再有新版本**；
  - 该类库在 **2025-04 的 `1.1.0-alpha07` 被标注废弃**（社区 fork `ed-george/encrypted-shared-preferences` 的说明与
    Google 源码 javadoc `@deprecated Use SharedPreferences instead` 均可佐证）；
  - 社区现行迁移方向 = **DataStore + Google Tink**（或 Keystore 直接 + AES-GCM）。
- ⇒ **结论：不引入已废弃库**；采用"平台 Keystore + 成熟加密库（Tink）"路线（见附录 B）。

# 附录 B：整改定稿 v0.2（据复核修订）

| 级别 | 事项 | 说明 |
|---|---|---|
| **P0-1** | 密钥隔离存储 | **不引入 security-crypto**；用 **Google Tink（`AndroidKeysetManager` + `StreamingAead`/`Aead`）** 加密后落盘（容器可用 MMKV/独立文件），或平台 Keystore + AES-GCM。Keystore 保管密钥集，磁盘只见密文 |
| **P0-2** | 关闭备份 | `allowBackup="false"`；若保留备份则按附录 A 的双节点排除口径，并**同时排除加密后的密文文件** |
| **P0-3** | 凭证不外泄（防线前移） | `redactHeader("Authorization")`；凭证错误统一抛 `AuthException`，**不透传服务端原文**；严禁 `Intent` 传密钥 |
| **P0-4** | 界面防泄漏（由 P1 提升） | 密钥输入框 `textPassword`；设置页 `FLAG_SECURE`（禁截屏/录屏/快照） |
| **P1** | 迁移与清理 | 首次启动把 MMKV 中的明文 `online.apiKey` 迁入加密存储并**从 MMKV 删除**；提供"清除密钥"入口 |
| **P1** | 仓库与 CI | `.gitignore` 覆盖 `local.properties`/`secrets.properties`/`*.jks`/`key.properties`/`.env*`；开 GitHub secret scanning + push protection |
| **不做** | —— | debug-only 密钥后门、`BuildConfig` 内置密钥、证书固定、自研加密算法 |

# 附录 C：v0.3 结论修正（用户裁定 + 我方撤回）

## C1 撤回"导出绑定面"整改（原作者认识错误）
- **原判断**：`LocalTextToSpeechService` `exported=true`、且平台未定义 `BIND_TTS_SERVICE` ⇒ 存在被任意应用直接绑定的攻击面，拟收敛导出面。
- **用户裁定（2026-09-16）**：本 App 的**产品定位就是"一个正常的系统 TTS 引擎"**，被其它应用绑定/调用是**设计目的**，
  不是漏洞；"哪怕被滥用也属正常逻辑之内"。
- **采纳**：**撤回**该条（原 P0-2 整条作废）。不再为"收紧绑定"做任何改动——收紧会直接破坏产品功能。
- 附：3 个 `exported=true` 的 activity（`CheckTtsData` / `GetSampleText` / `InstallTtsData`）同为 **TTS 引擎框架约定**，
  导出是必须的；动态 receiver 已正确使用 `RECEIVER_NOT_EXPORTED`（无外部触发面）。
- **因此"最小测试客户端验证绑定"不再需要执行**（问题本身不成立）。

## C2 BUG-P7-010 重新定级（无效权限声明）
- 原定级：P3「`BIND_TTS_SERVICE` 为无效权限声明 ⇒ 服务裸奔」。
- **修正定级**：**信息级（不作整改）**。理由：① 该权限是 AOSP 引擎写法的事实约定，保留有利于跨 ROM 一致性；
  ② 平台是否定义该权限属**平台侧差异**，与"服务是否该被绑定"这一产品行为无关；
  ③ 实测 TTS 端到端可用（作为默认引擎、第三方阅读器朗读成功），说明绑定链路正常。
- 处置：**保留声明 + 在架构书注明"该声明为平台约定；服务可被第三方调用属产品预期，不做权限收紧"**。

## C3 唯一遗留的产品决策（非安全问题）
在线模式下，设备上任意应用触发的朗读都会消耗**用户自己的在线额度**。这是"在线 TTS 引擎 + 共享设备"的固有结果，
属**产品策略**而非安全缺陷。可选策略（待用户裁定，本轮不实施）：
1. 第三方调用一律走**本地合成**，在线仅在**本 App 内**（用户显式操作）启用；
2. 或提供开关"允许第三方应用使用在线合成"（默认关）；
3. 或维持现状（用户额度自己对账）。

# 附录 D：产品模型修正（用户 2026-09-16 明确，直接影响本评估与新老结论）

## D1 产品设计理念（权威口径）
- **App 不预置任何模型**：首次进入由用户选择**在线模式**或**本地模式**，并可选择「仅在线 / 仅本地 / 谁优先」。
  ⇒ 目的：**极大压缩发包体积**。
- **本地不是回退路径，而是与在线等价的一等路径**：为"有高性能手机、但没有在线套餐"的用户而存在。
  （由来：最初只打算做在线，后来考虑到相当多人没有套餐但有性能机。）
- **不管控任何第三方调用**：App 只做好自己该做的事；外界的攻击面、系统本身对引擎的正常/非正常调用**一律不管**。

## D2 本评估稿据此修正的结论
1. **附录 C3（在线额度策略三选一）整条撤回** —— 与"不管控第三方调用"冲突，不再提出。
2. **撤回"在线优先 / 本地兜底"的一切表述**：正确表述是"**双模式等价，用户选择优先关系**"。
   （此前 P7-A 基线报告附录中我写过"支持在线优先/本地兜底的取向"，属错误表述，以本条为准。）
3. **BUG-P7-017（本地合成慢）升格**：既然本地是与在线等价的用户选择，那么"在最低配目标机（SDM845）上
   24 字 > 37 s"就不是"兜底路径不理想"，而是**选中本地模式的用户拿到不可用产品** ⇒ 属**核心质量缺陷**，
   与发包体积/模式选择同属 P7 主线。
4. **P7-D（包体/R8/依赖）与产品目标同向**：既然核心目标之一是"压体积"，release 侧的 R8 收缩/资源裁剪
   不是可选项而是主线目标 ⇒ **keystore 阻塞项的价值上升**（或采纳"临时 debug keystore 签验证版"的方案先跑出数字）。
5. **ABI 收敛（arm64-v8a + x86_64）与不预置模型**共同构成体积策略的两个手段（前者已实施）。

# 附录 E：P7-D 阻塞解除与构建实录（2026-09-16）

## E1 结论修正
- **原判断（我此前说错）**："P7-D 卡在 release keystore，整批做不了"。
- **事实**：Flutter 模板默认 `signingConfig = signingConfigs.getByName("debug")` ⇒ **release 构建本就可用**（用调试密钥签）；
  R8 / 资源收缩 / `lintRelease` / 依赖验证**与签名无关**。签名只影响**更新连续性**（同一密钥才能签后续更新）。
- 处置：生成自签名发布密钥并接入（存在 `key.properties` 用发布密钥，否则回退调试密钥，保证他人 clone 可构建）。

## E2 发布密钥（已交付 SMB `p7/signing/`）
| 项 | 值 |
|---|---|
| 别名 | `ebook2tts` |
| 算法/有效期 | RSA 4096，自签名 `SHA384withRSA`，2026-09-16 → **2056-09-08** |
| 证书 SHA-256 | `7D:37:9C:A3:BF:87:2B:AB:02:2F:2C:38:AB:61:F8:95:1A:6B:81:B5:86:09:83:E6:81:3B:33:5C:F5:97:F6:42` |
| Android 是否需要 CA | **不需要**（自签名证书即满足安装与更新校验） |

## E3 release 产物实测
```
✓ Built app-release.apk (417.3MB)
签名核验: Signer #1 certificate DN: CN=Kermond, OU=ShuShengLocal, O=ebook2tts, C=CN
          SHA-256: 7d379ca3bf872bab022f2c38ab61f8951a6b81b5860983e6813b335cf597f642  ← 与交付密钥一致
包体:     debug 1.5 G  →  release 398 M（R8 + 资源收缩生效；mapping.txt 位于 app/build/outputs/mapping/release/）
ABI:      arm64-v8a 7 + x86_64 7，**无 armeabi-v7a 残留**（ABI 收敛在 release 同样成立）
```

## E4 施工教训（写给后续 Agent，避免重复踩）
1. **Kotlin DSL 中 `java` 会被项目的 `java` 扩展遮蔽**：在 `build.gradle.kts` 里写 `java.util.Properties()` 会报
   `Unresolved reference 'util'`（连带 `Properties.isNotEmpty/getProperty` 等一串假错误，本次共 12 条）。
   正解：文件顶部显式 `import java.util.Properties`，然后只用 `Properties()`。
2. **构建日志别用 `tail -N` 截断**：首次失败时 `tail -6` 把真实报错吞掉，只留 `BUILD FAILED`，反而多花一轮定位。
   正解：全量落盘到文件，再用 `grep -nE "What went wrong|Script compilation|^  Line"` 取诊断段。
3. **改完 `build.gradle.kts` 必须真跑一次 release 构建**（脚本编译错误只在构建时暴露，`flutter analyze` 看不见）。

# 附录 F：release 包装机冒烟 + 备份关闭的设备侧实证（2026-09-16）

```
卸载旧包（调试密钥签）: Success
安装 release 包（发布密钥签, 398M）: Success（streamed install，32.3s）
冷启: 进程 6554 存活；FATAL EXCEPTION / ANR 计数 = 0
包信息: versionName=0.2.0-alpha.1, signatures=[f64c2596]（发布密钥）
flags=0x0 → 不含 ALLOW_BACKUP ⇒ 备份资格已关闭
dumpsys 中该包无 backup/dataExtraction 记录 ⇒ 系统不为该包保留备份通道
```
- **注意**：测试机按甲方 2026-09-16 约定视为可随意删改（已记入项目 `AGENTS.md`），
  卸载导致模型与配置丢失**不构成损失**，不再就此类操作请示。
- 未装模型时引擎进程懒启动（`:tts_service` 不常驻）属预期。
