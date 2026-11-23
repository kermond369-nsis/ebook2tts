/// 引擎访问协议（App 侧与引擎侧的接口边界）。
library;

/// 在线朗读状态。
enum OnlineState { off, ready, noKey, error }

/// 设备上已安装的系统或第三方 TTS 引擎条目（用于弱网回退选择）。
class SystemTtsEngine {
  const SystemTtsEngine({
    required this.packageName,
    required this.label,
    required this.isSystemApp,
  });

  final String packageName;
  final String label;
  final bool isSystemApp;
}

/// 引擎总体状态（纯在线 + 系统自带 TTS 兜底）。
class EngineStatus {
  const EngineStatus({
    this.online = OnlineState.off,
    this.providerKind = 'mimo',
    this.onlineLastError = '',
    this.fallbackEnginePackage = '',
    this.fallbackEngineLabel = '未检测到其他系统 TTS',
    this.hasFallbackEngine = false,
    this.lastFallbackEngine = '',
    this.lastFallbackReason = '',
    this.lastFallbackAt = 0,
  });

  final OnlineState online;
  final String providerKind;
  final String onlineLastError;
  final String fallbackEnginePackage;
  final String fallbackEngineLabel;
  final bool hasFallbackEngine;
  final String lastFallbackEngine;
  final String lastFallbackReason;
  final int lastFallbackAt;
}

/// 小米 MiMo 官方预置音色条目。
class MimoVoicePreset {
  const MimoVoicePreset({
    required this.id,
    required this.label,
    required this.lang,
  });

  final String id;
  final String label;
  final String lang;
}

const List<MimoVoicePreset> kMimoVoicePresets = <MimoVoicePreset>[
  MimoVoicePreset(id: '白桦', label: '白桦 · 中文男声', lang: 'zh'),
  MimoVoicePreset(id: '苏打', label: '苏打 · 中文男声', lang: 'zh'),
  MimoVoicePreset(id: '冰糖', label: '冰糖 · 中文女声', lang: 'zh'),
  MimoVoicePreset(id: '茉莉', label: '茉莉 · 中文女声', lang: 'zh'),
  MimoVoicePreset(id: 'Mia', label: 'Mia · 英文女声', lang: 'en'),
  MimoVoicePreset(id: 'Chloe', label: 'Chloe · 英文女声', lang: 'en'),
  MimoVoicePreset(id: 'Milo', label: 'Milo · 英文男声', lang: 'en'),
  MimoVoicePreset(id: 'Dean', label: 'Dean · 英文男声', lang: 'en'),
];

/// 应用配置（单一模式：在线多引擎 + 弱网超时自动回退系统 TTS）。
class AppConfig {
  const AppConfig({
    required this.speed,
    required this.onlineEnabled,
    required this.tokenPlanAccepted,
    required this.keyKind,
    required this.apiKeyMasked,
    required this.baseUrl,
    required this.allowMobileData,
    this.roleVoiceEnabled = true,
    this.roleCount = 0,
    this.roleRefineCount = 0,
    this.providerKind = 'mimo',
    this.model = 'mimo-v2.5-tts',
    this.voice = '白桦',
    this.responseFormat = 'pcm',
    this.sampleRate = 24000,
    this.customHeaders = '',
    this.customBodyTemplate = '',
    this.timeoutMs = 6000,
    this.fallbackTtsEngine = '',
  });

  factory AppConfig.initial() => const AppConfig(
        speed: 1.0,
        onlineEnabled: false,
        tokenPlanAccepted: false,
        keyKind: 'billing',
        apiKeyMasked: '',
        baseUrl: baseUrlBilling,
        allowMobileData: false,
        roleVoiceEnabled: true,
        providerKind: 'mimo',
        model: 'mimo-v2.5-tts',
        voice: '白桦',
        responseFormat: 'pcm',
        sampleRate: 24000,
        timeoutMs: 6000,
        fallbackTtsEngine: '',
      );

  final double speed;
  final bool onlineEnabled;
  final bool tokenPlanAccepted;

