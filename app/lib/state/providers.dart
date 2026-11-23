import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../engine/mock_engine_service.dart';
import '../engine/pigeon_engine_service.dart';
import '../platform/system_bridge.dart';

/// 引擎后端开关（规格 §1：仅此一处切换，页面代码不感知）。
const bool kEngineUseMock = false;

/// 应用顶级工作模式：纯在线 / 纯本地 / 混合双引擎。
enum AppWorkMode { onlineOnly, localOnly, hybrid }

extension RouteModeUi on RouteModeState {
  AppWorkMode get workMode => switch (mode) {
        'only_online' => AppWorkMode.onlineOnly,
        'only_local' => AppWorkMode.localOnly,
        _ => AppWorkMode.hybrid,
      };

  bool get allowsOnline => mode != 'only_local';
  bool get allowsLocal => mode != 'only_online';
  bool get prefersOnline => mode == 'prefer_online' || mode == 'only_online';
}

/// 语义化页面跳转目标（不依赖各模式下底部 Tab 的硬编码序号）。
enum AppDestination { home, online, localModels, localVoices, sample, settings }

/// 引擎服务入口。
final engineServiceProvider = Provider<EngineService>((ref) {
  return kEngineUseMock
      ? MockEngineService.instance()
      : PigeonEngineService.instance();
});

/// 引擎事件「滴答」：任意引擎事件自增一次，驱动下游数据刷新。
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

  void go(int index) => state = index;

  /// 按语义页切换（自动适配 纯在线 / 纯本地 / 混合 各自 Tab 布局）。
  void goDestination(AppDestination dest, AppWorkMode mode) {
    switch (mode) {
      case AppWorkMode.onlineOnly:
        state = switch (dest) {
          AppDestination.home => 0,
          AppDestination.online => 1,
          AppDestination.sample => 2,
          AppDestination.settings => 3,
          AppDestination.localModels || AppDestination.localVoices => 0,
        };
      case AppWorkMode.localOnly:
        state = switch (dest) {
          AppDestination.home => 0,
          AppDestination.localModels => 1,
          AppDestination.localVoices => 2,
          AppDestination.sample => 3,
          AppDestination.settings => 4,
          AppDestination.online => 0,
        };
      case AppWorkMode.hybrid:
        if (dest == AppDestination.localModels) {
          ref.read(localHubTabProvider.notifier).select(0);
        } else if (dest == AppDestination.localVoices) {
          ref.read(localHubTabProvider.notifier).select(1);
        }
        state = switch (dest) {
          AppDestination.home => 0,
          AppDestination.online => 1,
          AppDestination.localModels || AppDestination.localVoices => 2,
          AppDestination.sample => 3,
          AppDestination.settings => 4,
        };
    }
  }
}

/// 混合模式「本地」页内二级分段（0 = 模型管理 / 1 = 音色库）。
final localHubTabProvider =
    NotifierProvider<LocalHubTabNotifier, int>(LocalHubTabNotifier.new);

class LocalHubTabNotifier extends Notifier<int> {
  @override
  int build() => 0;

  void select(int tab) => state = tab.clamp(0, 1);
}

/// 路由模式状态（唯一界面数据源）。
final routeModeProvider =
    AsyncNotifierProvider<RouteModeNotifier, RouteModeState>(
  RouteModeNotifier.new,
);

class RouteModeNotifier extends AsyncNotifier<RouteModeState> {
  @override
  Future<RouteModeState> build() async {
    final v = await SystemBridge.getRouteMode();
    return v ?? const RouteModeState(mode: 'prefer_online', chosen: true);
  }

  Future<String?> setMode(String modeId) async {
    final saved = await SystemBridge.setRouteMode(modeId);
    final effective = saved ?? modeId;
    state = AsyncData(RouteModeState(mode: effective, chosen: true));
    ref.invalidate(statusProvider);
    ref.invalidate(runtimeMetaProvider);
    return saved;
  }

  Future<void> refresh() async {
    final v = await SystemBridge.getRouteMode();
    state = AsyncData(
      v ?? const RouteModeState(mode: 'prefer_online', chosen: true),
    );
    ref.invalidate(statusProvider);
    ref.invalidate(runtimeMetaProvider);
  }
}

