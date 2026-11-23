// 单一模式重构后 UI 烟雾测试：验证 4 Tab 极简壳、在线多引擎与预置音色、弱网超时与系统 TTS 兜底选择、朗读与诊断页。
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:ebook2tts/engine/mock_engine_service.dart';
import 'package:ebook2tts/main.dart';
import 'package:ebook2tts/pages/diagnostics_page.dart';
import 'package:ebook2tts/pages/online_config_page.dart';
import 'package:ebook2tts/pages/sample_page.dart';
import 'package:ebook2tts/pages/settings_page.dart';
import 'package:ebook2tts/state/providers.dart';

Finder _pageScrollable(Type pageType) => find.descendant(
      of: find.byType(pageType),
      matching: find.byType(Scrollable),
    );

Future<void> _bringIntoView(
  WidgetTester tester,
  Finder target, {
  required Type page,
  double delta = 200,
}) async {
  if (target.evaluate().isEmpty) {
    await tester.scrollUntilVisible(
      target,
      delta,
      scrollable: _pageScrollable(page),
    );
  }
  await tester.ensureVisible(target);
  await tester.pump();
}

Future<MockEngineService> _pumpApp(WidgetTester tester) async {
  final engine = await MockEngineService.create();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [engineServiceProvider.overrideWithValue(engine)],
      child: const Ebook2TtsApp(),
    ),
  );
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 200));
  expect(find.byType(CircularProgressIndicator), findsNothing);
  return engine;
}

void main() {
  testWidgets('直接进入四页极简架构，无三模式与本地模型入口', (WidgetTester tester) async {
    await _pumpApp(tester);

    expect(find.text('书声'), findsWidgets);
    expect(find.text('系统兜底'), findsWidgets);
    expect(find.text('首页'), findsOneWidget);
    expect(find.text('在线'), findsOneWidget);
    expect(find.text('朗读'), findsOneWidget);
    expect(find.text('设置'), findsOneWidget);

    expect(find.text('模型'), findsNothing);
    expect(find.text('音色'), findsNothing);
    expect(find.text('纯本地模式'), findsNothing);
    expect(find.text('混合模式'), findsNothing);
    expect(find.text('弱网与离线兜底状态'), findsOneWidget);
  });

  testWidgets('在线配置页可切换协议、选择 MiMo 官方预置音色并校验密钥提示',
      (WidgetTester tester) async {
    final engine = await _pumpApp(tester);

    await tester.tap(find.text('在线').last);
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('启用在线朗读', skipOffstage: false), findsOneWidget);
    expect(find.text('MiMo 默认音色', skipOffstage: false), findsOneWidget);

    await _bringIntoView(
      tester,
      find.text('冰糖 · 中文女声'),
      page: OnlineConfigPage,
      delta: 180,
    );
    await tester.tap(find.widgetWithText(ChoiceChip, '冰糖 · 中文女声'));
    await tester.pump(const Duration(milliseconds: 200));
    expect((await engine.config()).voice, '冰糖');
    await tester.pump(const Duration(seconds: 4));

    await _bringIntoView(
      tester,
      find.text('OpenAI 兼容'),
      page: OnlineConfigPage,
      delta: -180,
    );
    await tester.tap(find.text('OpenAI 兼容'));
    await tester.pump(const Duration(milliseconds: 200));
    await _bringIntoView(
      tester,
      find.text('OpenAI 兼容参数'),
      page: OnlineConfigPage,
      delta: 180,
    );
    expect(find.text('OpenAI 兼容参数'), findsOneWidget);
    await tester.pump(const Duration(seconds: 4));
  });

  testWidgets('设置页可调节弱网首包超时阈值与系统 TTS 兜底引擎',
      (WidgetTester tester) async {
    final engine = await _pumpApp(tester);

    await tester.tap(find.text('设置').last);
    await tester.pump(const Duration(milliseconds: 200));

    expect(find.text('本地推理性能', skipOffstage: false), findsNothing);
    await _bringIntoView(
      tester,
      find.text('弱网回退与系统 TTS 兜底'),
      page: SettingsPage,
      delta: 180,
    );
    expect(find.text('6 秒超时'), findsOneWidget);

    await _bringIntoView(
      tester,
      find.text('8 秒'),
      page: SettingsPage,
      delta: 180,
    );
    await tester.tap(find.widgetWithText(ChoiceChip, '8 秒'));
    await tester.pump(const Duration(milliseconds: 200));
    expect((await engine.config()).timeoutMs, 8000);
    expect(find.text('8 秒超时'), findsOneWidget);
    await tester.pump(const Duration(seconds: 4));

    await _bringIntoView(
      tester,
      find.text('小爱语音引擎'),
      page: SettingsPage,
      delta: 180,
    );
    await tester.tap(find.text('小爱语音引擎'));
    await tester.pump(const Duration(milliseconds: 200));
    expect((await engine.config()).fallbackTtsEngine, 'com.xiaomi.mibrain.speech');

    await tester.scrollUntilVisible(
      find.text('允许使用移动数据流量'),
      200,
      scrollable: _pageScrollable(SettingsPage),
    );
    expect((await engine.config()).allowMobileData, isFalse);
    await tester.pump(const Duration(seconds: 4));
  });

  testWidgets('朗读页与诊断页可完整渲染并执行试听与系统 TTS 兜底记录',
      (WidgetTester tester) async {
    final engine = await _pumpApp(tester);

    await tester.tap(find.text('朗读').last);
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('弱网兜底'), findsOneWidget);
    expect(find.text('开始朗读'), findsOneWidget);

    await _bringIntoView(
      tester,
      find.text('开始朗读'),
      page: SamplePage,
      delta: 180,
    );
    await tester.tap(find.text('开始朗读'));
    await tester.pump(const Duration(milliseconds: 100));
    await tester.pump(const Duration(seconds: 1));
    final st = await engine.status();
    expect(st.lastFallbackReason, 'online_disabled');

    await tester.tap(find.byIcon(Icons.monitor_heart_outlined));
    await tester.pumpAndSettle();
    expect(find.byType(DiagnosticsPage), findsOneWidget);
    await _bringIntoView(
      tester,
      find.text('系统 TTS 兜底'),
      page: DiagnosticsPage,
      delta: 180,
    );
    expect(find.text('系统 TTS 兜底'), findsOneWidget);
  });
}