  /// `'billing'` 按量计费 / `'plan'` Token Plan。
  final String keyKind;

  /// 脱敏后的密钥（仅用于回显，不落明文）。
  final String apiKeyMasked;
  final String baseUrl;
  final bool allowMobileData;

  /// 「AI 角色音色」开关（MiMo 专属）。
  final bool roleVoiceEnabled;
  final int roleCount;
  final int roleRefineCount;

  /// 在线引擎协议：`mimo` / `openai` / `custom_http`。
  final String providerKind;
  final String model;
  final String voice;
  final String responseFormat;
  final int sampleRate;
  final String customHeaders;
  final String customBodyTemplate;

  /// 弱网首包超时阈值（毫秒，默认 6000）。
  final int timeoutMs;

  /// 用户指定的兜底系统 TTS 引擎包名（空串 = 自动选择系统内置引擎）。
  final String fallbackTtsEngine;

  static const String baseUrlBilling = 'https://api.xiaomimimo.com/v1';
  static const String baseUrlPlan = 'https://token-plan-cn.xiaomimimo.com/v1';

  static String baseUrlFor(String keyKind) =>
      keyKind == 'plan' ? baseUrlPlan : baseUrlBilling;

  AppConfig copyWith({
    double? speed,
    bool? onlineEnabled,
    bool? tokenPlanAccepted,
    String? keyKind,
    String? apiKeyMasked,
    String? baseUrl,
    bool? allowMobileData,
    bool? roleVoiceEnabled,
    int? roleCount,
    int? roleRefineCount,
    String? providerKind,
    String? model,
    String? voice,
    String? responseFormat,
    int? sampleRate,
    String? customHeaders,
    String? customBodyTemplate,
    int? timeoutMs,
    String? fallbackTtsEngine,
  }) {
    return AppConfig(
      speed: speed ?? this.speed,
      onlineEnabled: onlineEnabled ?? this.onlineEnabled,
      tokenPlanAccepted: tokenPlanAccepted ?? this.tokenPlanAccepted,
      keyKind: keyKind ?? this.keyKind,
      apiKeyMasked: apiKeyMasked ?? this.apiKeyMasked,
      baseUrl: baseUrl ?? this.baseUrl,
      allowMobileData: allowMobileData ?? this.allowMobileData,
      roleVoiceEnabled: roleVoiceEnabled ?? this.roleVoiceEnabled,
      roleCount: roleCount ?? this.roleCount,
      roleRefineCount: roleRefineCount ?? this.roleRefineCount,
      providerKind: providerKind ?? this.providerKind,
      model: model ?? this.model,
      voice: voice ?? this.voice,
      responseFormat: responseFormat ?? this.responseFormat,
      sampleRate: sampleRate ?? this.sampleRate,
      customHeaders: customHeaders ?? this.customHeaders,
      customBodyTemplate: customBodyTemplate ?? this.customBodyTemplate,
      timeoutMs: timeoutMs ?? this.timeoutMs,
      fallbackTtsEngine: fallbackTtsEngine ?? this.fallbackTtsEngine,
    );
  }
}

/// 引擎事件。
class EngineEvent {
  const EngineEvent(this.type, this.data);

  final String type;
  final Map<String, dynamic> data;
}

/// 引擎服务协议。
abstract class EngineService {
  Future<EngineStatus> status();

  Future<AppConfig> config();

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
  });

  /// 校验当前在线配置/密钥：返回 `null` 表示通过，否则返回脱敏错误摘要。
  Future<String?> validateOnline({
    String? keyKind,
    String? apiKey,
  });

  /// 列出设备上可用的非本应用 TTS 引擎（用于弱网回退选择）。
  Future<List<SystemTtsEngine>> systemTtsEngines();

  /// 试听朗读（不改变全局默认音色）。
  Future<void> preview({required String text, String voiceId = ''});

  Future<void> stopPreview();

  Future<List<String>> recentLogs();

  Future<void> clearPerfCounters();

  Stream<EngineEvent> events();
}
