import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:shared_preferences/shared_preferences.dart';

import 'engine_service.dart';

/// [EngineService] 的 Mock 实现（P3 规格 §5）。
///
/// - 全页面可跑通、可点击、状态可切换；
/// - 模型/音色使用真实清单文案（体积为实测值）；
/// - 下载进度用假进度模拟：probe→download→verify→extract→promote；
/// - 关键配置（在线开关、Token Plan 同意、旁白、已装模型）落本机持久化，
///   以便「重启后已同意者直接可填、未同意者仍需弹窗」的验收成立。
///
/// 真机桥接由实施线随后替换为 Pigeon 实现，接口签名不变。
class MockEngineService implements EngineService {
  MockEngineService._(this._prefs) {
    _restore();
    _logs.add('引擎已就绪（本机离线引擎，界面为演示数据）');
  }

  static MockEngineService? _instance;

  /// 由 `main()` 完成异步初始化后注入。
  static MockEngineService instance() {
    final it = _instance;
    if (it == null) {
      throw StateError('MockEngineService 尚未初始化：请先调用 MockEngineService.create()');
    }
    return it;
  }

  /// 异步构造（读取本机持久化配置）。
  static Future<MockEngineService> create(SharedPreferences prefs) async {
    final service = MockEngineService._(prefs);
    _instance = service;
    return service;
  }

  final SharedPreferences _prefs;
  final _events = StreamController<EngineEvent>.broadcast();
  final _random = Random(20260914);
  final List<String> _logs = <String>[];

  late List<ModelInfo> _models = _catalog();
  String? _activeModelId;
  String _narratorVoiceId = _voicesCatalog().first.id;
  String _apiKeyPlain = '';
  String _onlineErrorRaw = '';
  AppConfig _config = AppConfig.initial();

  Map<String, dynamic> _perf = <String, dynamic>{};

  Timer? _downloadTimer;
  Timer? _previewTimer;
  DownloadProgress? _download;
  bool _previewing = false;
  String? _previewVoiceId;
  int _downloadGeneration = 0;

  // ---------------------------------------------------------------- 模型清单

  static List<ModelInfo> _catalog() => const <ModelInfo>[
        ModelInfo(
          id: 'kokoro-int8',
          label: 'Kokoro int8（主推）',
          note: '中英多音色，内置 103 个音色，体积与质量最平衡，首次引导默认下载。',
          downloadBytes: 147031220,
          extractedBytes: 215321602,
          installed: false,
          active: false,
          recommended: true,
        ),
        ModelInfo(
          id: 'vits-zh-ll',
          label: 'VITS zh-ll（保底）',
          note: '中文 5 音色，体积最小，适合低配设备与快速开始。',
          downloadBytes: 118810709,
          extractedBytes: 135457418,
          installed: false,
          active: false,
          recommended: false,
        ),
        ModelInfo(
          id: 'kokoro-fp32',
          label: 'Kokoro fp32（高端可选）',
          note: '更高音质，占用更大，建议中高端设备选用。',
          downloadBytes: 364816464,
          extractedBytes: 426654376,
          installed: false,
          active: false,
          recommended: false,
        ),
      ];

  static List<VoiceInfo> _voicesCatalog() => const <VoiceInfo>[
        VoiceInfo(id: 'zh-xiaoxiao', name: '晓晓（女声·温柔）', lang: 'zh', isNarrator: true),
        VoiceInfo(id: 'zh-xiaoyi', name: '晓伊（女声·明快）', lang: 'zh', isNarrator: false),
        VoiceInfo(id: 'zh-xiaoni', name: '晓妮（女声·亲切）', lang: 'zh', isNarrator: false),
        VoiceInfo(id: 'zh-yunxi', name: '云希（男声·沉稳）', lang: 'zh', isNarrator: false),
        VoiceInfo(id: 'zh-yunjian', name: '云健（男声·有力）', lang: 'zh', isNarrator: false),
        VoiceInfo(id: 'en-heart', name: 'Heart（英语·女声）', lang: 'en', isNarrator: false),
        VoiceInfo(id: 'en-bella', name: 'Bella（英语·女声）', lang: 'en', isNarrator: false),
        VoiceInfo(id: 'en-michael', name: 'Michael（英语·男声）', lang: 'en', isNarrator: false),
        VoiceInfo(id: 'en-emma', name: 'Emma（英语·女声）', lang: 'en', isNarrator: false),
      ];

