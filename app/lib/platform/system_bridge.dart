import 'package:flutter/services.dart';

/// 界面进程与系统之间的少量交互桥。
///
/// 只承载「跳转系统设置」一类纯界面事务；朗读、合成、下载一律走
/// [EngineService]（由 :engine 的独立进程完成），主进程零推理。
class SystemBridge {
  static const MethodChannel _channel =
      MethodChannel('com.kermond.ebook2tts/system');

  /// 打开系统「文字转语音」设置页。
  ///
  /// 返回：`tts` 已打开文字转语音设置；`settings` 退化为系统设置总页
  /// （需手动进入文字转语音）；`failed` 未能打开任何页面。
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
  ///
  /// 用途：真机 A/B 对照（本地 vs 在线）与「引擎在 :tts_service 进程是否出声」的验证；
  /// **不写配置、不改全局旁白**，仅等价于外部阅读器调用本引擎。
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

  /// 读取朗读路由模式与"是否已选择"（RQ-513）。
  ///
  /// 返回 null（桥不可用）⇒ 界面按"已选择、保持默认"处理，**不打断**用户（fail-open）。
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

  /// 写入朗读路由模式并置"已选择"（RQ-513 开局两步选择的落库点）。
  ///
  /// 返回实际落库的模式 id；桥不可用返回 null（调用方据此提示"未能保存"）。
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


/// 开局模式状态（RQ-513）：模式 + 是否已选择过。
class RouteModeState {
  const RouteModeState({required this.mode, required this.chosen});

  /// 四种取值：only_online / only_local / prefer_online / prefer_local
  final String mode;

  /// false ⇒ 首启，必须先引导用户选择
  final bool chosen;
}
