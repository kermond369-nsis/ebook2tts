# 附件W — agy 复核报告：widget 测试长期红根因修复（补丁复核）

> 版本 **v1.0** ｜ 2026-09-21 ｜ 复核方：**agy**（`gemini-3.8-flash-high` + `--effort high`，美国 AI 专线出口 7892）
> 被复核对象：`app/test/widget_test.dart` 补丁（首启闸门通道应答注入 + 事件泵补一次 `pump()`）
> 复核方式：**单轮内联**（完整 diff ＋ 根因诊断实据 ＋ 修复后验证结果），明确禁止其读文件/用工具
> 复核方结论：**【通过】**（9 条防呆红线全达标；附 1 条非阻断建议）

## 一、根因（本项目长期红的真实原因，已实证）

应用启动先经平台通道 `com.kermond.ebook2tts/system` 读 `getRouteMode`（**RQ-513 首启闸门**）；
**widget 测试未对该通道作答 ⇒ 通道 Future 永不完成 ⇒ 应用停在启动等待页（`CircularProgressIndicator`），主壳不渲染**。

诊断实据（临时用例打印，用例已删除）：
- 无 handler：`Shell=0 Setup=0 Spinner=1 texts=[]`（多次 pump 不变；`pumpAndSettle` 直接超时）
- 有 handler（`chosen=true`）：`Shell=1 Spinner=0 texts=[书声本地, 离线电子书朗读…, 第一步, …]`

## 二、复核方结论要点（原文摘录）

- 「**根因定位极其精确**……补丁对症下药」；
- 「Mock **不会掩盖真实缺陷**：打桩只针对宿主平台通信底层，Dart 侧从闸门解析、Riverpod 状态流到主壳挂载的**整条链路仍真实执行**；若解析/状态/构建出错，断言照样报红」；
- 「`setUp`/`tearDown` **成对闭合**、作用域仅限本测试文件，不污染其它测试文件」；
- 「补的 `pump()` **充分且不引入 Flaky**（FakeAsync 虚拟时钟，确定性高）」；
- 风险提示：**通道契约漂移**（原生返回结构若变更而 Mock 未同步 ⇒「测试绿、真机红」）——复核方认为已被同批次的契约测试与文档口径部分对冲；**实施线记为持续风险**（见 §四）。
- **非阻断建议**：在 `_pumpApp` 末尾增加 `expect(find.byType(CircularProgressIndicator), findsNothing);`，让闸门未解开时**秒级语义化失败**。

## 三、实施线处置

| # | 复核意见 | 处置 |
|---|---|---|
| 1 | 结论：通过（同意合入） | **采纳** |
| 2 | 建议：加 spinner 守卫断言 | **采纳并已实施** —— `_pumpApp` 末尾新增 `expect(..., findsNothing, reason: '首启闸门未解开：通道应答缺失或 status() 未落地')` |
| 3 | 风险：通道契约漂移（测试绿/真机红） | **记录为持续风险**：① 本批已在 `:engine` 侧保有通道编解码测试；② 后续若原生侧改返回结构，须同步 `SystemBridge` 解析与测试 Mock（写入《实现报告》§8.1 观察项） |

## 四、验证证据（修复 + 复核处置后，可复算）

| 项 | 命令 | 结果 |
|---|---|---|
| 静态分析 | `flutter analyze` | **No issues found** |
| Flutter 测试 | `flutter test` | **`00:03 +3: All tests passed!`**（契约 1 条 + widget 2 条） |
| Kotlin 侧 | `./gradlew :core:test :engine:testDebugUnitTest` | `:core` 93 / 0 失败；`:engine` 51 / 0 失败 |

**证据归档**：交付目录 `证据-代码清理批次-20260921/`（含本附件的原始复核输出 `附件W-agy原始复核输出.txt`）。

## 五、状态

本补丁与所在批次**已于同日提交**（见《实现报告》§8 变更记录 v1.9/v1.10 对应的 commit 短 SHA 回填）；**未 push**（按甲方指示不同步远端）。
