import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../engine/method_channel_engine_service.dart';
import '../engine/mock_engine_service.dart';

/// 引擎后端开关（默认 false，真机走 MethodChannel）。
const bool kEngineUseMock = false;

/// 固定四页导航目标。
enum AppDestination { home, online, sample, settings }

final engineServiceProvider = Provider<EngineService>((ref) {
  return kEngineUseMock
      ? MockEngineService.instance()
      : MethodChannelEngineService.create();
});

/// 引擎事件滴答：驱动状态、配置与诊断数据自动刷新。
final engineTicksProvider =
    NotifierProvider<EngineTicksNotifier, int>(EngineTicksNotifier.new);

class EngineTicksNotifier extends Notifier<int> {
  @override
  int build() {
    final engine = ref.watch(engineServiceProvider);
    final sub = engine.events().listen((_) {
      state = state + 1;
    });
    ref.onDispose(sub.cancel);
    return 0;
  }
}

/// 当前底部导航下标。
final navIndexProvider =
    NotifierProvider<NavIndexNotifier, int>(NavIndexNotifier.new);

class NavIndexNotifier extends Notifier<int> {
  @override
  int build() => 0;

  void go(int index) => state = index.clamp(0, 3);

  void goDestination(AppDestination dest) {
    state = switch (dest) {
      AppDestination.home => 0,
      AppDestination.online => 1,
      AppDestination.sample => 2,
      AppDestination.settings => 3,
    };
  }
}

/// 引擎状态。
final statusProvider = FutureProvider<EngineStatus>((ref) {
  ref.watch(engineTicksProvider);
  return ref.watch(engineServiceProvider).status();
});

/// 应用配置。
final configProvider =
    AsyncNotifierProvider<ConfigNotifier, AppConfig>(ConfigNotifier.new);

class ConfigNotifier extends AsyncNotifier<AppConfig> {
  @override
  Future<AppConfig> build() async {
    ref.watch(engineTicksProvider);
    return ref.read(engineServiceProvider).config();
  }

  Future<void> save({
    double? speed,
    bool? onlineEnabled,
    bool? allowMobileData,
    String? keyKind,
    String? apiKey,
    String? baseUrl,
    bool? tokenPlanAccepted,
    bool? roleVoiceEnabled,
    String? providerKind,
    String? model,
    String? voice,
    String? responseFormat,
    int? sampleRate,
    String? customHeaders,
    String? customBodyTemplate,
    int? timeoutMs,
    String? fallbackTtsEngine,
  }) async {
    final engine = ref.read(engineServiceProvider);
    await engine.updateConfig(
      speed: speed,
      onlineEnabled: onlineEnabled,
      allowMobileData: allowMobileData,
      keyKind: keyKind,
      apiKey: apiKey,
      baseUrl: baseUrl,
      tokenPlanAccepted: tokenPlanAccepted,
      roleVoiceEnabled: roleVoiceEnabled,
      providerKind: providerKind,
      model: model,
      voice: voice,
      responseFormat: responseFormat,
      sampleRate: sampleRate,
      customHeaders: customHeaders,
      customBodyTemplate: customBodyTemplate,
      timeoutMs: timeoutMs,
      fallbackTtsEngine: fallbackTtsEngine,
    );
    state = AsyncData(await engine.config());
    ref.invalidate(statusProvider);
    ref.invalidate(diagnosticsProvider);
  }

  Future<String?> validateOnline({String? keyKind, String? apiKey}) async {
    final err = await ref
        .read(engineServiceProvider)
        .validateOnline(keyKind: keyKind, apiKey: apiKey);
    ref.invalidate(statusProvider);
    ref.invalidate(diagnosticsProvider);
    return err;
  }
}

/// 设备可用系统自带 / 第三方 TTS 引擎列表（排除自身）。
final systemTtsEnginesProvider =
    FutureProvider<List<SystemTtsEngine>>((ref) {
  return ref.watch(engineServiceProvider).systemTtsEngines();
});

/// 最近一次性能数据。
final lastPerfProvider =
    NotifierProvider<LastPerfNotifier, Map<String, dynamic>>(
  LastPerfNotifier.new,
);

class LastPerfNotifier extends Notifier<Map<String, dynamic>> {
  @override
  Map<String, dynamic> build() {
    final engine = ref.watch(engineServiceProvider);
    final sub = engine.events().listen((event) {
      if (event.type != 'status') return;
      final perf = event.data['perf'];
      if (perf is Map) {
        state = Map<String, dynamic>.from(perf);
      }
    });
    ref.onDispose(sub.cancel);
    return <String, dynamic>{};
  }
}

/// 试听状态。
class PreviewState {
  const PreviewState({
    required this.playing,
    this.voiceId,
    this.stage,
    this.lastError,
  });

  final bool playing;
  final String? voiceId;
  final String? stage;
  final String? lastError;
}

final previewProvider =
    NotifierProvider<PreviewNotifier, PreviewState>(PreviewNotifier.new);

class PreviewNotifier extends Notifier<PreviewState> {
  @override
  PreviewState build() {
    final engine = ref.watch(engineServiceProvider);
    final sub = engine.events().listen((event) {
      if (event.type != 'status') return;
      final playing = event.data['previewing'];
      final stage = event.data['previewStage'] as String?;
      final err = event.data['previewError'] as String?;
      if (playing is bool || stage != null || err != null) {
        final nextPlaying = playing is bool ? playing : state.playing;
        state = PreviewState(
          playing: nextPlaying,
          voiceId: nextPlaying
              ? (event.data['previewVoiceId'] as String? ?? state.voiceId)
              : null,
          stage: nextPlaying ? (stage ?? state.stage) : null,
          lastError: err ?? (nextPlaying ? null : state.lastError),
        );
      }
    });
    ref.onDispose(sub.cancel);
    return const PreviewState(playing: false);
  }

  void clearError() {
    if (state.lastError != null) {
      state = PreviewState(
        playing: state.playing,
        voiceId: state.voiceId,
        stage: state.stage,
      );
    }
  }

  Future<void> preview({required String text, String voiceId = ''}) {
    clearError();
    return ref
        .read(engineServiceProvider)
        .preview(text: text, voiceId: voiceId);
  }

  Future<void> stop() => ref.read(engineServiceProvider).stopPreview();
}

/// 诊断页面聚合数据。
class DiagnosticsData {
  const DiagnosticsData({
    required this.status,
    required this.config,
    required this.perf,
    required this.logs,
  });

  final EngineStatus status;
  final AppConfig config;
  final Map<String, dynamic> perf;
  final List<String> logs;
}

final diagnosticsProvider = FutureProvider<DiagnosticsData>((ref) async {
  ref.watch(engineTicksProvider);
  final engine = ref.watch(engineServiceProvider);
  final status = await engine.status();
  final config = await engine.config();
  final logs = await engine.recentLogs();
  final perf = ref.watch(lastPerfProvider);
  return DiagnosticsData(
    status: status,
    config: config,
    perf: perf,
    logs: logs,
  );
});
