import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';

import 'engine_service.dart';

/// [EngineService] 的真机实现：通过标准 MethodChannel + EventChannel 与 Android 引擎通讯。
class MethodChannelEngineService implements EngineService {
  MethodChannelEngineService._() {
    _eventSubscription = _eventChannel.receiveBroadcastStream().listen(
      _onHostEvent,
      onError: (_) {},
    );
  }

  static const MethodChannel _channel =
      MethodChannel('com.kermond.ebook2tts/engine');
  static const EventChannel _eventChannel =
      EventChannel('com.kermond.ebook2tts/engine_events');

  static MethodChannelEngineService? _instance;

  static MethodChannelEngineService instance() {
    final it = _instance;
    if (it == null) {
      throw StateError(
          'MethodChannelEngineService 尚未初始化：请先调用 create()');
    }
    return it;
  }

  static MethodChannelEngineService create() {
    final service = MethodChannelEngineService._();
    _instance = service;
    return service;
  }

  final StreamController<EngineEvent> _events =
      StreamController<EngineEvent>.broadcast();
  StreamSubscription<dynamic>? _eventSubscription;

  Map<String, dynamic> _perf = <String, dynamic>{};
  bool _previewing = false;
  String? _previewVoiceId;
  String? _previewStage;
  String? _previewError;
  Timer? _previewWatchdog;

  @override
  Future<EngineStatus> status() async {
    final m = await _channel.invokeMapMethod<String, dynamic>('getStatus') ??
        const <String, dynamic>{};
    final rawPerf = m['lastPerf'];
    if (rawPerf is Map) {
      _perf = rawPerf.map((k, v) => MapEntry(k.toString(), v));
    }
    return EngineStatus(
      online: _onlineState(m['online'] as String? ?? 'off'),
      providerKind: m['providerKind'] as String? ?? 'mimo',
      onlineLastError: m['onlineLastError'] as String? ?? '',
      fallbackEnginePackage: m['fallbackEnginePackage'] as String? ?? '',
      fallbackEngineLabel:
          m['fallbackEngineLabel'] as String? ?? '未检测到其他系统 TTS',
      hasFallbackEngine: m['hasFallbackEngine'] == true,
      lastFallbackEngine: m['lastFallbackEngine'] as String? ?? '',
      lastFallbackReason: m['lastFallbackReason'] as String? ?? '',
      lastFallbackAt: (m['lastFallbackAt'] as num?)?.toInt() ?? 0,
    );
  }

