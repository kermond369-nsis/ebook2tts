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

  /// 本地模式适用性判定（RQ-515/RQ-516，IM-546）。
  ///
  /// 用于「用户选择本地模式」时按需弹「性能不足，可能延迟极大」。
  /// 判定口径（阈值、玄戒 O1/O3 豁免、核数下限）全部在 :core 的 LocalModeAdvisor，
  /// 界面**不得**自行复刻该逻辑。桥不可用时返回 null（老版本/异常）⇒ 界面按"不弹"处理。
  static Future<LocalModeVerdict?> localModeVerdict() async {
    try {
      final m = await _channel
          .invokeMethod<Map<Object?, Object?>>('localModeVerdict');
      if (m == null) return null;
      return LocalModeVerdict(
        warn: (m['warn'] as bool?) ?? false,
        reason: (m['reason'] as String?) ?? '',
        soc: (m['soc'] as String?) ?? '',
        cores: (m['cores'] as int?) ?? 0,
      );
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }
}

/// 本地模式适用性判定结果（RQ-515）。
class LocalModeVerdict {
  const LocalModeVerdict({
    required this.warn,
    required this.reason,
    required this.soc,
    required this.cores,
  });

  /// true ⇒ 选择本地模式时应弹警告
  final bool warn;

  /// 判定依据（用于弹窗副标题，如「SoC 低于骁龙 8 Gen 1」）
  final String reason;

  /// 实际读到的 SoC 型号（诊断/反馈用）
  final String soc;

  /// 物理核数（诊断用）
  final int cores;
}
