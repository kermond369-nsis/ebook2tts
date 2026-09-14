import 'dart:async';
import 'dart:convert';

import 'engine_bridge.g.dart';
import 'engine_service.dart';

/// [EngineService] 的 Pigeon 真机实现（ADR-012）：App 侧唯一协议到引擎的转发层。
///
/// 与 `MockEngineService` 完全对齐的语义（页面代码无需感知差异）：
/// - `events()`：订阅时先补发一份**当前状态快照**，随后为增量事件；
/// - 快照键（`type == 'status'` 的 `data`）：`state` / `modelId` / `sampleRate` /
///   `speakers` / `online` / `perf` / `previewing` / `previewVoiceId` / `narratorVoiceId`；
/// - 引擎经宿主 `EngineEventApi` 推来的事件中：带 `modelId` 的 `progress`（下载进度）
///   原样转发；`preview*` 阶段事件（试听生命周期）**不进下载进度通道**，而是折算为
///   试听状态 + 性能快照，避免污染「模型下载」进度条；
/// - 性能数据一律**实测**：`firstChunkMs` = 试听请求 → `preview_start` 毫秒差，
///   `totalMs` = 试听请求 → `preview_done` 毫秒差，`perfAt` = 记录时间；
///   丢块数桥接未跨进程上报，记为 0（引擎进程内计数）。
class PigeonEngineService implements EngineService {
  PigeonEngineService._(this._host) {
    // 注册 Flutter 侧事件接收端（宿主经 EngineEventApi.onEvent 回调进来）。
    EngineEventApi.setUp(_BridgeEventSink(this));
  }

  static PigeonEngineService? _instance;

  /// 由 `main()` 注入；宿主插件由 `MainActivity` 注册（Flutter v2 embedding）。
  static PigeonEngineService instance() {
    final it = _instance;
    if (it == null) {
      throw StateError(
          'PigeonEngineService 尚未初始化：请先调用 PigeonEngineService.create()');
    }
    return it;
  }

  /// 创建单例（不做异步初始化：host 侧状态都在引擎里）。
  static PigeonEngineService create({EngineHostApi? host}) {
    final service = PigeonEngineService._(host ?? EngineHostApi());
    _instance = service;
    return service;
  }

  final EngineHostApi _host;
  final StreamController<EngineEvent> _events =
      StreamController<EngineEvent>.broadcast();

  Map<String, dynamic> _perf = <String, dynamic>{};
  bool _previewing = false;
  String? _previewVoiceId;
  String? _narratorVoiceId;

  /// 试听请求（Flutter 发起）时间戳，用于实测首块/总耗时。
  int? _previewRequestedAt;
  int? _previewFirstChunkMs;

  /// 试听兜底看门狗：宿主异常时不至于把界面卡在「试听中」。
  Timer? _previewWatchdog;

  // ---------------------------------------------------------------- 协议转发

  @override
  Future<EngineStatus> status() async {
    final dto = await _host.status();
    return EngineStatus(
      state: _engineState(dto.state),
      modelId: dto.modelId,
      sampleRate: dto.sampleRate,
      speakers: dto.speakers,
      online: _onlineState(dto.online),
    );
  }

  @override
  Future<List<ModelInfo>> models() async {
    final list = await _host.models();
    return list
        .map((m) => ModelInfo(
              id: m.id,
              label: m.label,
              note: m.note,
              downloadBytes: m.downloadBytes,
              extractedBytes: m.extractedBytes,
              installed: m.installed,
              active: m.active,
              recommended: m.recommended,
            ))
        .toList();
  }

  @override
  Future<void> startDownload(String modelId) => _host.startDownload(modelId);

  @override
  Future<void> cancelDownload() => _host.cancelDownload();

  @override
  Future<void> deleteModel(String modelId) => _host.deleteModel(modelId);

  @override
  Future<void> setActiveModel(String modelId) => _host.setActiveModel(modelId);