  // ---------------------------------------------------------------- 持久化

  static const _kInstalled = 'mock.models.installed';
  static const _kActive = 'mock.models.active';
  static const _kNarrator = 'mock.voice.narrator';
  static const _kConfig = 'mock.config';
  static const _kPerf = 'mock.perf';

  void _restore() {
    final installed = _prefs.getStringList(_kInstalled) ?? const <String>[];
    final active = _prefs.getString(_kActive);
    _models = _models
        .map((m) => m.copyWith(
              installed: installed.contains(m.id),
              active: m.id == active,
            ))
        .toList();
    _activeModelId = _models.any((m) => m.active) ? active : null;
    _narratorVoiceId = _prefs.getString(_kNarrator) ?? _narratorVoiceId;

    final rawConfig = _prefs.getString(_kConfig);
    if (rawConfig != null && rawConfig.isNotEmpty) {
      try {
        final map = jsonDecode(rawConfig) as Map<String, dynamic>;
        _config = AppConfig(
          speed: (map['speed'] as num?)?.toDouble() ?? 1.0,
          onlineEnabled: map['onlineEnabled'] == true,
          tokenPlanAccepted: map['tokenPlanAccepted'] == true,
          keyKind: map['keyKind'] as String? ?? 'billing',
          apiKeyMasked: map['apiKeyMasked'] as String? ?? '',
          baseUrl: map['baseUrl'] as String? ??
              AppConfig.baseUrlFor(map['keyKind'] as String? ?? 'billing'),
          allowMobileData: map['allowMobileData'] == true,
          customMirror: map['customMirror'] as String? ?? '',
        );
      } catch (_) {
        _config = AppConfig.initial();
      }
    }

    final rawPerf = _prefs.getString(_kPerf);
    if (rawPerf != null && rawPerf.isNotEmpty) {
      try {
        _perf = jsonDecode(rawPerf) as Map<String, dynamic>;
      } catch (_) {
        _perf = <String, dynamic>{};
      }
    }
  }

  Future<void> _persistConfig() async {
    await _prefs.setString(_kConfig, jsonEncode(<String, dynamic>{
      'speed': _config.speed,
      'onlineEnabled': _config.onlineEnabled,
      'tokenPlanAccepted': _config.tokenPlanAccepted,
      'keyKind': _config.keyKind,
      'apiKeyMasked': _config.apiKeyMasked,
      'baseUrl': _config.baseUrl,
      'allowMobileData': _config.allowMobileData,
      'customMirror': _config.customMirror,
    }));
  }

  Future<void> _persistModels() async {
    await _prefs.setStringList(
      _kInstalled,
      _models.where((m) => m.installed).map((m) => m.id).toList(),
    );
    if (_activeModelId == null) {
      await _prefs.remove(_kActive);
    } else {
      await _prefs.setString(_kActive, _activeModelId!);
    }
  }

  // ---------------------------------------------------------------- 事件与快照

  void _emit(String type, Map<String, dynamic> data) {
    if (!_events.isClosed) {
      _events.add(EngineEvent(type, data));
    }
  }

  void _emitStatus() {
    _emit('status', _statusSnapshot());
  }

  void _emitModels() {
    _emit('model', <String, dynamic>{
      'models': _models.map((m) => m.id).toList(),
    });
  }

  Map<String, dynamic> _statusSnapshot() => <String, dynamic>{
        'state': _state().name,
        'modelId': _activeModelId,
        'sampleRate': _sampleRate(),
        'speakers': _speakers(),
        'online': _onlineState().name,
        'perf': Map<String, dynamic>.from(_perf),
        'previewing': _previewing,
        'previewVoiceId': _previewVoiceId,
        'narratorVoiceId': _narratorVoiceId,
      };

  void _log(String line) {
    _logs.add(line);
    if (_logs.length > 80) {
      _logs.removeRange(0, _logs.length - 80);
    }
  }

  // ---------------------------------------------------------------- 状态推导

  EngineState _state() {
    if (_activeModelId == null) {
      return EngineState.noModel;
    }
    return EngineState.ready;
  }

