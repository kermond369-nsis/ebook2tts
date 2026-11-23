import 'dart:async';
import 'dart:math';

import 'engine_service.dart';

/// [EngineService] 的内存 Mock 实现（供 `flutter test` 与无真机调试）。
class MockEngineService implements EngineService {
  MockEngineService._() {
    _logs.add('书声已启动（在线多引擎 + 系统自带 TTS 兜底）');
  }

  static MockEngineService? _instance;

  static MockEngineService instance() {
    final it = _instance;
    if (it == null) {
      throw StateError('MockEngineService 尚未初始化：请先调用 create()');
    }
    return it;
  }

  static Future<MockEngineService> create() async {
    final service = MockEngineService._();
    _instance = service;
    return service;
  }

  final _events = StreamController<EngineEvent>.broadcast();
  final _random = Random(20261123);
  final List<String> _logs = <String>[];

  static const List<SystemTtsEngine> _mockEngines = <SystemTtsEngine>[
    SystemTtsEngine(
      packageName: 'com.xiaomi.mibrain.speech',
      label: '小爱语音引擎',
      isSystemApp: true,
    ),
    SystemTtsEngine(
      packageName: 'com.google.android.tts',
      label: 'Google 语音服务',
      isSystemApp: true,
    ),
  ];

  AppConfig _config = AppConfig.initial();
  String _apiKeyPlain = '';
  String _onlineErrorRaw = '';
  String _lastFallbackEngine = '';
  String _lastFallbackReason = '';
  int _lastFallbackAt = 0;

  Map<String, dynamic> _perf = <String, dynamic>{};
  Timer? _previewTimer;
  bool _previewing = false;
  String? _previewVoiceId;
  String? _previewStage;
  String? _previewError;

  OnlineState _onlineState() {
    if (!_config.onlineEnabled) return OnlineState.off;
    if (_onlineErrorRaw.isNotEmpty) return OnlineState.error;
    if (_config.providerKind == 'mimo' && _apiKeyPlain.isEmpty) {
      return OnlineState.noKey;
    }
    if (_config.providerKind == 'custom_http' &&
        !_config.baseUrl.startsWith('https://')) {
      return OnlineState.error;
    }
    return OnlineState.ready;
  }

  SystemTtsEngine _effectiveFallback() {
    if (_config.fallbackTtsEngine.isNotEmpty) {
      for (final e in _mockEngines) {
        if (e.packageName == _config.fallbackTtsEngine) return e;
      }
    }
    return _mockEngines.first;
  }

  Map<String, dynamic> _statusSnapshot() => <String, dynamic>{
        'online': _onlineState().name,
        'providerKind': _config.providerKind,
        'perf': Map<String, dynamic>.from(_perf),
        'previewing': _previewing,
        'previewVoiceId': _previewVoiceId,
        'previewStage': _previewStage,
        'previewError': _previewError,
      };

  void _emitStatus() {
    if (!_events.isClosed) {
      _events.add(EngineEvent('status', _statusSnapshot()));
    }
  }

  void _log(String line) {
    _logs.add(line);
    if (_logs.length > 80) {
      _logs.removeRange(0, _logs.length - 80);
    }
  }

  @override
  Future<EngineStatus> status() async {
    final fb = _effectiveFallback();
    return EngineStatus(
      online: _onlineState(),
      providerKind: _config.providerKind,
      onlineLastError: _onlineErrorRaw,
      fallbackEnginePackage: fb.packageName,
      fallbackEngineLabel: fb.label,
      hasFallbackEngine: true,
      lastFallbackEngine: _lastFallbackEngine,
      lastFallbackReason: _lastFallbackReason,
      lastFallbackAt: _lastFallbackAt,
    );
  }

  @override
  Future<AppConfig> config() async => _config;

