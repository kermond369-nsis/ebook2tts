import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../platform/system_bridge.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';
import 'diagnostics_page.dart';
import 'disclaimer_page.dart';
import 'mode_setup_page.dart';

/// 全局设置页：精简清爽，不再堆砌在线鉴权/API 参数（已移至「在线」专属标签页）。
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
    final route = ref.watch(routeModeProvider).value ??
        const RouteModeState(mode: 'prefer_online', chosen: true);
    final workMode = route.workMode;

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

    return PageBody(
      children: [
        // ① 工作模式
        SectionCard(
          title: '朗读模式',
          subtitle: '决定顶部与底部界面展示的页面范围，以及网络与本地的协作策略。',
          trailing: StatusChip(
            label: routeModeLabel(route.mode),
            color: switch (workMode) {
              AppWorkMode.onlineOnly => Tokens.cloud,
              AppWorkMode.localOnly => Tokens.accent,
              AppWorkMode.hybrid => Tokens.hybrid,
            },
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (workMode == AppWorkMode.hybrid) ...[
                SegmentedButton<String>(
                  showSelectedIcon: false,
                  segments: const [
                    ButtonSegment(value: 'prefer_online', label: Text('在线优先')),
                    ButtonSegment(value: 'prefer_local', label: Text('本地优先')),
                  ],
                  selected: <String>{
                    route.prefersOnline ? 'prefer_online' : 'prefer_local',
                  },
                  onSelectionChanged: (s) =>
                      ref.read(routeModeProvider.notifier).setMode(s.first),
                ),
                const SizedBox(height: 12),
              ],
              SecondaryButton(
                label: '切换工作模式（纯在线 / 纯本地 / 混合）',
                icon: Icons.swap_horiz_rounded,
                onPressed: () => _openModeSetup(context, route.mode),
              ),
            ],
          ),
        ),

        // ② 全局语速
        SectionCard(
          title: '全局语速',
          subtitle: '同时作用于本机合成与在线合成；拖动松手即自动保存。',
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

        // ③ 网络与流量策略
        SectionCard(
          title: '网络与流量策略',
          subtitle: workMode == AppWorkMode.onlineOnly
              ? '纯在线模式下，开启后可在蜂窝移动网络下继续在线合成。'
              : workMode == AppWorkMode.localOnly
                  ? '仅影响「模型管理」下载本地语音模型；离线朗读本身零流量消耗。'
                  : '同时控制在线朗读与本地模型下载在蜂窝网络下的放行策略。',
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
                          v ? '已允许使用移动数据流量' : '已切换为仅在 Wi-Fi 下联网',
                        );
                      }
                    },
                  ),
                ],
              ),
              const SizedBox(height: 4),
              Text(
                config.allowMobileData
                    ? '已允许：移动数据网络下可正常在线朗读或下载模型。'
                    : '已关闭（默认）：仅在无线网络（Wi-Fi）下执行在线合成与模型下载。',
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
                leading: const Icon(Icons.settings_voice_outlined, color: Tokens.accent),
                title: const Text('系统「文字转语音」设置', style: TextStyle(fontSize: 14.5)),
                subtitle: const Text(
                  '将「书声」设为系统默认引擎供阅读软件调用',
                  style: TextStyle(fontSize: 12, color: Tokens.textDim),
                ),
                trailing: const Icon(Icons.chevron_right_rounded, color: Tokens.textDim),
                onTap: _openSystemSettings,
              ),
              const Divider(height: 8),
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.monitor_heart_outlined, color: Tokens.cloud),
                title: const Text('运行诊断与日志', style: TextStyle(fontSize: 14.5)),
                subtitle: const Text(
                  '系统通道 A/B 对照测试、性能指标与实时运行日志',
                  style: TextStyle(fontSize: 12, color: Tokens.textDim),
                ),
                trailing: const Icon(Icons.chevron_right_rounded, color: Tokens.textDim),
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
                leading: const Icon(Icons.gavel_outlined, color: Tokens.textDim),
                title: const Text('在线朗读免责声明', style: TextStyle(fontSize: 14.5)),
                subtitle: const Text(
                  '查看服务边界、密钥责任与隐私条款全文',
                  style: TextStyle(fontSize: 12, color: Tokens.textDim),
                ),
                trailing: const Icon(Icons.chevron_right_rounded, color: Tokens.textDim),
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
              KeyValueRow(label: '版本', value: 'v1.0.1（通用 TTS 转接端）'),
              KeyValueRow(label: '作者 / 许可', value: 'Kermond · MIT'),
            ],
          ),
        ),
      ],
    );
  }

  Future<void> _openModeSetup(BuildContext context, String currentMode) async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (routeCtx) => ModeSetupPage(
          initialMode: currentMode,
          isInitialSetup: false,
          onDone: () async {
            await ref.read(routeModeProvider.notifier).refresh();
            if (routeCtx.mounted) Navigator.of(routeCtx).pop();
          },
        ),
      ),
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
