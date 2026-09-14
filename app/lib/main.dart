import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'engine/mock_engine_service.dart';
import 'shell/app_shell.dart';
import 'state/providers.dart';
import 'theme/tokens.dart';

/// 应用入口。
///
/// Mock 阶段：先把本机配置读入 [MockEngineService]，再经 Riverpod 注入全树；
/// 实施线替换为 Pigeon 真机实现时，只需改这里的注入对象，页面代码不动（规格 §1）。
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final prefs = await SharedPreferences.getInstance();
  final engine = await MockEngineService.create(prefs);
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