/// 通用在线 TTS 引擎配置（`mimo` / `openai` / `custom_http`）。
final onlineProviderConfigProvider =
    AsyncNotifierProvider<OnlineProviderConfigNotifier, OnlineProviderConfig>(
  OnlineProviderConfigNotifier.new,
);

class OnlineProviderConfigNotifier extends AsyncNotifier<OnlineProviderConfig> {
  @override
  Future<OnlineProviderConfig> build() async {
    final v = await SystemBridge.getOnlineProviderConfig();
    return v ?? const OnlineProviderConfig();
  }

  Future<bool> save(OnlineProviderConfig cfg) async {
    final ok = await SystemBridge.setOnlineProviderConfig(cfg);
    state = AsyncData(cfg);
    ref.invalidate(statusProvider);
    ref.invalidate(runtimeMetaProvider);
    return ok;
  }

  Future<String?> probe({String? apiKey}) async {
    final err = await SystemBridge.validateOnlineProvider(apiKey: apiKey);
    ref.invalidate(statusProvider);
    ref.invalidate(runtimeMetaProvider);
    return err;
  }
}

/// 引擎总体状态。
final statusProvider = FutureProvider<EngineStatus>((ref) {
  ref.watch(engineTicksProvider);
  return ref.watch(engineServiceProvider).status();
});

/// 运行时隔离错误与持久化性能摘要。
final runtimeMetaProvider = FutureProvider<RuntimeMeta>((ref) {
  ref.watch(engineTicksProvider);
  ref.watch(routeModeProvider);
  return SystemBridge.getRuntimeMeta();
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
    bool? roleVoiceEnabled,
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
      roleVoiceEnabled: roleVoiceEnabled,
    );
    state = AsyncData(await engine.config());
    ref.invalidate(statusProvider);
    ref.invalidate(runtimeMetaProvider);
  }

  /// 校验密钥：返回 null 表示通过，否则为脱敏错误摘要。
  Future<String?> validateKey({
    required String keyKind,
    required String apiKey,
  }) async {
    final err = await ref
        .read(engineServiceProvider)
        .validateKey(keyKind: keyKind, apiKey: apiKey);
    ref.invalidate(statusProvider);
    ref.invalidate(runtimeMetaProvider);
    return err;
  }
}

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
  const PreviewState({
    required this.playing,
    this.voiceId,
    this.lastError,
  });

  final bool playing;
  final String? voiceId;
  final String? lastError;
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
      final err = event.data['previewError'] as String?;
      if (playing is bool || err != null) {
        state = PreviewState(
          playing: playing is bool ? playing : false,
          voiceId: (playing == true)
              ? (event.data['previewVoiceId'] as String? ?? state.voiceId)
              : null,
          lastError: err ?? (playing == true ? null : state.lastError),
        );
      }
    });
    ref.onDispose(sub.cancel);
    return const PreviewState(playing: false);
  }

  void clearError() {
    if (state.lastError != null) {
      state = PreviewState(playing: state.playing, voiceId: state.voiceId);
    }
  }

  Future<void> preview({required String text, required String voiceId}) {
    clearError();
    return ref
        .read(engineServiceProvider)
        .preview(text: text, voiceId: voiceId);
  }

  Future<void> stop() => ref.read(engineServiceProvider).stopPreview();
}

/// 诊断数据（引擎状态 + 最近一次性能 + 日志 + 运行时隔离错误）。
class DiagnosticsData {
  const DiagnosticsData({
    required this.status,
    required this.perf,
    required this.logs,
    required this.meta,
  });

  final EngineStatus status;
  final Map<String, dynamic> perf;
  final List<String> logs;
  final RuntimeMeta meta;
}

final diagnosticsProvider = FutureProvider<DiagnosticsData>((ref) async {
  ref.watch(engineTicksProvider);
  final engine = ref.watch(engineServiceProvider);
  final status = await engine.status();
  final logs = await engine.recentLogs();
  final meta = await ref.watch(runtimeMetaProvider.future);
  final eventPerf = ref.watch(lastPerfProvider);
  final mergedPerf = eventPerf.isNotEmpty ? eventPerf : meta.lastPerf;
  return DiagnosticsData(
    status: status,
    perf: mergedPerf,
    logs: logs,
    meta: meta,
  );
});
