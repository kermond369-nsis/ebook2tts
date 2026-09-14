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
}