  int _sampleRate() {
    if (_activeModelId == null) {
      return 0;
    }
    return _activeModelId == 'vits-zh-ll' ? 16000 : 24000;
  }

  int _speakers() {
    if (_activeModelId == null) {
      return 0;
    }
    return _activeModelId == 'vits-zh-ll' ? 5 : 103;
  }

  OnlineState _onlineState() {
    if (!_config.onlineEnabled) {
      return OnlineState.off;
    }
    if (_onlineErrorRaw.isNotEmpty) {
      return OnlineState.error;
    }
    if (_apiKeyPlain.isEmpty) {
      return OnlineState.noKey;
    }
    return OnlineState.ready;
  }

  @override
  Future<EngineStatus> status() async {
    return EngineStatus(
      state: _state(),
      modelId: _activeModelId,
      sampleRate: _sampleRate(),
      speakers: _speakers(),
      online: _onlineState(),
    );
  }

  // ---------------------------------------------------------------- 模型操作

  @override
  Future<List<ModelInfo>> models() async =>
      _models.map((m) => m.copyWith()).toList();

  @override
  Future<void> startDownload(String modelId) async {
    final target = _models.firstWhere((m) => m.id == modelId);
    if (target.installed) {
      return; // 已安装不可重复安装（规格：已安装显示"使用中"并禁用）
    }
    await _cancelDownloadInternal(silent: true);
    _downloadGeneration++;
    final generation = _downloadGeneration;
    _log('开始下载模型：${target.label}');

    _download = DownloadProgress(
      modelId: modelId,
      received: 0,
      total: target.downloadBytes,
      phase: 'probe',
    );
    _emit('progress', _progressMap(_download!));

    // 探测最快镜像 -> 下载 -> 校验 -> 解压 -> 收尾
    const probeMs = 700;
    const verifyMs = 900;
    const extractMs = 1400;
    const promoteMs = 500;

    final downloadMs =
        (target.downloadBytes / 26000000 * 1000).round().clamp(2600, 12000);

    Timer(Duration(milliseconds: probeMs), () {
      if (generation != _downloadGeneration) return;
      _log('已选择最快镜像，开始下载（共 ${_mb(target.downloadBytes)} 兆）');
      final start = DateTime.now();
      _downloadTimer = Timer.periodic(const Duration(milliseconds: 120), (t) {
        if (generation != _downloadGeneration) {
          t.cancel();
          return;
        }
        final elapsed = DateTime.now().difference(start).inMilliseconds;
        final frac = (elapsed / downloadMs).clamp(0.0, 1.0);
        final received = (target.downloadBytes * frac).round();
        _download = DownloadProgress(
          modelId: modelId,
          received: received,
          total: target.downloadBytes,
          phase: 'download',
        );
        _emit('progress', _progressMap(_download!));
        if (frac >= 1.0) {
          t.cancel();
          _download = DownloadProgress(
            modelId: modelId,
            received: target.downloadBytes,
            total: target.downloadBytes,
            phase: 'verify',
          );
          _log('下载完成，校验文件完整性');
          _emit('progress', _progressMap(_download!));
          Timer(const Duration(milliseconds: verifyMs), () {
            if (generation != _downloadGeneration) return;
            _download = DownloadProgress(
              modelId: modelId,
              received: target.downloadBytes,
              total: target.downloadBytes,
              phase: 'extract',
            );
            _log('校验通过，开始解压（解压后约占 ${_mb(target.extractedBytes)} 兆）');
            _emit('progress', _progressMap(_download!));
            Timer(const Duration(milliseconds: extractMs), () {
              if (generation != _downloadGeneration) return;
              _download = DownloadProgress(
                modelId: modelId,
                received: target.downloadBytes,
                total: target.downloadBytes,
                phase: 'promote',
              );
              _log('解压完成，安装收尾');
              _emit('progress', _progressMap(_download!));
              Timer(const Duration(milliseconds: promoteMs), () async {
                if (generation != _downloadGeneration) return;
                await _finishInstall(modelId);
              });
            });
          });
        }
      });
    });
  }

  Map<String, dynamic> _progressMap(DownloadProgress p) => <String, dynamic>{
        'modelId': p.modelId,
        'received': p.received,
        'total': p.total,
        'phase': p.phase,
      };