  @override
  Future<List<VoiceInfo>> voices() async {
    final list = await _host.voices();
    for (final v in list) {
      if (v.isNarrator) {
        _narratorVoiceId = v.id;
      }
    }
    return list
        .map((v) => VoiceInfo(
              id: v.id,
              name: v.name,
              lang: v.lang,
              isNarrator: v.isNarrator,
            ))
        .toList();
  }

  @override
  Future<void> setNarratorVoice(String voiceId) async {
    await _host.setNarratorVoice(voiceId);
    _narratorVoiceId = voiceId;
    _emitStatusSnapshot();
  }

  @override
  Future<void> preview({required String text, required String voiceId}) async {
    _previewRequestedAt = DateTime.now().millisecondsSinceEpoch;
    _previewFirstChunkMs = null;
    _previewVoiceId = voiceId;
    _previewing = true;
    _armPreviewWatchdog();
    _emitStatusSnapshot();
    try {
      await _host.preview(text, voiceId);
    } catch (_) {
      // 通道异常：本地立即回退试听态，错误由宿主日志（诊断页可见）承载。
      _clearPreviewState();
      _emitStatusSnapshot();
    }
  }

  @override
  Future<void> stopPreview() async {
    try {
      await _host.stopPreview();
    } finally {
      _clearPreviewState();
      _emitStatusSnapshot();
    }
  }

  @override
  Future<AppConfig> config() async {
    final dto = await _host.config();
    return AppConfig(
      speed: dto.speed,
      onlineEnabled: dto.onlineEnabled,
      tokenPlanAccepted: dto.tokenPlanAccepted,
      keyKind: dto.keyKind,
      apiKeyMasked: dto.apiKeyMasked,
      baseUrl: dto.baseUrl,
      allowMobileData: dto.allowMobileData,
      customMirror: dto.customMirror,
    );
  }

  @override
  Future<void> updateConfig({
    double? speed,
    bool? onlineEnabled,
    bool? allowMobileData,
    String? keyKind,
    String? apiKey,
    String? baseUrl,
    String? customMirror,
    bool? tokenPlanAccepted,
  }) async {
    await _host.updateConfig(
      speed: speed,
      onlineEnabled: onlineEnabled,
      allowMobileData: allowMobileData,
      keyKind: keyKind,
      apiKey: apiKey,
      baseUrl: baseUrl,
      customMirror: customMirror,
      tokenPlanAccepted: tokenPlanAccepted,
    );
    _emitStatusSnapshot();
  }

  @override
  Future<List<String>> recentLogs() => _host.recentLogs();

  @override
  Future<void> clearPerfCounters() async {
    await _host.clearPerfCounters();
    _perf = <String, dynamic>{};
    _emitStatusSnapshot();
  }

  @override
  Future<String?> validateKey({
    required String keyKind,
    required String apiKey,
  }) =>
      _host.validateKey(keyKind, apiKey);

  @override
  Stream<EngineEvent> events() async* {
    yield EngineEvent('status', await _statusSnapshot());
    yield* _events.stream;
  }

  // ---------------------------------------------------------------- 事件接收

  /// 宿主事件入口（由生成的 [EngineEventApi] 回调）。
  void _onHostEvent(EngineEventDto dto) {
    final data = _decode(dto.dataJson);
    final phase = data['phase'] as String?;

    // 试听生命周期：preview_start / preview / preview_done / preview_stopped
    if (phase != null && phase.startsWith('preview')) {
      _onPreviewLifecycle(phase, data);
      return;
    }

    // 宿主 status 事件是部分字段（如 {modelId,state} / {narrator} / {phase}），
    // 统一折算为**完整快照**后再发给界面（与 Mock 语义一致）。
    if (dto.type == 'status') {
      final narrator = data['narrator'];
      if (narrator is String && narrator.isNotEmpty) {
        _narratorVoiceId = narrator;
      }
      _emitStatusSnapshot();
      return;
    }

    // 引擎报错且正在试听：清试听态（引擎侧已停止尝试）。
    if (dto.type == 'error' && _previewing) {
      _clearPreviewState();
      _emitStatusSnapshot();
    }
    _emit(dto.type, data);
  }

