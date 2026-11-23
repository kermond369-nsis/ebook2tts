import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'engine/engine_service.dart';
import 'engine/mock_engine_service.dart';
import 'engine/pigeon_engine_service.dart';
import 'pages/mode_setup_page.dart';
import 'shell/app_shell.dart';
import 'state/providers.dart';
import 'theme/tokens.dart';

/// 应用入口。
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
      title: '书声',
      debugShowCheckedModeBanner: false,
      theme: Tokens.theme(),
      home: const _StartGate(),
    );
  }
}

/// 首启闸门（RQ-513）：未选择过工作模式 ⇒ 先进入三模式引导页；已选择 ⇒ 直接进入对应布局主壳。
class _StartGate extends ConsumerWidget {
  const _StartGate();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final routeAsync = ref.watch(routeModeProvider);
    final st = routeAsync.value;
    if (st == null) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (!st.chosen) {
      return ModeSetupPage(
        onDone: () => ref.read(routeModeProvider.notifier).refresh(),
      );
    }
    return const AppShell();
  }
}