  Future<void> _finishInstall(String modelId) async {
    _models = _models
        .map((m) => m.id == modelId
            ? m.copyWith(installed: true, active: true)
            : m.copyWith(active: false))
        .toList();
    _activeModelId = modelId;
    _download = null;
    await _persistModels();
    _log('模型已安装并设为使用中：${_label(modelId)}');
    _emit('progress', <String, dynamic>{
      'modelId': modelId,
      'received': 0,
      'total': 0,
      'phase': 'done',
    });
    _emitModels();
    _emitStatus();
  }

  @override
  Future<void> cancelDownload() async {
    await _cancelDownloadInternal(silent: false);
  }

  Future<void> _cancelDownloadInternal({required bool silent}) async {
    if (_download == null) {
      return;
    }
    _downloadGeneration++;
    _downloadTimer?.cancel();
    _downloadTimer = null;
    _download = null;
    if (!silent) {
      _log('已取消下载，临时文件已清理');
    }
    _emit('progress', <String, dynamic>{
      'modelId': '',
      'received': 0,
      'total': 0,
      'phase': 'idle',
    });
  }

  @override
  Future<void> deleteModel(String modelId) async {
    final wasActive = _activeModelId == modelId;
    _models = _models
        .map((m) => m.id == modelId ? m.copyWith(installed: false, active: false) : m)
        .toList();
    if (wasActive) {
      _activeModelId = null;
    }
    await _persistModels();
    _log('已删除模型：${_label(modelId)}');
    _emitModels();
    _emitStatus();
  }

  @override
  Future<void> setActiveModel(String modelId) async {
    final target = _models.firstWhere((m) => m.id == modelId);
    if (!target.installed) {
      return;
    }
    _models = _models
        .map((m) => m.copyWith(active: m.id == modelId))
        .toList();
    _activeModelId = modelId;
    await _persistModels();
    _log('已切换使用中的模型：${target.label}');
    _emitModels();
    _emitStatus();
  }

  // ---------------------------------------------------------------- 音色

  @override
  Future<List<VoiceInfo>> voices() async => _voicesCatalog()
      .map((v) => v.copyWith(isNarrator: v.id == _narratorVoiceId))
      .toList();

  @override
  Future<void> setNarratorVoice(String voiceId) async {
    _narratorVoiceId = voiceId;
    await _prefs.setString(_kNarrator, voiceId);
    _log('已设置全局旁白音色：${_voiceName(voiceId)}');
    _emitStatus();
  }

  @override
  Future<void> preview({required String text, required String voiceId}) async {
    await _stopPreviewInternal(silent: true);
    _previewing = true;
    _previewVoiceId = voiceId;
    _log('开始试听：${_voiceName(voiceId)}（试听不改变全局旁白）');
    _emitStatus();
    final durationMs = (1200 + text.length * 55).clamp(1500, 6000);
    _previewTimer = Timer(Duration(milliseconds: durationMs), _finishPreview);
  }

  void _finishPreview() {
    if (!_previewing) {
      return;
    }
    _previewing = false;
    final voiceId = _previewVoiceId;
    _previewVoiceId = null;
    final firstChunk = 260 + _random.nextInt(180);
    final total = 1400 + _random.nextInt(900);
    _perf = <String, dynamic>{
      'firstChunkMs': firstChunk,
      'totalMs': total,
      'droppedChunks': 0,
      'perfAt': DateTime.now().millisecondsSinceEpoch,
    };
    _prefs.setString(_kPerf, jsonEncode(_perf));
    _log('试听完成（${voiceId == null ? '旁白' : _voiceName(voiceId)}）：'
        '首块 $firstChunk 毫秒，总耗时 ${(total / 1000).toStringAsFixed(1)} 秒，丢块 0');
    _emitStatus();
  }

  @override
  Future<void> stopPreview() async {
    await _stopPreviewInternal(silent: false);
  }

  Future<void> _stopPreviewInternal({required bool silent}) async {
    if (!_previewing) {
      return;
    }
    _previewTimer?.cancel();
    _previewTimer = null;
    _previewing = false;
    final voiceId = _previewVoiceId;
    _previewVoiceId = null;
    if (!silent) {
      _log('已停止试听（${voiceId == null ? '旁白' : _voiceName(voiceId)}）');
    }
    _emitStatus();
  }

