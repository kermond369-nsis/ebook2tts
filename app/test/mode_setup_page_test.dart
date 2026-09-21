// RQ-515（2026-09-21 修订）契约测试：首启页「本地合成最低要求」无条件告知。
//
// 固化三条契约（对应《附件V》复核建议）：
//   ① 点击「本地模式」弹出含「骁龙 8 Gen 1」「天玑 9300」字样的告知；
//   ② 弹窗**无**「不再提示」勾选项；
//   ③ 确认后选中本地模式（卡片进入选中态）。
// 桥接以假 Handler 提供（避免真平台通道），仅覆盖本页所需方法。
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:ebook2tts/pages/mode_setup_page.dart';

void main() {
  const channel = MethodChannel('com.kermond.ebook2tts/system');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      switch (call.method) {
        case 'setRouteMode':
          return (call.arguments as Map?)?['mode'] ?? 'prefer_local';
        case 'getRouteMode':
          return <String, Object>{'mode': 'prefer_local', 'chosen': true};
        default:
          return null;
      }
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('选择本地模式 ⇒ 无条件弹「本地合成最低要求」告知（无判定、无勾选）',
      (WidgetTester tester) async {
    await tester.pumpWidget(MaterialApp(
      home: ModeSetupPage(onDone: () async {}),
    ));
    await tester.pump(const Duration(milliseconds: 100));

    // 点击「本地模式」卡片
    await tester.tap(find.text('本地模式'));
    await tester.pumpAndSettle();

    // ① 告知出现且含算力口径字样
    expect(find.text('本地合成最低要求'), findsOneWidget);
    expect(find.textContaining('骁龙 8 Gen 1'), findsOneWidget);
    expect(find.textContaining('天玑 9300'), findsOneWidget);

    // ② 无「不再提示」勾选
    expect(find.byType(Checkbox), findsNothing);
    expect(find.textContaining('不再提示'), findsNothing);

    // ③ 显式确认后关闭
    await tester.tap(find.text('我知道了'));
    await tester.pumpAndSettle();
    expect(find.text('本地合成最低要求'), findsNothing);
  });
}
