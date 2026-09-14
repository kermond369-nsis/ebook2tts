/// 引擎访问协议（App 侧与引擎侧的接口边界）。
///
/// 严格对应《P3-APP实施规格》§4 的 `EngineService` 协议——App 侧不得发明新接口；
/// 本文件为唯一权威签名，Mock 实现（`mock_engine_service.dart`）与后续 Pigeon
/// 真机实现必须保持同一签名。
///
/// 约定：诊断页展示的「最近一次性能数据」由 [EngineService.events] 中
/// `type == 'status'` 事件的 `data` 承载（键：`firstChunkMs` 首块耗时毫秒、
/// `totalMs` 总耗时毫秒、`droppedChunks` 丢块数、`perfAt` 记录时间）；
/// 事件流在订阅时会先补发一份当前状态快照，随后为增量事件。
library;

/// 引擎状态。
enum EngineState { noModel, loading, ready, reloading, error }

/// 在线朗读状态。
enum OnlineState { off, ready, noKey, error }

/// 引擎总体状态。
class EngineStatus {
  const EngineStatus({
    required this.state,
    this.modelId,
    this.sampleRate = 0,
    this.speakers = 0,
    this.online = OnlineState.off,
  });

  final EngineState state;
  final String? modelId;
  final int sampleRate;
  final int speakers;
  final OnlineState online;

  /// 规格 §4 中 `online` 与 `onlineState` 并列出现（同一含义），此处以别名兼容。
  OnlineState get onlineState => online;
}

/// 模型条目。
class ModelInfo {
  const ModelInfo({
    required this.id,
    required this.label,
    required this.note,
    required this.downloadBytes,
    required this.extractedBytes,
    required this.installed,
    required this.active,
    required this.recommended,
  });

  final String id;
  final String label;
  final String note;
  final int downloadBytes;
  final int extractedBytes;
  final bool installed;
  final bool active;
  final bool recommended;

  ModelInfo copyWith({
    String? id,
    String? label,
    String? note,
    int? downloadBytes,
    int? extractedBytes,
    bool? installed,
    bool? active,
    bool? recommended,
  }) {
    return ModelInfo(
      id: id ?? this.id,
      label: label ?? this.label,
      note: note ?? this.note,
      downloadBytes: downloadBytes ?? this.downloadBytes,
      extractedBytes: extractedBytes ?? this.extractedBytes,
      installed: installed ?? this.installed,
      active: active ?? this.active,
      recommended: recommended ?? this.recommended,
    );
  }
}

/// 下载进度。
///
/// [phase] 取值：`idle`/`probe`/`download`/`verify`/`extract`/`promote`/`done`/`error`。
class DownloadProgress {
  const DownloadProgress({
    required this.modelId,
    required this.received,
    required this.total,
    required this.phase,
  });

  final String modelId;
  final int received;
  final int total;
  final String phase;

  double get fraction => total <= 0 ? 0 : (received / total).clamp(0.0, 1.0);
}

/// 音色条目。
class VoiceInfo {
  const VoiceInfo({
    required this.id,
    required this.name,
    required this.lang,
    required this.isNarrator,
  });

  final String id;
  final String name;

  /// 语言标记：`zh` 中文优先、`en` 英文折叠展示。
  final String lang;

  /// 是否为当前旁白。
  final bool isNarrator;

  VoiceInfo copyWith({String? id, String? name, String? lang, bool? isNarrator}) {
    return VoiceInfo(
      id: id ?? this.id,
      name: name ?? this.name,
      lang: lang ?? this.lang,
      isNarrator: isNarrator ?? this.isNarrator,
    );
  }
}

/// 应用配置。
class AppConfig {
  const AppConfig({
    required this.speed,
    required this.onlineEnabled,
    required this.tokenPlanAccepted,
    required this.keyKind,
    required this.apiKeyMasked,
    required this.baseUrl,
    required this.allowMobileData,
    required this.customMirror,
  });

  /// 默认值：在线总开关关、数据流量关、按量计费、未同意 Token Plan（规格 §3/§6）。
  factory AppConfig.initial() => const AppConfig(
        speed: 1.0,
        onlineEnabled: false,
        tokenPlanAccepted: false,
        keyKind: 'billing',
        apiKeyMasked: '',
        baseUrl: baseUrlBilling,
        allowMobileData: false,
        customMirror: '',
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
  final String customMirror;

  /// 两类密钥与接口域名相互绑定，不可混用（见《免责声明》第三条）。
  static const String baseUrlBilling = 'https://api.xiaomimimo.com/v1';

  /// Token Plan（中国集群）接口域名。
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
    String? customMirror,
  }) {
    return AppConfig(
      speed: speed ?? this.speed,
      onlineEnabled: onlineEnabled ?? this.onlineEnabled,
      tokenPlanAccepted: tokenPlanAccepted ?? this.tokenPlanAccepted,
      keyKind: keyKind ?? this.keyKind,
      apiKeyMasked: apiKeyMasked ?? this.apiKeyMasked,
      baseUrl: baseUrl ?? this.baseUrl,
      allowMobileData: allowMobileData ?? this.allowMobileData,
      customMirror: customMirror ?? this.customMirror,
    );
  }
}

/// 引擎事件。`type`：`status`/`progress`/`model`/`error`。
class EngineEvent {
  const EngineEvent(this.type, this.data);

  final String type;
  final Map<String, dynamic> data;
}

/// 引擎服务协议（规格 §4 唯一权威定义）。
abstract class EngineService {
  Future<EngineStatus> status();

  Future<List<ModelInfo>> models();

  Future<void> startDownload(String modelId);

  Future<void> cancelDownload();

  Future<void> deleteModel(String modelId);

  Future<void> setActiveModel(String modelId);

  Future<List<VoiceInfo>> voices();

  Future<void> setNarratorVoice(String voiceId);

  /// 试听指定音色——**不改变全局旁白**。
  Future<void> preview({required String text, required String voiceId});

  Future<void> stopPreview();

  Future<AppConfig> config();

  Future<void> updateConfig({
    double? speed,
    bool? onlineEnabled,
    bool? allowMobileData,
    String? keyKind,
    String? apiKey,
    String? baseUrl,
    String? customMirror,
    bool? tokenPlanAccepted,
  });

  Future<List<String>> recentLogs();

  Future<void> clearPerfCounters();

  Stream<EngineEvent> events();

  /// 校验密钥：返回 `null` 表示通过，否则返回平台方原始错误摘要。
  Future<String?> validateKey({
    required String keyKind,
    required String apiKey,
  });
}
