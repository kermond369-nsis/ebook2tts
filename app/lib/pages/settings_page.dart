import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../platform/system_bridge.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';
import 'diagnostics_page.dart';
import 'disclaimer_page.dart';

/// 全局设置页：语速、弱网超时阈值、系统 TTS 兜底引擎与网络流量策略。
class SettingsPage extends ConsumerStatefulWidget {
  const SettingsPage({super.key});

  @override
  ConsumerState<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends ConsumerState<SettingsPage> {
  double? _speedDraft;

  @override
  Widget build(BuildContext context) {
    final config = ref.watch(configProvider).value;
    final status = ref.watch(statusProvider).value;
    final enginesAsync = ref.watch(systemTtsEnginesProvider);

    if (config == null) {
      return const PageBody(
        children: [
          SectionCard(
            child: Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: CircularProgressIndicator(),
              ),
            ),
          ),
        ],
      );
    }

    final speed = _speedDraft ?? config.speed;
    final engines = enginesAsync.value ?? const <SystemTtsEngine>[];
    final timeoutSec = (config.timeoutMs / 1000).round();

    return PageBody(
      children: [
        // ① 全局语速
        SectionCard(
          title: '全局语速',
          subtitle: '同时作用于在线合成与系统 TTS 兜底；拖动松手即自动保存。',
          trailing: StatusChip(label: '${speed.toStringAsFixed(1)} 倍'),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Slider(
                value: speed.clamp(0.5, 2.0),
                min: 0.5,
                max: 2.0,
                divisions: 15,
                label: '${speed.toStringAsFixed(1)} 倍',
                onChanged: (v) => setState(() => _speedDraft = v),
                onChangeEnd: (v) async {
                  await ref.read(configProvider.notifier).save(speed: v);
                  if (mounted) {
                    setState(() => _speedDraft = null);
                    showAppSnack(context, '语速已保存：${v.toStringAsFixed(1)} 倍');
                  }
                },
              ),
              Wrap(
                spacing: 8,
                children: [
                  for (final preset in const [0.8, 1.0, 1.2, 1.5])
                    ChoiceChip(
                      label: Text('${preset.toStringAsFixed(1)}×'),
                      selected: (speed - preset).abs() < 0.05,
                      onSelected: (_) async {
                        await ref
                            .read(configProvider.notifier)
                            .save(speed: preset);
                        if (mounted) {
                          setState(() => _speedDraft = null);
                          showAppSnack(
                            context,
                            '语速已设为 ${preset.toStringAsFixed(1)} 倍',
                          );
                        }
                      },
                    ),
                ],
              ),
            ],
          ),
        ),

