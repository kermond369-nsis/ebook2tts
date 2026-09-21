# 附件V — agy 复核报告：代码清理批次（RQ-515 判定链路下线 / IM-PENDING-4）

> 版本 **v1.0** ｜ 2026-09-21 ｜ 复核方：**agy**（`gemini-3.8-flash-high` + `--effort high`，美国 AI 专线出口 7892）
> 被复核对象：本批次工作区改动（代码侧：`LocalModeAdvisor` 下线 + `MainActivity`/`SystemBridge`/`ModeSetupPage` 三处引用清理 + 无条件告知）
> 复核方式：**单轮内联**（代码 diff 全文 + 甲方口径 + 本仓库 9 条防呆红线），明确禁止其读文件/用工具
> 复核方结论：**有条件通过（CONDITIONAL PASS）** —— 条件项＝修复「同一页面内重复点击会反复弹窗」

## 一、复核方 9 条防呆红线逐条核验表（原文摘录）

| 序号 | 红线 | 适用性 | 结论 |
| :--- | :--- | :---: | :---: |
| ① | 绝不为内部原因中止在途朗读请求 | 不适用 | 合规 |
| ② | 绝不在已有音频时调用 error() 强停 | 不适用 | 合规 |
| ③ | 绝不「假成功」 | **适用** | 合规（彻底移除原 `unknown_soc` 等 fail-open 静默伪装路径） |
| ④ | 主进程零推理、`:tts_service` 零 Flutter | **适用** | 合规（`MainActivity` 判定通道与函数一并移除，主进程进一步净化） |
| ⑤ | native 释放只在守卫锁内、无持有者时 | 不适用 | 合规 |
| ⑥ | 微淡化仅首块头部/末块尾部 | 不适用 | 合规 |
| ⑦ | 合成线程严禁 IO/去抖/锁长等待 | 不适用 | 合规 |
| ⑧ | 存量模型回填 `.completed` 前必须过清单校验 | 不适用 | 合规（`ModelCatalog.kt` 仅文案同步，未动校验逻辑） |
| ⑨ | SAF 导入必须强制经 SafeExtractor | 不适用 | 合规 |

**残留检查（复核方结论）**：编译层面干净（无悬挂引用/无用 import）；行为层面**无残留机型判定、白名单豁免或限制分支**。
**弹窗口径（复核方结论）**：无条件、无判定、无限制 ✅；位置＝选择本地之后 ✅；无「不再提示」勾选 ✅；文案与 RQ-519 口径一致 ✅。

## 二、实施线处置表

| # | 复核意见 | 严重度 | 处置 | 说明 |
|---|---|---|---|---|
| 4.1 | 同一页面内**重复点击**「本地模式」会反复弹窗（未记录已告知态），违背「首启只弹一次」的最小打扰原则 | 中 | **采纳并已修复** | 加守卫 `if (_onlineMode == false) return;`（已选本地则不再重复响应） |
| 4.2 | `showDialog` 默认 `barrierDismissible = true`：**点外部/返回键关闭**后仍会执行「选中本地」，属被动选中 | 低 | **采纳并已修复** | `barrierDismissible: false`（须显式点「我知道了」确认后才选中） |
| 4.3 | `system_bridge.dart` 删除数据类后留下连续空行（代码卫生） | 轻微 | **采纳并已修复** | 收敛为空行 1 行 |
| 5.2 | 删除 `LocalModeAdvisorTest.kt` 合理；**建议补** `mode_setup_page_test.dart`（固化：弹窗含「骁龙 8 Gen 1/天玑 9300」、无「不再提示」、确认后驱动状态） | 建议 | **采纳并已补充** | 新增 `app/test/mode_setup_page_test.dart`（3 条契约；运行结果见 §三） |

> **同日另有一项独立证据**：`app/test/widget_test.dart` 的 2 条用例在本批次**失败**，经 **A/B 对照**（临时还原本批次 app 侧改动后复跑，**同样失败、失败形态一致**）判定为**基线既有问题（与本批无关）**；已记入报告 §9.1，修复列为待办。

## 三、验证证据（可复算）

| 项 | 命令 | 结果 |
|---|---|---|
| 静态分析 | `flutter analyze` | No issues found（0 告警） |
| Flutter 测试 | `flutter test` | **新增契约测试 1/1 通过**（`mode_setup_page_test.dart`：选择本地模式 ⇒ 无条件弹告知、无「不再提示」勾选、确认后关闭）；`widget_test.dart` 2 条**基线既有失败**（见 A/B 行） |
| Kotlin 侧测试 | `./gradlew :core:test :engine:testDebugUnitTest -Porg.gradle.java.installations.paths=/opt/jdk17` | `:core` 93 / 0 失败；`:engine` 51 / 0 失败（含 `ManifestCodecTest` 5/5） |
| 残留扫描 | `grep -rn 'LocalModeAdvisor\|LocalModeVerdict\|localModeVerdict' --include=*.kt --include=*.dart .` | 除文档目录外**零命中** |
| A/B 对照 | 还原 app 侧改动 → `flutter test test/widget_test.dart` → 再恢复 | 基线**同样失败**（与本批无关） |
| 证据归档 | 交付目录 `证据-代码清理批次-20260921/` | `附件V-agy原始复核输出.txt`、`A-B基线-…log`、`flutter-…log`、`gradle-…log` 四份原始日志 |

## 四、状态

- 复核方「有条件通过」的**唯一条件项（4.1）已修复**；4.2/4.3 一并修复；测试覆盖建议已采纳。
- 本批次改动**未提交、未 push**（待甲方许可）；`flutter test` 运行结果以本文件末尾为准。
