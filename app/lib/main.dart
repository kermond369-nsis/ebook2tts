import 'package:flutter/material.dart';
import 'pages/mode_setup_page.dart';
import 'platform/system_bridge.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'engine/engine_service.dart';
import 'engine/mock_engine_service.dart';
import 'engine/pigeon_engine_service.dart';
import 'shell/app_shell.dart';
import 'state/providers.dart';
import 'theme/tokens.dart';

/// 应用入口。
///
/// 默认（[kEngineUseMock] = false）：注入 [PigeonEngineService] 真机桥接——
/// 宿主插件由 `MainActivity.configureFlutterEngine` 注册，事件经
/// `EngineEventApi` 回推；页面代码不动（规格 §1）。
/// 置 [kEngineUseMock] = true 可一键回退到 [MockEngineService]（演示数据，内存态）。
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final EngineService engine;
  if (kEngineUseMock) {
    engine = await MockEngineService.create();
  } else {
    engine = PigeonEngineService.create();
  }
  runApp(
    ProviderScope(
      overrides: [engineServiceProvider.overrideWithValue(engine)],
      child: const Ebook2TtsApp(),
    ),
  );
}

class Ebook2TtsApp extends StatelessWidget {
  const Ebook2TtsApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '书声本地',
      debugShowCheckedModeBanner: false,
      theme: Tokens.theme(),
      home: const _StartGate(),
    );
  }
}

/// 首启闸门（RQ-513）：未选择过朗读模式 ⇒ 先走「开局模式选择」；已选择 ⇒ 直接进主界面。
///
/// 桥不可用（异常/旧版本）⇒ 视为"已选择、保持默认"，**不打断**用户。
class _StartGate extends StatefulWidget {
  const _StartGate();

  @override
  State<_StartGate> createState() => _StartGateState();
}

class _StartGateState extends State<_StartGate> {
  RouteModeState? _mode;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final v = await SystemBridge.getRouteMode();
    if (!mounted) return;
    setState(() => _mode = v ?? const RouteModeState(mode: 'prefer_online', chosen: true));
  }

  @override
  Widget build(BuildContext context) {
    final st = _mode;
    if (st == null) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (!st.chosen) {
      return ModeSetupPage(onDone: _load);
    }
    return const AppShell();
  }
}