        // ② 弱网回退与系统 TTS 兜底
        SectionCard(
          title: '弱网回退与系统 TTS 兜底',
          subtitle: '在线合成超时、断网或服务不可达时，自动切换为设备自带系统 TTS 引擎（已硬性排除本应用防递归）。',
          trailing: StatusChip(
            label: '$timeoutSec 秒超时',
            color: Tokens.cloud,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                '弱网首包超时阈值',
                style: TextStyle(
                  fontSize: 13.5,
                  fontWeight: FontWeight.w600,
                  color: Tokens.text,
                ),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 6,
                children: [
                  for (final ms in const [4000, 6000, 8000, 12000])
                    ChoiceChip(
                      label: Text('${ms ~/ 1000} 秒'),
                      selected: config.timeoutMs == ms,
                      onSelected: (_) async {
                        await ref
                            .read(configProvider.notifier)
                            .save(timeoutMs: ms);
                        if (context.mounted) {
                          showAppSnack(context, '弱网超时阈值已设为 ${ms ~/ 1000} 秒');
                        }
                      },
                    ),
                ],
              ),
              const Divider(height: 22),
              Row(
                children: [
                  const Expanded(
                    child: Text(
                      '兜底系统 TTS 引擎',
                      style: TextStyle(
                        fontSize: 13.5,
                        fontWeight: FontWeight.w600,
                        color: Tokens.text,
                      ),
                    ),
                  ),
                  TextButton.icon(
                    onPressed: () => ref.invalidate(systemTtsEnginesProvider),
                    icon: const Icon(Icons.refresh_rounded, size: 16),
                    label: const Text('刷新'),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              _EngineRadioRow(
                selected: config.fallbackTtsEngine.isEmpty,
                title: '自动选择系统内置引擎（推荐）',
                subtitle: status != null && status.hasFallbackEngine
                    ? '当前自动选定：${status.fallbackEngineLabel}'
                    : '按手机厂商系统内置语音服务优先级自动匹配',
                onTap: () => ref
                    .read(configProvider.notifier)
                    .save(fallbackTtsEngine: ''),
              ),
              if (engines.isEmpty && !enginesAsync.isLoading)
                const Padding(
                  padding: EdgeInsets.only(top: 8),
                  child: Text(
                    '当前设备未探测到其他可用系统 TTS 引擎，请在系统设置确认已启用系统语音服务。',
                    style: TextStyle(fontSize: 12, color: Tokens.danger),
                  ),
                )
              else
                for (final engine in engines) ...[
                  const SizedBox(height: 8),
                  _EngineRadioRow(
                    selected: config.fallbackTtsEngine == engine.packageName,
                    title: engine.label,
                    subtitle: engine.packageName,
                    badge: engine.isSystemApp ? '系统内置' : null,
                    onTap: () => ref
                        .read(configProvider.notifier)
                        .save(fallbackTtsEngine: engine.packageName),
                  ),
                ],
            ],
          ),
        ),

        // ③ 网络与流量策略
        SectionCard(
          title: '网络与流量策略',
          subtitle: '关闭时，使用蜂窝移动网络将直接 0 毫秒回退系统自带 TTS，不消耗手机流量。',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Expanded(
                    child: Text(
                      '允许使用移动数据流量',
                      style: TextStyle(fontSize: 14.5, color: Tokens.text),
                    ),
                  ),
                  Switch(
                    value: config.allowMobileData,
                    onChanged: (v) async {
                      await ref
                          .read(configProvider.notifier)
                          .save(allowMobileData: v);
                      if (context.mounted) {
                        showAppSnack(
                          context,
                          v ? '已允许在移动数据下使用在线合成' : '已设为仅在 Wi-Fi 下联网',
                        );
                      }
                    },
                  ),
                ],
              ),
              const SizedBox(height: 4),
              Text(
                config.allowMobileData
                    ? '已允许：移动数据网络下仍优先在线合成（弱网超时后自动回退系统 TTS）。'
                    : '已关闭（默认）：仅在无线网络（Wi-Fi）下发起在线合成，蜂窝网络下自动使用系统 TTS。',
                style: const TextStyle(
                  fontSize: 12.5,
                  color: Tokens.textDim,
                  height: 1.5,
                ),
              ),
            ],
          ),
        ),

        // ④ 系统设置与诊断
        SectionCard(
          title: '系统接入与工具',
          child: Column(
            children: [
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(
                  Icons.settings_voice_outlined,
                  color: Tokens.accent,
                ),
                title: const Text(
                  '系统「文字转语音」设置',
                  style: TextStyle(fontSize: 14.5),
                ),
                subtitle: const Text(
                  '将「书声」设为系统默认引擎供阅读软件调用',
                  style: TextStyle(fontSize: 12, color: Tokens.textDim),
                ),
                trailing: const Icon(
                  Icons.chevron_right_rounded,
                  color: Tokens.textDim,
                ),
                onTap: _openSystemSettings,
              ),
              const Divider(height: 8),
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(
                  Icons.monitor_heart_outlined,
                  color: Tokens.cloud,
                ),
                title: const Text(
                  '运行诊断与日志',
                  style: TextStyle(fontSize: 14.5),
                ),
                subtitle: const Text(
                  '系统通道对照测试、弱网回退记录与实时运行日志',
                  style: TextStyle(fontSize: 12, color: Tokens.textDim),
                ),
                trailing: const Icon(
                  Icons.chevron_right_rounded,
                  color: Tokens.textDim,
                ),
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) => Scaffold(
                      appBar: AppBar(title: const Text('诊断与日志')),
                      body: const DiagnosticsPage(),
                    ),
                  ),
                ),
              ),
              const Divider(height: 8),
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(
                  Icons.gavel_outlined,
                  color: Tokens.textDim,
                ),
                title: const Text(
                  '在线朗读免责声明',
                  style: TextStyle(fontSize: 14.5),
                ),
                subtitle: const Text(
                  '查看服务边界、密钥责任与隐私条款全文',
                  style: TextStyle(fontSize: 12, color: Tokens.textDim),
                ),
                trailing: const Icon(
                  Icons.chevron_right_rounded,
                  color: Tokens.textDim,
                ),
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) => const DisclaimerPage(),
                  ),
                ),
              ),
            ],
          ),
        ),

        // ⑤ 关于
        const SectionCard(
          title: '关于书声',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              KeyValueRow(label: '应用名称', value: '书声（ebook2tts）'),
              KeyValueRow(label: '版本', value: 'v2.0.0（在线 + 系统兜底）'),
              KeyValueRow(label: '作者 / 许可', value: 'Kermond · MIT'),
            ],
          ),
        ),
      ],
    );
  }

  Future<void> _openSystemSettings() async {
    final result = await SystemBridge.openSystemTtsSettings();
    if (!mounted) return;
    switch (result) {
      case 'tts':
        showAppSnack(context, '已打开系统「文字转语音」设置，请选择「书声」');
      case 'settings':
        showAppSnack(context, '已打开系统设置，请手动进入「文字转语音」');
      default:
        showAppSnack(context, '未能打开系统设置，请手动前往「文字转语音」', danger: true);
    }
  }
}

class _EngineRadioRow extends StatelessWidget {
  const _EngineRadioRow({
    required this.selected,
    required this.title,
    required this.subtitle,
    required this.onTap,
    this.badge,
  });

  final bool selected;
  final String title;
  final String subtitle;
  final VoidCallback onTap;
  final String? badge;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(Tokens.radiusButton),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: selected
              ? Tokens.accent.withValues(alpha: 0.08)
              : Tokens.background,
          borderRadius: BorderRadius.circular(Tokens.radiusButton),
          border: Border.all(
            color: selected ? Tokens.accent : Tokens.divider,
            width: selected ? 1.5 : 1,
          ),
        ),
        child: Row(
          children: [
            Icon(
              selected
                  ? Icons.radio_button_checked_rounded
                  : Icons.radio_button_unchecked_rounded,
              size: 20,
              color: selected ? Tokens.accent : Tokens.textDim,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Flexible(
                        child: Text(
                          title,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                            color: selected ? Tokens.accent : Tokens.text,
                          ),
                        ),
                      ),
                      if (badge != null) ...[
                        const SizedBox(width: 6),
                        StatusChip(label: badge!, color: Tokens.cloud),
                      ],
                    ],
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: const TextStyle(
                      fontSize: 12,
                      color: Tokens.textDim,
                      height: 1.4,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
