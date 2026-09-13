# GitHub / CI 备忘录（ebook2tts-local）

> 项目：`kermond369-nsis/ebook2tts-local`  
> 姊妹仓库：`kermond369-nsis/ebook2tts`（云端 MiMo 版）  
> 改 Actions、发版、署名前先看一遍。

---

## 1. Actions · Node 版本

| 要求 | 说明 |
|------|------|
| **必须用 Node 24 一代** | 不要只写 `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24` |
| 采用 | `checkout@v5` `setup-java@v5` `setup-gradle@v5` `setup-android@v4` `upload-artifact@v6` |
| Release | **`gh release create/upload`**，不用 `softprops/action-gh-release` |

**验收**：CI log 搜 `Node.js 20`，应为 0 条。

---

## 2. Release 策略

| 规则 | 说明 |
|------|------|
| main 每次 push | 自动 `build-<7位短SHA>` Release |
| 正式版 | 打 `v*` tag，versionName 与 tag 对齐 |
| 附件名 | `ebook2tts-local-<shortsha>-debug.apk` |

---

## 3. 版本号

- 0.2 重构基线：**`0.2.0-alpha.1`**（M1，对齐需求书/实现报告）
- `app/build.gradle.kts`：`versionName = "0.2.0-alpha.1"`

---

## 4. 署名

| 场景 | 正确写法 |
|------|----------|
| App 主界面作者 | **Kermond** |
| LICENSE | **kermond369-nsis** |
| 许可证 | MIT |

不要写入本机 Windows 用户名。

---

## 5. 仓库

| 项 | 值 |
|----|-----|
| 仓库 | `kermond369-nsis/ebook2tts-local` |
| 流程 | 先 private → 安全审查 → public |
| Topics | `android` `tts` `kotlin` `sherpa-onnx` `audiobook` `offline` |

---

## 6. 密钥与安全

- 禁止 `github_pat_…`、`sk-`/`tp-` Key 进仓库/Issue/Release
- CI：`contents: write` 即可
- git 用 `gh auth setup-git`

---

## 7. 构建约定

| 项 | 值 |
|----|-----|
| Gradle | 8.5 |
| AGP | 8.3.2 |
| JDK | 17 |
| SDK | android-34 + build-tools 34.0.0 |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk` |
| 本地 AAR | `engine/libs/sherpa-onnx-1.13.8.aar`（约 50MB，随仓库） |

---

## 8. 发版检查清单

- [ ] Actions 无 `Node.js 20` 警告
- [ ] Release 出现新 `build-<shortsha>` 或已更新 `v*`
- [ ] APK 文件名含短 SHA
- [ ] 无密钥
- [ ] versionName 与 tag 一致
- [ ] 界面作者 **Kermond**，LICENSE **kermond369-nsis**
