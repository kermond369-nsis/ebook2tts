import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../engine/mock_engine_service.dart';
import '../engine/pigeon_engine_service.dart';

/// 引擎后端开关（规格 §1：仅此一处切换，页面代码不感知）。
///
/// - `false`（默认）= Pigeon 真机桥接（[PigeonEngineService]，真机引擎）；
/// - `true` = 回退 [MockEngineService]（演示数据；`main()` 会先完成其异步初始化）。
const bool kEngineUseMock = false;

/// 引擎服务入口。
///
/// 已由 Mock 切换为 Pigeon 真机实现；签名与下游页面保持不变（规格 §1）。
final engineServiceProvider = Provider<EngineService>((ref) {
  return kEngineUseMock
      ? MockEngineService.instance()
      : PigeonEngineService.instance();
});

/// 引擎事件「滴答」：任意引擎事件自增一次，用于驱动各数据提供者刷新。
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

/// 底部导航当前页（0 引导 / 1 模型 / 2 音色 / 3 朗读 / 4 设置 / 5 诊断）。
final navIndexProvider =
    NotifierProvider<NavIndexNotifier, int>(NavIndexNotifier.new);

class NavIndexNotifier extends Notifier<int> {
  @override
  int build() => 0;

  void go(int index) => state = index;
}

/// 引擎总体状态。
final statusProvider = FutureProvider<EngineStatus>((ref) {
  ref.watch(engineTicksProvider);
  return ref.watch(engineServiceProvider).status();
});

/// 模型清单。
final modelsProvider = FutureProvider<List<ModelInfo>>((ref) {
  ref.watch(engineTicksProvider);
  return ref.watch(engineServiceProvider).models();
});

/// 当前下载进度（null 表示没有进行中的下载）。
final downloadProgressProvider =
    NotifierProvider<DownloadProgressNotifier, DownloadProgress?>(
  DownloadProgressNotifier.new,
);

class DownloadProgressNotifier extends Notifier<DownloadProgress?> {
  @override
  DownloadProgress? build() {
    final engine = ref.watch(engineServiceProvider);
    final sub = engine.events().listen((event) {
      if (event.type != 'progress') {
        return;
      }
      final data = event.data;
      final phase = data['phase'] as String? ?? 'idle';
      if (phase == 'idle' || phase == 'done' || phase == 'error') {
        state = null;
        return;
      }
      state = DownloadProgress(
        modelId: data['modelId'] as String? ?? '',
        received: (data['received'] as num?)?.toInt() ?? 0,
        total: (data['total'] as num?)?.toInt() ?? 0,
        phase: phase,
      );
    });
    ref.onDispose(sub.cancel);
    return null;
  }
}

/// 音色清单（含当前旁白标记）。
final voicesProvider = FutureProvider<List<VoiceInfo>>((ref) {
  ref.watch(engineTicksProvider);
  return ref.watch(engineServiceProvider).voices();
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

  /// 统一写入口（对应 `EngineService.updateConfig`）。
  Future<void> save({
    double? speed,
    bool? onlineEnabled,
    bool? allowMobileData,
    String? keyKind,
    String? apiKey,
    String? baseUrl,
    String? customMirror,
    bool? tokenPlanAccepted,
  }) async {
    final engine = ref.read(engineServiceProvider);
    await engine.updateConfig(
      speed: speed,
      onlineEnabled: onlineEnabled,
      allowMobileData: allowMobileData,
      keyKind: keyKind,
      apiKey: apiKey,
      baseUrl: baseUrl,
      customMirror: customMirror,
      tokenPlanAccepted: tokenPlanAccepted,
    );
    state = AsyncData(await engine.config());
  }

  /// 校验密钥：返回 null 表示通过，否则为平台方原始错误摘要。
  Future<String?> validateKey({
    required String keyKind,
    required String apiKey,
  }) {
    return ref
        .read(engineServiceProvider)
        .validateKey(keyKind: keyKind, apiKey: apiKey);
  }
}

/// 最近一次性能数据（由引擎 status 事件承载；空 Map 表示暂无数据）。
final lastPerfProvider =
    NotifierProvider<LastPerfNotifier, Map<String, dynamic>>(
  LastPerfNotifier.new,
);

class LastPerfNotifier extends Notifier<Map<String, dynamic>> {
  @override
  Map<String, dynamic> build() {
    final engine = ref.watch(engineServiceProvider);
    final sub = engine.events().listen((event) {
      if (event.type != 'status') {
        return;
      }
      final perf = event.data['perf'];
      if (perf is Map) {
        state = Map<String, dynamic>.from(perf);
      }
    });
    ref.onDispose(sub.cancel);
    return <String, dynamic>{};
  }
}

/// 试听播放状态（试听不改变全局旁白）。
class PreviewState {
  const PreviewState({required this.playing, this.voiceId});

  final bool playing;
  final String? voiceId;
}

final previewProvider =
    NotifierProvider<PreviewNotifier, PreviewState>(PreviewNotifier.new);

class PreviewNotifier extends Notifier<PreviewState> {
  @override
  PreviewState build() {
    final engine = ref.watch(engineServiceProvider);
    final sub = engine.events().listen((event) {
      if (event.type != 'status') {
        return;
      }
      final playing = event.data['previewing'];
      if (playing is bool) {
        state = PreviewState(
          playing: playing,
          voiceId: event.data['previewVoiceId'] as String?,
        );
      }
    });
    ref.onDispose(sub.cancel);
    return const PreviewState(playing: false);
  }

  Future<void> preview({required String text, required String voiceId}) {
    return ref
        .read(engineServiceProvider)
        .preview(text: text, voiceId: voiceId);
  }

  Future<void> stop() => ref.read(engineServiceProvider).stopPreview();
}

/// 诊断数据（引擎状态 + 最近一次性能 + 日志）。
class DiagnosticsData {
  const DiagnosticsData({
    required this.status,
    required this.perf,
    required this.logs,
  });

  final EngineStatus status;
  final Map<String, dynamic> perf;
  final List<String> logs;
}

final diagnosticsProvider = FutureProvider<DiagnosticsData>((ref) async {
  ref.watch(engineTicksProvider);
  final engine = ref.watch(engineServiceProvider);
  final status = await engine.status();
  final logs = await engine.recentLogs();
  final perf = ref.watch(lastPerfProvider);
  return DiagnosticsData(status: status, perf: perf, logs: logs);
});
