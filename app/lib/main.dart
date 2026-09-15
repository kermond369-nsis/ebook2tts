import 'package:flutter/material.dart';
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
      home: const AppShell(),
    );
  }
}
