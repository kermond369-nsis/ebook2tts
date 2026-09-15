// P3 界面烟雾测试：导航壳可切换，六个页面均有真实内容（无空白页）。
//
// 说明（2026-09-15 修订）：
// - 本测试在 P4 批次前即为**红**（基线上同样失败，非本批次引入）：原断言在 50ms 内取状态，
//   且设置页是懒加载列表——屏外条目**未被构建**，直接 `find.text` 必然找不到；
// - 现修订为：①给状态/事件流留出落地时间；②需要屏外内容时先滚动到可见再断言。
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:ebook2tts/engine/mock_engine_service.dart';
import 'package:ebook2tts/main.dart';
import 'package:ebook2tts/state/providers.dart';

Future<void> _pumpApp(WidgetTester tester) async {
  // IM-511：Mock 已改为内存态，不再需要 shared_preferences
  final engine = await MockEngineService.create();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [engineServiceProvider.overrideWithValue(engine)],
      child: const Ebook2TtsApp(),
    ),
  );
  // 首屏状态来自异步 status()，给足时间再断言
  await tester.pump(const Duration(milliseconds: 400));
}

void main() {
  testWidgets('导航壳可切换，六个页面均非空白', (WidgetTester tester) async {
    await _pumpApp(tester);

    // 引导页内容（页面为滚动容器，主按钮在首屏之下 ⇒ 断言用 skipOffstage: false，
    // 判据是「文案已在组件树中渲染」，而不是「恰好落在可视区」）
    expect(find.text('书声本地'), findsWidgets);
    expect(find.text('去下载模型', skipOffstage: false), findsOneWidget);
    expect(find.text('当前状态', skipOffstage: false), findsWidgets);

    // 逐页切换：每页都应有可读的中文标题或内容
    for (final entry in <String, String>{
      '模型': '下载镜像（可选）',
      '音色': '中文音色（优先）',
      '朗读': '要朗读的文本',
      '设置': '在线朗读',
      '诊断': '引擎状态',
      '引导': '当前状态',
    }.entries) {
      await tester.tap(find.text(entry.key).last);
      await tester.pump(const Duration(milliseconds: 200));
      expect(find.text(entry.value, skipOffstage: false), findsWidgets,
          reason: '页面「${entry.key}」应渲染内容：${entry.value}');
    }
  });

  testWidgets('默认值：在线朗读关、数据流量关、按量计费', (WidgetTester tester) async {
    await _pumpApp(tester);

    await tester.tap(find.text('设置').last);
    await tester.pump(const Duration(milliseconds: 300));

    // 首屏可见区：在线朗读默认关（含单独同意提示）
    expect(
        find.text('已关闭：朗读只在本机完成。开启前会先请你单独确认外发告知。',
            skipOffstage: false),
        findsOneWidget);
    expect(find.text('按量计费 API Key（推荐）', skipOffstage: false), findsOneWidget);

    // 屏外：网络策略区（懒加载列表 → 先滚动到构建出来再断言）
    const wifiOff = '已关闭（默认）：只在无线网络下下载模型与联网朗读。';
    await tester.scrollUntilVisible(
      find.text(wifiOff),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text(wifiOff), findsOneWidget);
  });
}
