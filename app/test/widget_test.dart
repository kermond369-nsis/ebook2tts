// 重构后 UI 烟雾测试：验证「纯在线 / 纯本地 / 混合」三模式下的严格界面隔离与安全默认值。
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:ebook2tts/engine/mock_engine_service.dart';
import 'package:ebook2tts/main.dart';
import 'package:ebook2tts/state/providers.dart';

const MethodChannel _systemChannel = MethodChannel('com.kermond.ebook2tts/system');

void _mockSystem({required String mode, String onlineLastError = ''}) {
  var storedThreads = 0;
  var provider = 'cpu';
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(_systemChannel, (MethodCall call) async {
    switch (call.method) {
      case 'getRouteMode':
        return <String, Object>{'mode': mode, 'chosen': true};
      case 'setRouteMode':
        final args = call.arguments as Map<Object?, Object?>?;
        return (args?['mode'] as String?) ?? mode;
      case 'getOnlineProviderConfig':
        return <String, Object>{
          'providerKind': 'mimo',
          'baseUrl': '',
          'model': '',
          'voice': '',
          'responseFormat': 'pcm',
          'sampleRate': 24000,
          'customHeaders': '',
          'customBodyTemplate': '',
        };
      case 'getRuntimeMeta':
        return <String, Object>{
          'onlineLastError': onlineLastError,
          'localLastError': mode == 'only_online' ? '模型未安装' : '',
          'lastPerf': <String, Object>{},
          'providerKind': 'mimo',
        };
      case 'getMirrorConfig':
        return <String, Object>{
          'selected': 'auto',
          'customUrl': '',
          'presets': <Map<String, Object>>[
            {'id': 'auto', 'label': '自动测速选最快（推荐）', 'prefix': ''},
            {
              'id': 'https://gh-proxy.com',
              'label': 'gh-proxy.com（国内加速）',
              'prefix': 'https://gh-proxy.com',
            },
            {'id': 'direct', 'label': 'GitHub 官方直连', 'prefix': ''},
            {'id': 'custom', 'label': '自定义镜像 / 直链', 'prefix': ''},
          ],
        };
      case 'setMirrorConfig':
        return 'ok';
      case 'getInferenceConfig':
        return <String, Object>{
          'storedThreads': storedThreads,
          'effectiveThreads': storedThreads == 0 ? 2 : storedThreads,
          'cpuCores': 8,
          'provider': provider,
        };
      case 'setInferenceConfig':
        final args = call.arguments as Map<Object?, Object?>?;
        if (args?['threads'] is int) {
          storedThreads = args!['threads'] as int;
        }
        return 'ok';
      case 'probeMirrors':
        return <Map<String, Object>>[
          {
            'id': 'https://gh-proxy.com',
            'label': 'gh-proxy.com（国内加速）',
            'ok': true,
            'latencyMs': 120,
            'speedKbps': 512.0,
            'httpCode': 206,
          },
        ];
      default:
        return null;
    }
  });
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
  await tester.pump(const Duration(milliseconds: 400));
  expect(find.byType(CircularProgressIndicator), findsNothing,
      reason: '首启闸门未解开：通道应答缺失或 status() 未落地');
  return engine;
}

