# app · 书声本地 界面层

本目录是「书声本地」的 **Flutter 界面**（Dart 侧），与引擎（`:core` / `:engine`）通过 Pigeon 桥接通信。
**界面进程零推理**：一切合成发生在引擎进程 `:tts_service`。

## 六个页面

| 页面 | 作用 |
|---|---|
| 引导 | 三步上手：下载模型 → 设为系统朗读 → 完成；显示引擎状态与模型/音色数量 |
| 模型 | 三档模型管理（下载 / 取消 / 删除）、自定义下载镜像、体积与进度 |
| 音色库 | 中英音色列表：**「试听」只播样音、不改旁白**；「设旁白」才改变全局音色 |
| 示例朗读 | 输入任意文本试播当前旁白；内置「多角色示例」；显示当前音色 / 语速 / 模式 |
| 设置 | 语速滑杆、在线朗读总开关（**单独同意**弹窗）、AI 角色音色开关、密钥（按量计费 / Token Plan 两类）、系统 TTS 入口、完整免责声明 |
| 诊断 | 引擎状态、最近一次合成指标、日志 |

## 关键语义（改动前先看）

- **试听 vs 旁白**：音色库「试听」传具体音色 id ⇒ 引擎**强制本地**合成该音色（在线模型没有这些音色）；
  示例朗读/跟随旁白场景传空 ⇒ 允许走在线，在线音色按**旁白性别**映射。
- **在线开关**：默认关；打开需二次确认弹窗（单独同意），关闭即撤回同意。
- **密钥**：两类密钥与域名绑定，不可混用；输入框留空时点「校验密钥」= 校验**已保存**的密钥。
- **配置单写者**：配置统一由引擎侧 MMKV 持有，界面只读写桥接接口（不使用 shared_preferences）。

## 开发

```bash
flutter pub get
flutter analyze          # 期望 0 issues
flutter test             # widget 测试
flutter build apk --debug
# 产物：build/app/outputs/flutter-apk/app-debug.apk
```

- Pigeon 桥接文件：`lib/engine/engine_bridge.g.dart`（与 Kotlin 侧 `EngineBridge.kt` 成对）
- 法律文本常量：`lib/content/legal_texts.dart`（**逐字**取自交付文档，不得改写）
- 设计令牌：`lib/theme/tokens.dart`

> 本 App 属于「书声本地」仓库的界面层；构建入口在 `app/android`（独立 Gradle 构建），
> 请勿在仓库根调用 `:app:*`。
