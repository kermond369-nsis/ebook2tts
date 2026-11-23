import 'package:flutter/services.dart';

/// 界面进程与系统之间的少量交互桥。
///
/// 只承载「跳转系统设置 / 路由模式 / 通用引擎扩展配置 / 运行时摘要」事务；
/// 朗读、合成、下载一律走 [EngineService]（由 `:engine` 的独立进程完成），主进程零推理。
class SystemBridge {
  static const MethodChannel _channel =
      MethodChannel('com.kermond.ebook2tts/system');

  /// 打开系统「文字转语音」设置页。
  static Future<String> openSystemTtsSettings() async {
    try {
      final result =
          await _channel.invokeMethod<String>('openSystemTtsSettings');
      return result ?? 'failed';
    } on PlatformException {
      return 'failed';
    } on MissingPluginException {
      return 'failed';
    }
  }

  /// 对照朗读：经系统 TTS 显式绑定本 App 的引擎朗读一段样例。
  static Future<bool> speakSample(String text) async {
    try {
      await _channel.invokeMethod<void>('speakSample', {'text': text});
      return true;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  /// 停止对照朗读。
  static Future<void> stopSample() async {
    try {
      await _channel.invokeMethod<void>('stopSample');
    } on PlatformException {
      // 忽略：停止失败不影响界面
    } on MissingPluginException {
      // 忽略
    }
  }

  /// 读取朗读路由模式与“是否已选择”（RQ-513）。
  ///
  /// 返回 null（桥不可用）⇒ 界面按“已选择、保持默认”处理，不打断用户。
  static Future<RouteModeState?> getRouteMode() async {
    try {
      final m = await _channel
          .invokeMethod<Map<Object?, Object?>>('getRouteMode');
      if (m == null) return null;
      return RouteModeState(
        mode: (m['mode'] as String?) ?? 'prefer_online',
        chosen: (m['chosen'] as bool?) ?? true,
      );
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  /// 写入朗读路由模式并置“已选择”。
  static Future<String?> setRouteMode(String modeId) async {
    try {
      return await _channel
          .invokeMethod<String>('setRouteMode', {'mode': modeId});
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  /// 读取通用在线 TTS 引擎配置（`mimo` / `openai` / `custom_http`）。
  static Future<OnlineProviderConfig?> getOnlineProviderConfig() async {
    try {
      final m = await _channel
          .invokeMethod<Map<Object?, Object?>>('getOnlineProviderConfig');
      if (m == null) return null;
      return OnlineProviderConfig(
        providerKind: (m['providerKind'] as String?) ?? 'mimo',
        baseUrl: (m['baseUrl'] as String?) ?? '',
        model: (m['model'] as String?) ?? '',
        voice: (m['voice'] as String?) ?? '',
        responseFormat: (m['responseFormat'] as String?) ?? 'pcm',
        sampleRate: (m['sampleRate'] as num?)?.toInt() ?? 24000,
        customHeaders: (m['customHeaders'] as String?) ?? '',
        customBodyTemplate: (m['customBodyTemplate'] as String?) ?? '',
      );
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  /// 保存通用在线 TTS 引擎配置并通知 `:tts_service` 热重载。
  static Future<bool> setOnlineProviderConfig(OnlineProviderConfig cfg) async {
    try {
      final res = await _channel.invokeMethod<String>(
        'setOnlineProviderConfig',
        <String, Object>{
          'providerKind': cfg.providerKind,
          'baseUrl': cfg.baseUrl,
          'model': cfg.model,
          'voice': cfg.voice,
          'responseFormat': cfg.responseFormat,
          'sampleRate': cfg.sampleRate,
          'customHeaders': cfg.customHeaders,
          'customBodyTemplate': cfg.customBodyTemplate,
        },
      );
      return res == 'ok';
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  /// 探测当前配置的在线 TTS 引擎连通性（null = 通过，否则为脱敏错误摘要）。
  static Future<String?> validateOnlineProvider({String? apiKey}) async {
    try {
      return await _channel.invokeMethod<String>(
        'validateOnlineProvider',
        <String, Object>{if (apiKey != null) 'apiKey': apiKey},
      );
    } on PlatformException catch (e) {
      return e.message ?? '平台调用失败';
    } on MissingPluginException {
      return '当前环境不支持该平台调用';
    }
  }

  /// 读取引擎/在线隔离错误与最近一次持久化性能数据。
  static Future<RuntimeMeta> getRuntimeMeta() async {
    try {
      final m = await _channel
          .invokeMethod<Map<Object?, Object?>>('getRuntimeMeta');
      if (m == null) return const RuntimeMeta();
      final rawPerf = m['lastPerf'];
      final perf = rawPerf is Map
          ? rawPerf.map((k, v) => MapEntry(k.toString(), v))
          : const <String, dynamic>{};
      return RuntimeMeta(
        onlineLastError: (m['onlineLastError'] as String?) ?? '',
        localLastError: (m['localLastError'] as String?) ?? '',
        lastPerf: perf,
        providerKind: (m['providerKind'] as String?) ?? 'mimo',
      );
    } on PlatformException {
      return const RuntimeMeta();
    } on MissingPluginException {
      return const RuntimeMeta();
    }
  }

  /// 读取下载镜像下拉配置与可用预设清单。
  static Future<MirrorConfigState> getMirrorConfig() async {
    try {
      final m = await _channel
          .invokeMethod<Map<Object?, Object?>>('getMirrorConfig');
      if (m == null) return MirrorConfigState.defaults();
      final rawPresets = m['presets'];
      final presets = rawPresets is List
          ? rawPresets
              .whereType<Map<Object?, Object?>>()
              .map(
                (p) => MirrorPresetItem(
                  id: (p['id'] as String?) ?? '',
                  label: (p['label'] as String?) ?? '',
                  prefix: (p['prefix'] as String?) ?? '',
                ),
              )
              .where((p) => p.id.isNotEmpty)
              .toList()
          : MirrorConfigState.defaultPresets;
      return MirrorConfigState(
        selected: (m['selected'] as String?) ?? 'auto',
        customUrl: (m['customUrl'] as String?) ?? '',
        presets: presets.isEmpty ? MirrorConfigState.defaultPresets : presets,
      );
    } on PlatformException {
      return MirrorConfigState.defaults();
    } on MissingPluginException {
      return MirrorConfigState.defaults();
    }
  }

  /// 保存下载镜像下拉选中项与自定义镜像地址。
  static Future<bool> setMirrorConfig({
    String? selected,
    String? customUrl,
  }) async {
    try {
      final res = await _channel.invokeMethod<String>(
        'setMirrorConfig',
        <String, Object>{
          if (selected != null) 'selected': selected,
          if (customUrl != null) 'customUrl': customUrl,
        },
      );
      return res == 'ok';
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  /// 对所有可用下载镜像执行 64KB Range 并行测速。
  static Future<List<MirrorProbeResult>> probeMirrors({
    String? customUrl,
  }) async {
    try {
      final raw = await _channel.invokeMethod<List<Object?>>(
        'probeMirrors',
        <String, Object>{if (customUrl != null) 'customUrl': customUrl},
      );
      if (raw == null) return const <MirrorProbeResult>[];
      return raw
          .whereType<Map<Object?, Object?>>()
          .map(
            (r) => MirrorProbeResult(
              id: (r['id'] as String?) ?? '',
              label: (r['label'] as String?) ?? '',
              ok: r['ok'] == true,
              latencyMs: (r['latencyMs'] as num?)?.toInt() ?? -1,
              speedKbps: (r['speedKbps'] as num?)?.toDouble() ?? -1.0,
              httpCode: (r['httpCode'] as num?)?.toInt() ?? 0,
            ),
          )
          .toList();
    } on PlatformException {
      return const <MirrorProbeResult>[];
    } on MissingPluginException {
      return const <MirrorProbeResult>[];
    }
  }

  /// 调用系统文件选择器导入离线模型归档或 `.onnx` 声码器。
  static Future<LocalImportResult> importLocalModel(String modelId) async {
    try {
      final m = await _channel.invokeMethod<Map<Object?, Object?>>(
        'importLocalModel',
        <String, Object>{'modelId': modelId},
      );
      if (m == null) {
        return const LocalImportResult(
          ok: false,
          cancelled: false,
          message: '当前环境不支持本地文件导入',
        );
      }
      return LocalImportResult(
        ok: m['ok'] == true,
        cancelled: m['cancelled'] == true,
        message: (m['message'] as String?) ?? '',
        modelId: m['modelId'] as String?,
      );
    } on PlatformException catch (e) {
      return LocalImportResult(
        ok: false,
        cancelled: false,
        message: e.message ?? '本地导入调用失败',
      );
    } on MissingPluginException {
      return const LocalImportResult(
        ok: false,
        cancelled: false,
        message: '当前平台通道不支持本地文件导入',
      );
    }
  }

  /// 读取本地推理线程与计算后端配置。
  static Future<InferenceConfigState> getInferenceConfig() async {
    try {
      final m = await _channel
          .invokeMethod<Map<Object?, Object?>>('getInferenceConfig');
      if (m == null) return InferenceConfigState.defaults();
      return InferenceConfigState(
        storedThreads: (m['storedThreads'] as num?)?.toInt() ?? 0,
        effectiveThreads: (m['effectiveThreads'] as num?)?.toInt() ?? 2,
        cpuCores: (m['cpuCores'] as num?)?.toInt() ?? 8,
        provider: (m['provider'] as String?) ?? 'cpu',
      );
    } on PlatformException {
      return InferenceConfigState.defaults();
    } on MissingPluginException {
      return InferenceConfigState.defaults();
    }
  }

  /// 保存本地推理线程数配置并通知引擎重载。
  static Future<bool> setInferenceConfig({int? threads}) async {
    try {
      final res = await _channel.invokeMethod<String>(
        'setInferenceConfig',
        <String, Object>{if (threads != null) 'threads': threads},
      );
      return res == 'ok';
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }
}

/// 通用在线 TTS 引擎配置。
class OnlineProviderConfig {
  const OnlineProviderConfig({
    this.providerKind = 'mimo',
    this.baseUrl = '',
    this.model = '',
    this.voice = '',
    this.responseFormat = 'pcm',
    this.sampleRate = 24000,
    this.customHeaders = '',
    this.customBodyTemplate = '',
  });

  final String providerKind;
  final String baseUrl;
  final String model;
  final String voice;
  final String responseFormat;
  final int sampleRate;
  final String customHeaders;
  final String customBodyTemplate;

  OnlineProviderConfig copyWith({
    String? providerKind,
    String? baseUrl,
    String? model,
    String? voice,
    String? responseFormat,
    int? sampleRate,
    String? customHeaders,
    String? customBodyTemplate,
  }) {
    return OnlineProviderConfig(
      providerKind: providerKind ?? this.providerKind,
      baseUrl: baseUrl ?? this.baseUrl,
      model: model ?? this.model,
      voice: voice ?? this.voice,
      responseFormat: responseFormat ?? this.responseFormat,
      sampleRate: sampleRate ?? this.sampleRate,
      customHeaders: customHeaders ?? this.customHeaders,
      customBodyTemplate: customBodyTemplate ?? this.customBodyTemplate,
    );
  }
}

/// 朗读路由模式状态（RQ-513）。
class RouteModeState {
  const RouteModeState({required this.mode, required this.chosen});

  final String mode;
  final bool chosen;
}

/// 运行时诊断隔离摘要（在线错误与本地错误完全分离）。
class RuntimeMeta {
  const RuntimeMeta({
    this.onlineLastError = '',
    this.localLastError = '',
    this.lastPerf = const <String, dynamic>{},
    this.providerKind = 'mimo',
  });

  final String onlineLastError;
  final String localLastError;
  final Map<String, dynamic> lastPerf;
  final String providerKind;
}

/// 镜像下拉选项定义。
class MirrorPresetItem {
  const MirrorPresetItem({
    required this.id,
    required this.label,
    required this.prefix,
  });

  final String id;
  final String label;
  final String prefix;
}

/// 当前选中的镜像源与预设清单。
class MirrorConfigState {
  const MirrorConfigState({
    required this.selected,
    required this.customUrl,
    required this.presets,
  });

  factory MirrorConfigState.defaults() => const MirrorConfigState(
        selected: 'auto',
        customUrl: '',
        presets: defaultPresets,
      );

  static const List<MirrorPresetItem> defaultPresets = <MirrorPresetItem>[
    MirrorPresetItem(id: 'auto', label: '自动测速选最快（推荐）', prefix: ''),
    MirrorPresetItem(
      id: 'https://gh-proxy.com',
      label: 'gh-proxy.com（国内加速）',
      prefix: 'https://gh-proxy.com',
    ),
    MirrorPresetItem(
      id: 'https://gh-proxy.org',
      label: 'gh-proxy.org（备用加速）',
      prefix: 'https://gh-proxy.org',
    ),
    MirrorPresetItem(
      id: 'https://edgeone.gh-proxy.org',
      label: 'EdgeOne 加速镜像',
      prefix: 'https://edgeone.gh-proxy.org',
    ),
    MirrorPresetItem(
      id: 'https://cdn.gh-proxy.org',
      label: 'CDN 加速镜像',
      prefix: 'https://cdn.gh-proxy.org',
    ),
    MirrorPresetItem(id: 'direct', label: 'GitHub 官方直连', prefix: ''),
    MirrorPresetItem(id: 'custom', label: '自定义镜像 / 直链', prefix: ''),
  ];

  final String selected;
  final String customUrl;
  final List<MirrorPresetItem> presets;

  MirrorConfigState copyWith({
    String? selected,
    String? customUrl,
    List<MirrorPresetItem>? presets,
  }) {
    return MirrorConfigState(
      selected: selected ?? this.selected,
      customUrl: customUrl ?? this.customUrl,
      presets: presets ?? this.presets,
    );
  }
}

/// 单节点镜像测速结果。
class MirrorProbeResult {
  const MirrorProbeResult({
    required this.id,
    required this.label,
    required this.ok,
    required this.latencyMs,
    required this.speedKbps,
    required this.httpCode,
  });

  final String id;
  final String label;
  final bool ok;
  final int latencyMs;
  final double speedKbps;
  final int httpCode;

  String get shortSummary => ok
      ? '${latencyMs}ms · ${speedKbps >= 1000 ? '${(speedKbps / 1024).toStringAsFixed(1)} MB/s' : '${speedKbps.toStringAsFixed(0)} KB/s'}'
      : (httpCode > 0 ? 'HTTP $httpCode' : '连接超时');
}

/// 本地导入结果。
class LocalImportResult {
  const LocalImportResult({
    required this.ok,
    required this.cancelled,
    required this.message,
    this.modelId,
  });

  final bool ok;
  final bool cancelled;
  final String message;
  final String? modelId;
}

/// 本地推理性能配置（CPU 线程数与计算后端）。
class InferenceConfigState {
  const InferenceConfigState({
    required this.storedThreads,
    required this.effectiveThreads,
    required this.cpuCores,
    required this.provider,
  });

  factory InferenceConfigState.defaults() => const InferenceConfigState(
        storedThreads: 0,
        effectiveThreads: 2,
        cpuCores: 8,
        provider: 'cpu',
      );

  final int storedThreads;
  final int effectiveThreads;
  final int cpuCores;
  final String provider;

  InferenceConfigState copyWith({
    int? storedThreads,
    int? effectiveThreads,
    int? cpuCores,
    String? provider,
  }) {
    return InferenceConfigState(
      storedThreads: storedThreads ?? this.storedThreads,
      effectiveThreads: effectiveThreads ?? this.effectiveThreads,
      cpuCores: cpuCores ?? this.cpuCores,
      provider: provider ?? this.provider,
    );
  }
}