  void _onPreviewLifecycle(String phase, Map<String, dynamic> data) {
    final now = DateTime.now().millisecondsSinceEpoch;
    switch (phase) {
      case 'preview_start':
        if (_previewRequestedAt != null) {
          _previewFirstChunkMs = now - _previewRequestedAt!;
        }
        _previewing = true;
        _armPreviewWatchdog();
        _emitStatusSnapshot();
      case 'preview':
        // 进度事件（percent）：仅刷新界面，不产生性能记录。
        _emitStatusSnapshot();
      case 'preview_done':
        final requestedAt = _previewRequestedAt;
        if (requestedAt != null) {
          _perf = <String, dynamic>{
            'firstChunkMs': _previewFirstChunkMs ?? 0,
            'totalMs': now - requestedAt,
            'droppedChunks': 0,
            'perfAt': now,
            'sampleRate': data['sr'] ?? 0,
          };
        }
        _clearPreviewState();
        _emitStatusSnapshot();
      case 'preview_stopped':
        _clearPreviewState();
        _emitStatusSnapshot();
    }
  }

  void _armPreviewWatchdog() {
    _previewWatchdog?.cancel();
    _previewWatchdog = Timer(const Duration(seconds: 30), () {
      if (_previewing) {
        _clearPreviewState();
        _emitStatusSnapshot();
      }
    });
  }

  void _clearPreviewState() {
    _previewWatchdog?.cancel();
    _previewWatchdog = null;
    _previewing = false;
    _previewVoiceId = null;
  }

  // ---------------------------------------------------------------- 快照

  Future<Map<String, dynamic>> _statusSnapshot() async {
    try {
      final st = await status();
      return <String, dynamic>{
        'state': st.state.name,
        'modelId': st.modelId,
        'sampleRate': st.sampleRate,
        'speakers': st.speakers,
        'online': st.online.name,
        'perf': Map<String, dynamic>.from(_perf),
        'previewing': _previewing,
        'previewVoiceId': _previewVoiceId,
        'narratorVoiceId': _narratorVoiceId,
      };
    } catch (_) {
      // 宿主未注册/通道未就绪：不允许伪造数据，仅如实标记错误态。
      return <String, dynamic>{
        'state': EngineState.error.name,
        'modelId': null,
        'sampleRate': 0,
        'speakers': 0,
        'online': OnlineState.off.name,
        'perf': Map<String, dynamic>.from(_perf),
        'previewing': _previewing,
        'previewVoiceId': _previewVoiceId,
        'narratorVoiceId': _narratorVoiceId,
      };
    }
  }

  Future<void> _emitStatusSnapshot() async {
    try {
      _emit('status', await _statusSnapshot());
    } catch (_) {
      // 事件通道已关闭（如热重启瞬间）：忽略。
    }
  }

  void _emit(String type, Map<String, dynamic> data) {
    if (!_events.isClosed) {
      _events.add(EngineEvent(type, data));
    }
  }

  // ---------------------------------------------------------------- 工具

  static Map<String, dynamic> _decode(String dataJson) {
    try {
      final decoded = jsonDecode(dataJson);
      if (decoded is Map<String, dynamic>) {
        return decoded;
      }
    } catch (_) {
      // 引擎回传的 JSON 异常时视为空数据，不构造假字段。
    }
    return <String, dynamic>{};
  }

  static EngineState _engineState(String raw) => switch (raw) {
        'noModel' => EngineState.noModel,
        'loading' => EngineState.loading,
        'ready' => EngineState.ready,
        'reloading' => EngineState.reloading,
        'error' => EngineState.error,
        _ => EngineState.error,
      };

  static OnlineState _onlineState(String raw) => switch (raw) {
        'off' => OnlineState.off,
        'ready' => OnlineState.ready,
        'noKey' => OnlineState.noKey,
        'error' => OnlineState.error,
        _ => OnlineState.off,
      };
}

/// Flutter 侧事件接收端：宿主推送 → [PigeonEngineService] 事件流。
class _BridgeEventSink extends EngineEventApi {
  _BridgeEventSink(this._service);

  final PigeonEngineService _service;

  @override
  void onEvent(EngineEventDto event) => _service._onHostEvent(event);
}
