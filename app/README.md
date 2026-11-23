# app · 书声 界面层

本目录是「书声」v2.0.0 的 **Flutter 界面**（Dart 侧），通过标准 `MethodChannel` + `EventChannel`（`EngineBridgePlugin.kt` / `method_channel_engine_service.dart`）与 `:engine` 通信。
**界面进程零合成**：在线合成、弱网超时回退与系统 TTS 转接均发生在引擎进程 `:tts_service`。

## 四页极简架构（+ 诊断页）

| 页面 | 作用 |
|---|---|
| 首页 | 运行概览、在线状态与系统 TTS 兜底状态、3 步启用清单、打开系统朗读设置 |
| 在线 | 在线朗读总开关（**单独同意**弹窗）、AI 角色造音色、小米 MiMo / OpenAI 兼容 / 自定义 HTTP 配置、MiMo 8 预置音色与密钥校验 |
| 朗读 | 输入或粘贴小说片段、内置「多角色示例」、语速即时调节与试播、显示实时状态与兜底提示 |
| 设置 | 全局语速、弱网首包超时阈值（4/6/8/12 秒）、系统自带 TTS 兜底引擎选择、移动数据策略、系统设置/诊断/免责声明入口 |
| 诊断 | 在线/兜底引擎运行概览、系统 TTS 通道对照测试、最近性能指标与运行日志 |

## 开发

```bash
flutter pub get
flutter analyze          # 期望 0 issues
flutter test             # widget 测试
flutter build apk --debug
# 产物：build/app/outputs/flutter-apk/app-debug.apk
```

- 平台通道桥接：`lib/engine/method_channel_engine_service.dart` ↔ `android/app/src/main/kotlin/com/kermond/ebook2tts/EngineBridgePlugin.kt`
- 法律文本常量：`lib/content/legal_texts.dart`
- 设计令牌：`lib/theme/tokens.dart`
