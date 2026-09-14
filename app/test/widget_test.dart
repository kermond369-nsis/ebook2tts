// P3 界面烟雾测试：导航壳可切换，六个页面均有真实内容（无空白页）。
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:ebook2tts/engine/mock_engine_service.dart';
import 'package:ebook2tts/main.dart';
import 'package:ebook2tts/state/providers.dart';

Future<void> _pumpApp(WidgetTester tester) async {
  SharedPreferences.setMockInitialValues(<String, Object>{});
  final prefs = await SharedPreferences.getInstance();
  final engine = await MockEngineService.create(prefs);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [engineServiceProvider.overrideWithValue(engine)],
      child: const Ebook2TtsApp(),
    ),
  );
  await tester.pump(const Duration(milliseconds: 50));
}

void main() {
  testWidgets('导航壳可切换，六个页面均非空白', (WidgetTester tester) async {
    await _pumpApp(tester);

    // 引导页
    expect(find.text('书声本地'), findsWidgets);
    expect(find.text('去下载模型'), findsOneWidget);

    // 逐页切换：每页都应有可读的中文标题或内容
    for (final entry in <String, String>{
      '模型': '下载镜像（可选）',
      '音色': '中文音色（优先）',
      '朗读': '要朗读的文本',
      '设置': '在线朗读',
      '诊断': '引擎状态',
      '引导': '当前状态',
    }.entries) {
      await tester.tap(find.text(entry.key));
      await tester.pump(const Duration(milliseconds: 50));
      expect(find.text(entry.value), findsWidgets,
          reason: '页面「${entry.key}」应渲染内容：${entry.value}');
    }
  });

  testWidgets('默认值：在线朗读关、数据流量关、按量计费', (WidgetTester tester) async {
    await _pumpApp(tester);

    await tester.tap(find.text('设置'));
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.text('已关闭：朗读只在本机完成。开启前会先请你单独确认外发告知。'),
        findsOneWidget);
    expect(find.text('已关闭（默认）：只在无线网络下下载模型与联网朗读。'), findsOneWidget);
    expect(find.text('按量计费 API Key（推荐）'), findsOneWidget);
  });
}