  @override
  Future<void> updateConfig({
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
    var next = _config;
    if (keyKind != null && keyKind != _config.keyKind) {
      next = next.copyWith(
        keyKind: keyKind,
        baseUrl: (baseUrl != null && baseUrl.isNotEmpty)
            ? baseUrl
            : AppConfig.baseUrlFor(keyKind),
      );
      _onlineErrorRaw = '';
    } else if (baseUrl != null) {
      final kind = providerKind ?? next.providerKind;
      next = next.copyWith(
        baseUrl: kind == 'mimo' && baseUrl.isEmpty
            ? AppConfig.baseUrlFor(next.keyKind)
            : baseUrl,
      );
    }
    if (apiKey != null) {
      _apiKeyPlain = apiKey;
      _onlineErrorRaw = '';
      next = next.copyWith(apiKeyMasked: _mask(apiKey));
    }
    if (speed != null) next = next.copyWith(speed: speed);
    if (onlineEnabled != null) {
      next = next.copyWith(onlineEnabled: onlineEnabled);
      if (!onlineEnabled) _onlineErrorRaw = '';
    }
    if (allowMobileData != null) {
      next = next.copyWith(allowMobileData: allowMobileData);
    }
    if (tokenPlanAccepted != null) {
      next = next.copyWith(tokenPlanAccepted: tokenPlanAccepted);
    }
    if (roleVoiceEnabled != null) {
      next = next.copyWith(roleVoiceEnabled: roleVoiceEnabled);
    }
    if (providerKind != null) {
      next = next.copyWith(providerKind: providerKind);
      _onlineErrorRaw = '';
    }
    if (model != null) next = next.copyWith(model: model);
    if (voice != null) next = next.copyWith(voice: voice);
    if (responseFormat != null) {
      next = next.copyWith(responseFormat: responseFormat);
    }
    if (sampleRate != null) next = next.copyWith(sampleRate: sampleRate);
    if (customHeaders != null) {
      next = next.copyWith(customHeaders: customHeaders);
    }
    if (customBodyTemplate != null) {
      next = next.copyWith(customBodyTemplate: customBodyTemplate);
    }
    if (timeoutMs != null) {
      next = next.copyWith(timeoutMs: timeoutMs.clamp(2000, 30000));
    }
    if (fallbackTtsEngine != null) {
      next = next.copyWith(fallbackTtsEngine: fallbackTtsEngine);
    }
    _config = next;
    _log('配置已更新（${_config.providerKind} / 超时 ${_config.timeoutMs}ms）');
    _emitStatus();
  }

  @override
  Future<String?> validateOnline({String? keyKind, String? apiKey}) async {
    await Future<void>.delayed(const Duration(milliseconds: 200));
    final kind = keyKind ?? _config.keyKind;
    final key = (apiKey != null && apiKey.isNotEmpty) ? apiKey : _apiKeyPlain;
    if (_config.providerKind == 'mimo') {
      final prefix = kind == 'plan' ? 'tp-' : 'sk-';
      if (!key.startsWith(prefix) || key.length < 12) {
        final err = kind == 'plan'
            ? '平台方返回：无效的 Token Plan 密钥（401 未授权）'
            : '平台方返回：无效的 API Key（401 未授权）';
        _onlineErrorRaw = err;
        _log('校验失败：$err');
        _emitStatus();
        return err;
      }
    }
    _onlineErrorRaw = '';
    _log('在线连通性校验通过（${_config.providerKind}）');
    _emitStatus();
    return null;
  }

  @override
  Future<List<SystemTtsEngine>> systemTtsEngines() async => _mockEngines;

  @override
  Future<void> preview({required String text, String voiceId = ''}) async {
    _previewTimer?.cancel();
    _previewing = true;
    _previewVoiceId = voiceId;
    _previewError = null;
    final onlineReady = _onlineState() == OnlineState.ready;
    _previewStage = onlineReady ? 'connecting_online' : 'fallback_system';
    _log(onlineReady
        ? '开始试听（在线：${_config.providerKind}）'
        : '在线未就绪，已回退系统 TTS（${_effectiveFallback().label}）');
    _emitStatus();
    _previewTimer = Timer(const Duration(milliseconds: 800), () {
      if (!_previewing) return;
      _previewing = false;
      _previewStage = null;
      _previewVoiceId = null;
      final first = 180 + _random.nextInt(140);
      final total = 900 + _random.nextInt(400);
      if (!onlineReady) {
        _lastFallbackEngine = _effectiveFallback().label;
        _lastFallbackReason = 'online_disabled';
        _lastFallbackAt = DateTime.now().millisecondsSinceEpoch;
      }
      _perf = <String, dynamic>{
        'firstChunkMs': first,
        'totalMs': total,
        'droppedChunks': 0,
        'backend': onlineReady ? 'online' : 'system_tts',
        'perfAt': DateTime.now().millisecondsSinceEpoch,
      };
      _emitStatus();
    });
  }

  @override
  Future<void> stopPreview() async {
    _previewTimer?.cancel();
    _previewing = false;
    _previewStage = null;
    _previewVoiceId = null;
    _emitStatus();
  }

  @override
  Future<List<String>> recentLogs() async => List<String>.unmodifiable(_logs);

  @override
  Future<void> clearPerfCounters() async {
    _perf = <String, dynamic>{};
    _lastFallbackEngine = '';
    _lastFallbackReason = '';
    _lastFallbackAt = 0;
    _log('性能计数与回退记录已清零');
    _emitStatus();
  }

  @override
  Stream<EngineEvent> events() async* {
    yield EngineEvent('status', _statusSnapshot());
    yield* _events.stream;
  }

  static String _mask(String key) {
    if (key.length <= 6) return '…';
    return '${key.substring(0, 3)}…${key.substring(key.length - 4)}';
  }

  void dispose() {
    _previewTimer?.cancel();
    _events.close();
  }
}