  @override
  Future<AppConfig> config() async {
    final m = await _channel.invokeMapMethod<String, dynamic>('getConfig') ??
        const <String, dynamic>{};
    return AppConfig(
      speed: (m['speed'] as num?)?.toDouble() ?? 1.0,
      onlineEnabled: m['onlineEnabled'] == true,
      tokenPlanAccepted: m['tokenPlanAccepted'] == true,
      keyKind: m['keyKind'] as String? ?? 'billing',
      apiKeyMasked: m['apiKeyMasked'] as String? ?? '',
      baseUrl: m['baseUrl'] as String? ?? AppConfig.baseUrlBilling,
      allowMobileData: m['allowMobileData'] == true,
      roleVoiceEnabled: m['roleVoiceEnabled'] != false,
      roleCount: (m['roleCount'] as num?)?.toInt() ?? 0,
      roleRefineCount: (m['roleRefineCount'] as num?)?.toInt() ?? 0,
      providerKind: m['providerKind'] as String? ?? 'mimo',
      model: m['model'] as String? ?? 'mimo-v2.5-tts',
      voice: m['voice'] as String? ?? '白桦',
      responseFormat: m['responseFormat'] as String? ?? 'pcm',
      sampleRate: (m['sampleRate'] as num?)?.toInt() ?? 24000,
      customHeaders: m['customHeaders'] as String? ?? '',
      customBodyTemplate: m['customBodyTemplate'] as String? ?? '',
      timeoutMs: (m['timeoutMs'] as num?)?.toInt() ?? 6000,
      fallbackTtsEngine: m['fallbackTtsEngine'] as String? ?? '',
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
    final args = <String, Object>{
      if (speed != null) 'speed': speed,
      if (onlineEnabled != null) 'onlineEnabled': onlineEnabled,
      if (allowMobileData != null) 'allowMobileData': allowMobileData,
      if (keyKind != null) 'keyKind': keyKind,
      if (apiKey != null) 'apiKey': apiKey,
      if (baseUrl != null) 'baseUrl': baseUrl,
      if (tokenPlanAccepted != null) 'tokenPlanAccepted': tokenPlanAccepted,
      if (roleVoiceEnabled != null) 'roleVoiceEnabled': roleVoiceEnabled,
      if (providerKind != null) 'providerKind': providerKind,
      if (model != null) 'model': model,
      if (voice != null) 'voice': voice,
      if (responseFormat != null) 'responseFormat': responseFormat,
      if (sampleRate != null) 'sampleRate': sampleRate,
      if (customHeaders != null) 'customHeaders': customHeaders,
      if (customBodyTemplate != null)
        'customBodyTemplate': customBodyTemplate,
      if (timeoutMs != null) 'timeoutMs': timeoutMs,
      if (fallbackTtsEngine != null) 'fallbackTtsEngine': fallbackTtsEngine,
    };
    await _channel.invokeMethod<bool>('updateConfig', args);
    await _emitStatusSnapshot();
  }

  @override
  Future<String?> validateOnline({String? keyKind, String? apiKey}) async {
    final err = await _channel.invokeMethod<String>(
      'validateOnline',
      <String, Object>{
        if (keyKind != null) 'keyKind': keyKind,
        if (apiKey != null) 'apiKey': apiKey,
      },
    );
    await _emitStatusSnapshot();
    return err;
  }

  @override
  Future<List<SystemTtsEngine>> systemTtsEngines() async {
    final list = await _channel
        .invokeListMethod<Map<Object?, Object?>>('listSystemTtsEngines');
    if (list == null) return const <SystemTtsEngine>[];
    return list
        .map(
          (m) => SystemTtsEngine(
            packageName: (m['packageName'] as String?) ?? '',
            label: (m['label'] as String?) ?? '',
            isSystemApp: m['isSystemApp'] == true,
          ),
        )
        .where((e) => e.packageName.isNotEmpty)
        .toList();
  }

  @override
  Future<void> preview({required String text, String voiceId = ''}) async {
    _previewVoiceId = voiceId;
    _previewStage = 'connecting_online';
    _previewError = null;
    _previewing = true;
    _armPreviewWatchdog();
    await _emitStatusSnapshot();
    try {
      await _channel.invokeMethod<void>('preview', <String, Object>{
        'text': text,
        'voiceId': voiceId,
      });
    } catch (_) {
      _clearPreviewState();
      await _emitStatusSnapshot();
    }
  }

  @override
  Future<void> stopPreview() async {
    try {
      await _channel.invokeMethod<void>('stopPreview');
    } finally {
      _clearPreviewState();
      await _emitStatusSnapshot();
    }
  }

  @override
  Future<List<String>> recentLogs() async {
    final logs = await _channel.invokeListMethod<String>('recentLogs');
    return logs ?? const <String>[];
  }

  @override
  Future<void> clearPerfCounters() async {
    await _channel.invokeMethod<void>('clearPerfCounters');
    _perf = <String, dynamic>{};
    await _emitStatusSnapshot();
  }

  @override
  Stream<EngineEvent> events() async* {
    yield EngineEvent('status', await _statusSnapshot());
    yield* _events.stream;
  }

  void _onHostEvent(dynamic raw) {
    if (raw is! Map) return;
    final type = raw['type'] as String? ?? 'status';
    final dataJson = raw['dataJson'] as String? ?? '{}';
    final data = _decode(dataJson);

    if (type == 'status') {
      final perf = data['perf'];
      if (perf is Map) {
        _perf = Map<String, dynamic>.from(perf);
      }
      final playing = data['previewing'];
      final previewStage = data['previewStage'] as String?;
      final previewErr = data['previewError'] as String?;
      if (previewStage != null) {
        _previewStage = previewStage;
      }
      if (previewErr != null) {
        _previewError = previewErr;
      }
      if (playing is bool) {
        if (playing) {
          _previewing = true;
          final pv = data['previewVoiceId'] as String?;
          if (pv != null && pv.isNotEmpty) {
            _previewVoiceId = pv;
          }
          if (previewErr == null) {
            _previewError = null;
          }
          _armPreviewWatchdog();
        } else {
          _clearPreviewState();
        }
      }
      _emitStatusSnapshot();
      return;
    }

    if (!_events.isClosed) {
      _events.add(EngineEvent(type, data));
    }
  }

  void _armPreviewWatchdog() {
    _previewWatchdog?.cancel();
    _previewWatchdog = Timer(const Duration(seconds: 45), () {
      if (_previewing) {
        _previewError ??= '朗读等待超时，请检查网络或前往「诊断」查看日志';
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
    _previewStage = null;
  }

  Future<Map<String, dynamic>> _statusSnapshot() async {
    try {
      final st = await status();
      return <String, dynamic>{
        'online': st.online.name,
        'providerKind': st.providerKind,
        'perf': Map<String, dynamic>.from(_perf),
        'previewing': _previewing,
        'previewVoiceId': _previewVoiceId,
        'previewStage': _previewStage,
        'previewError': _previewError,
      };
    } catch (_) {
      return <String, dynamic>{
        'online': OnlineState.off.name,
        'perf': Map<String, dynamic>.from(_perf),
        'previewing': _previewing,
        'previewVoiceId': _previewVoiceId,
        'previewStage': _previewStage,
        'previewError': _previewError,
      };
    }
  }

  Future<void> _emitStatusSnapshot() async {
    if (_events.isClosed) return;
    _events.add(EngineEvent('status', await _statusSnapshot()));
  }

  static Map<String, dynamic> _decode(String dataJson) {
    try {
      final decoded = jsonDecode(dataJson);
      if (decoded is Map<String, dynamic>) return decoded;
    } catch (_) {
      // 忽略异常格式事件载荷
    }
    return <String, dynamic>{};
  }

  static OnlineState _onlineState(String raw) => switch (raw) {
        'ready' => OnlineState.ready,
        'noKey' => OnlineState.noKey,
        'error' => OnlineState.error,
        _ => OnlineState.off,
      };

  void dispose() {
    _previewWatchdog?.cancel();
    _eventSubscription?.cancel();
    _events.close();
  }
}