  // ---------------------------------------------------------------- 配置

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
    String? customMirror,
    bool? tokenPlanAccepted,
  }) async {
    var next = _config;

    if (keyKind != null && keyKind != _config.keyKind) {
      next = next.copyWith(
        keyKind: keyKind,
        // 两类密钥与接口域名相互绑定：切换类型时自动跟随，除非同一次调用里显式覆盖
        baseUrl: (baseUrl != null && baseUrl.isNotEmpty)
            ? baseUrl
            : AppConfig.baseUrlFor(keyKind),
      );
      // 切换密钥类型即视为重新核验，旧错误清空
      _onlineErrorRaw = '';
    } else if (baseUrl != null) {
      // 空串 = 恢复自动跟随
      next = next.copyWith(
        baseUrl: baseUrl.isEmpty ? AppConfig.baseUrlFor(next.keyKind) : baseUrl,
      );
    }

    if (apiKey != null) {
      _apiKeyPlain = apiKey;
      _onlineErrorRaw = '';
      next = next.copyWith(apiKeyMasked: _mask(apiKey));
    }
    if (speed != null) {
      next = next.copyWith(speed: speed);
    }
    if (onlineEnabled != null) {
      next = next.copyWith(onlineEnabled: onlineEnabled);
      if (!onlineEnabled) {
        _onlineErrorRaw = '';
      }
    }
    if (allowMobileData != null) {
      next = next.copyWith(allowMobileData: allowMobileData);
    }
    if (customMirror != null) {
      next = next.copyWith(customMirror: customMirror);
    }
    if (tokenPlanAccepted != null) {
      next = next.copyWith(tokenPlanAccepted: tokenPlanAccepted);
    }

    _config = next;
    await _persistConfig();
    _emitStatus();
  }

  static String _mask(String key) {
    if (key.length <= 6) {
      return '…';
    }
    return '${key.substring(0, 3)}…${key.substring(key.length - 4)}';
  }

  // ---------------------------------------------------------------- 诊断

  @override
  Future<List<String>> recentLogs() async => List<String>.unmodifiable(_logs);

  @override
  Future<void> clearPerfCounters() async {
    _perf = <String, dynamic>{};
    await _prefs.remove(_kPerf);
    _log('性能计数已清零');
    _emitStatus();
  }

  @override
  Stream<EngineEvent> events() async* {
    // 订阅时先补发一份当前快照（含最近一次性能数据），随后为增量事件
    yield EngineEvent('status', _statusSnapshot());
    yield* _events.stream;
  }

  @override
  Future<String?> validateKey({
    required String keyKind,
    required String apiKey,
  }) async {
    await Future<void>.delayed(const Duration(milliseconds: 700));
    final prefix = keyKind == 'plan' ? 'tp-' : 'sk-';
    if (!apiKey.startsWith(prefix) || apiKey.length < 12) {
      final err = keyKind == 'plan'
          ? '平台方返回：无效的 Token Plan 密钥（401 未授权）'
          : '平台方返回：无效的 API Key（401 未授权）';
      _onlineErrorRaw = err;
      _log('密钥校验未通过：$err');
      _emitStatus();
      return err;
    }
    if (apiKey.contains('ban') || apiKey.contains('stop')) {
      const err = '平台方返回：该密钥已被停用或封禁（403 禁止访问）';
      _onlineErrorRaw = err;
      _log('密钥校验未通过：$err');
      _emitStatus();
      return err;
    }
    _onlineErrorRaw = '';
    _log('密钥校验通过（${keyKind == 'plan' ? 'Token Plan' : '按量计费'}）');
    _emitStatus();
    return null;
  }

  // ---------------------------------------------------------------- 工具

  String _label(String modelId) =>
      _models.firstWhere((m) => m.id == modelId, orElse: () => _models.first).label;

  String _voiceName(String voiceId) => _voicesCatalog()
      .firstWhere((v) => v.id == voiceId,
          orElse: () => _voicesCatalog().first)
      .name;

  static int _mb(int bytes) => bytes ~/ 1000000;

  void dispose() {
    _downloadTimer?.cancel();
    _previewTimer?.cancel();
    _events.close();
  }
}