void main() {
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_systemChannel, null);
  });

  testWidgets('纯在线模式：仅显示首页/在线/朗读/设置，隐藏本地模型与音色，且不被本地无模型误报错误',
      (WidgetTester tester) async {
    _mockSystem(mode: 'only_online');
    await _pumpApp(tester);

    expect(find.text('书声'), findsWidgets);
    expect(find.text('纯在线'), findsWidgets);
    expect(find.text('在线'), findsOneWidget);
    expect(find.text('朗读'), findsOneWidget);
    expect(find.text('设置'), findsOneWidget);
    expect(find.text('模型'), findsNothing);
    expect(find.text('音色'), findsNothing);

    await tester.tap(find.text('在线').last);
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('启用在线朗读', skipOffstage: false), findsOneWidget);
    expect(find.text('在线语音引擎', skipOffstage: false), findsOneWidget);
    const billing = '按量计费 API Key（推荐）';
    await tester.scrollUntilVisible(
      find.text(billing),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text(billing), findsOneWidget);
    expect(find.textContaining('平台方拒绝了当前密钥'), findsNothing);
    await tester.tap(find.text('设置').last);
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('本地推理性能', skipOffstage: false), findsNothing);
  });

  testWidgets('纯本地模式：仅显示本地模型与音色，彻底隐藏在线配置', (WidgetTester tester) async {
    _mockSystem(mode: 'only_local');
    await _pumpApp(tester);

    expect(find.text('纯本地'), findsWidgets);
    expect(find.text('模型'), findsOneWidget);
    expect(find.text('音色'), findsOneWidget);
    expect(find.text('在线'), findsNothing);

    await tester.tap(find.text('模型').last);
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('端侧语音模型', skipOffstage: false), findsOneWidget);
    expect(find.text('下载源与镜像测速', skipOffstage: false), findsOneWidget);
    expect(find.text('一键测速', skipOffstage: false), findsOneWidget);
    expect(find.text('从本地导入', skipOffstage: false), findsWidgets);

    await tester.tap(find.text('音色').last);
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('当前旁白', skipOffstage: false), findsWidgets);

    await tester.tap(find.text('设置').last);
    await tester.pump(const Duration(milliseconds: 200));
    const mobileToggle = '允许使用移动数据流量';
    await tester.scrollUntilVisible(
      find.text(mobileToggle),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text(mobileToggle), findsOneWidget);
    expect(find.text('启用在线朗读', skipOffstage: false), findsNothing);
  });

  testWidgets('混合模式：同时提供在线与本地入口，且移动数据默认关闭', (WidgetTester tester) async {
    _mockSystem(mode: 'prefer_online');
    await _pumpApp(tester);

    expect(find.text('混合'), findsWidgets);
    expect(find.text('在线'), findsOneWidget);
    expect(find.text('本地'), findsOneWidget);

    await tester.tap(find.text('本地').last);
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('模型管理'), findsOneWidget);
    expect(find.text('音色库'), findsOneWidget);

    await tester.tap(find.text('设置').last);
    await tester.pump(const Duration(milliseconds: 200));
    const wifiOff = '已关闭（默认）：仅在无线网络（Wi-Fi）下执行在线合成与模型下载。';
    await tester.scrollUntilVisible(
      find.text(wifiOff),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text(wifiOff), findsOneWidget);
  });

  testWidgets('纯本地模式：切换模型按钮文字完整且点击后即时生效，并可调节 CPU 推理线程',
      (WidgetTester tester) async {
    _mockSystem(mode: 'only_local');
    final engine = await _pumpApp(tester);

    await engine.startDownload('matcha-zh-en');
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(seconds: 1));
    }
    await engine.startDownload('vits-zh-ll');
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(seconds: 1));
    }

    await tester.tap(find.text('模型').last);
    await tester.pump(const Duration(milliseconds: 200));

    await tester.scrollUntilVisible(
      find.text('启用此模型').hitTestable(),
      180,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.pump();
    expect(find.text('启用此模型'), findsOneWidget);
    await tester.tap(find.text('启用此模型').hitTestable());
    await tester.pump(const Duration(milliseconds: 250));
    await tester.pump(const Duration(seconds: 4));

    final models = await engine.models();
    expect(models.firstWhere((m) => m.id == 'matcha-zh-en').active, isTrue);
    expect(models.firstWhere((m) => m.id == 'vits-zh-ll').active, isFalse);
    expect(find.text('正在使用该模型', skipOffstage: false), findsOneWidget);
    expect(find.text('启用此模型', skipOffstage: false), findsOneWidget);

    await tester.tap(find.text('设置').last);
    await tester.pump(const Duration(milliseconds: 200));
    await tester.scrollUntilVisible(
      find.text('允许使用移动数据流量'),
      250,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.pump();
    expect(find.text('本地推理性能'), findsOneWidget);
    expect(find.text('2 线程 · CPU'), findsOneWidget);

    await tester.tap(find.text('4 线程'));
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('4 线程 · CPU'), findsOneWidget);
    await tester.pump(const Duration(seconds: 4));
  });
}
