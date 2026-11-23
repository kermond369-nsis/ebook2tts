import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'engine/engine_service.dart';
import 'engine/method_channel_engine_service.dart';
import 'engine/mock_engine_service.dart';
import 'shell/app_shell.dart';
import 'state/providers.dart';
import 'theme/tokens.dart';

/// 应用入口（单一模式：在线多引擎 + 系统自带 TTS 兜底）。
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final EngineService engine;
  if (kEngineUseMock) {
    engine = await MockEngineService.create();
  } else {
    engine = MethodChannelEngineService.create();
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
      title: '书声',
      debugShowCheckedModeBanner: false,
      theme: Tokens.theme(),
      home: const AppShell(),
    );
  }
}
