// 首启三模式选择与本地合成最低要求告知契约测试（RQ-513 / RQ-515）。
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:ebook2tts/pages/mode_setup_page.dart';

void main() {
  const channel = MethodChannel('com.kermond.ebook2tts/system');
  String? savedMode;

  setUp(() {
    savedMode = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      switch (call.method) {
        case 'setRouteMode':
          savedMode = (call.arguments as Map?)?['mode'] as String?;
          return savedMode ?? 'prefer_online';
        case 'getRouteMode':
          return <String, Object>{'mode': 'prefer_online', 'chosen': true};
        default:
          return null;
      }
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('纯本地模式 ⇒ 无条件弹「本地合成最低要求」告知（无判定、无勾选）',
      (WidgetTester tester) async {
    await tester.pumpWidget(MaterialApp(
      home: ModeSetupPage(onDone: () async {}),
    ));
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.text('纯在线模式'), findsOneWidget);
    expect(find.text('纯本地模式'), findsOneWidget);
    expect(find.text('混合模式'), findsOneWidget);

    await tester.tap(find.text('纯本地模式'));
    await tester.pumpAndSettle();

    expect(find.text('本地合成最低要求'), findsOneWidget);
    expect(find.textContaining('骁龙 8 Gen 1'), findsOneWidget);
    expect(find.textContaining('天玑 9300'), findsOneWidget);

    expect(find.byType(Checkbox), findsNothing);
    expect(find.textContaining('不再提示'), findsNothing);

    await tester.tap(find.text('我知道了'));
    await tester.pumpAndSettle();
    expect(find.text('本地合成最低要求'), findsNothing);

    await tester.tap(find.text('进入应用'));
    await tester.pumpAndSettle();
    expect(savedMode, 'only_local');
  });

  testWidgets('纯在线与混合模式可直接选中并保存对应 route.mode', (WidgetTester tester) async {
    await tester.pumpWidget(MaterialApp(
      home: ModeSetupPage(onDone: () async {}),
    ));
    await tester.pump();

    await tester.tap(find.text('混合模式'));
    await tester.pumpAndSettle();
    expect(find.text('本地优先（不可用走在线）'), findsOneWidget);

    await tester.tap(find.text('本地优先（不可用走在线）'));
    await tester.pump();
    await tester.tap(find.text('进入应用'));
    await tester.pumpAndSettle();
    expect(savedMode, 'prefer_local');
  });
}
