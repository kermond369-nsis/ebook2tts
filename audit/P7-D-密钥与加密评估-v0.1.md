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
