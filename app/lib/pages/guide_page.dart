import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../platform/system_bridge.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';

/// 首页概览：展示在线合成状态、弱网系统 TTS 兜底状态与快速启用清单。
class GuidePage extends ConsumerWidget {
  const GuidePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = ref.watch(statusProvider).value;
    final config = ref.watch(configProvider).value;

    final onlineReady = status?.online == OnlineState.ready;
    final onlineEnabled = config?.onlineEnabled ?? false;
    final hasKeyOrCustom = (config?.apiKeyMasked.isNotEmpty ?? false) ||
        (config?.providerKind == 'custom_http' &&
            (config?.baseUrl.startsWith('https://') ?? false)) ||
        onlineReady;
    final timeoutSec = ((config?.timeoutMs ?? 6000) / 1000).round();
    final fallbackLabel = status?.fallbackEngineLabel ?? '自动探测中';

    return PageBody(
      children: [
        _HeroCard(
          onlineReady: onlineReady,
          onlineEnabled: onlineEnabled,
          fallbackLabel: fallbackLabel,
          timeoutSec: timeoutSec,
        ),
        if (onlineEnabled &&
            status?.online == OnlineState.error &&
            (status?.onlineLastError.isNotEmpty ?? false)) ...[
          InfoBanner(
            title: '在线合成异常（已自动回退系统 TTS）',
            body: status!.onlineLastError,
            color: Tokens.danger,
            icon: Icons.cloud_off_rounded,
            action: TextButton(
              onPressed: () => ref
                  .read(navIndexProvider.notifier)
                  .goDestination(AppDestination.online),
              child: const Text('前往在线配置排查'),
            ),
          ),
          const SizedBox(height: 12),
        ],
        SectionCard(
          title: '快速启用清单',
          subtitle: '无需下载几百兆离线语音包；在线优先，弱网自动切回设备系统 TTS。',
          child: Column(
            children: [
              _ChecklistTile(
                done: hasKeyOrCustom,
                title: '1. 配置在线引擎与音色',
                subtitle: '支持小米 MiMo（含 AI 角色造音色）、OpenAI 兼容与自定义 HTTP。',
                onTap: () => ref
                    .read(navIndexProvider.notifier)
                    .goDestination(AppDestination.online),
              ),
              const SizedBox(height: 8),
              _ChecklistTile(
                done: onlineEnabled,
                title: '2. 打开「启用在线朗读」开关',
                subtitle: '开启前完成外发告知确认；关闭或断网时自动用系统 TTS 朗读。',
                onTap: () => ref
                    .read(navIndexProvider.notifier)
                    .goDestination(AppDestination.online),
              ),
              const SizedBox(height: 8),
              _ChecklistTile(
                done: onlineReady,
                title: '3. 示例试听并设为系统默认 TTS',
                subtitle: '先在「朗读」页试播确认效果，再去系统设置选用「书声」。',
                onTap: () => ref
                    .read(navIndexProvider.notifier)
                    .goDestination(
                      onlineReady
                          ? AppDestination.sample
                          : AppDestination.online,
                    ),
              ),
            ],
          ),
        ),
        SectionCard(
          title: '弱网与离线兜底状态',
          subtitle: '当在线接口连接/首包超过阈值或断网时，本引擎直接调用设备内置 TTS 接续朗读，绝不中断阅读。',
          trailing: StatusChip(
            label: (status?.hasFallbackEngine ?? false) ? '兜底就绪' : '待检测',
            color: (status?.hasFallbackEngine ?? false)
                ? Tokens.accent
                : Tokens.hybrid,
          ),
          child: Column(
            children: [
              KeyValueRow(
                label: '系统兜底引擎',
                value: fallbackLabel,
                valueColor: (status?.hasFallbackEngine ?? false)
                    ? Tokens.text
                    : Tokens.danger,
              ),
              KeyValueRow(
                label: '弱网首包超时',
                value: '$timeoutSec 秒后自动回退',
                valueColor: Tokens.cloud,
              ),
              const SizedBox(height: 8),
              SecondaryButton(
                label: '调整超时阈值与兜底引擎',
                icon: Icons.tune_rounded,
                onPressed: () => ref
                    .read(navIndexProvider.notifier)
                    .goDestination(AppDestination.settings),
              ),
            ],
          ),
        ),
        SectionCard(
          title: '系统 TTS 对接',
          subtitle: '将「书声」设为系统默认文字转语音引擎后，微信读书、Legado 等外部阅读器可直接调用。',
          child: PrimaryButton(
            label: onlineReady ? '去示例朗读' : '打开系统朗读设置',
            icon: onlineReady
                ? Icons.play_arrow_rounded
                : Icons.settings_voice_outlined,
            onPressed: onlineReady
                ? () => ref
                    .read(navIndexProvider.notifier)
                    .goDestination(AppDestination.sample)
                : () => _openSystemSettings(context),
          ),
        ),
      ],
    );
  }

  Future<void> _openSystemSettings(BuildContext context) async {
    final result = await SystemBridge.openSystemTtsSettings();
    if (!context.mounted) return;
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

class _HeroCard extends StatelessWidget {
  const _HeroCard({
    required this.onlineReady,
    required this.onlineEnabled,
    required this.fallbackLabel,
    required this.timeoutSec,
  });

  final bool onlineReady;
  final bool onlineEnabled;
  final String fallbackLabel;
  final int timeoutSec;

  @override
  Widget build(BuildContext context) {
    final accent = onlineReady ? Tokens.accent : Tokens.cloud;
    final title = onlineReady
        ? '在线合成已就绪'
        : (onlineEnabled ? '在线待配置 · 系统兜底中' : '当前使用系统 TTS 兜底');
    final subtitle = onlineReady
        ? '优先使用云端大模型语音合成；若弱网首包超过 $timeoutSec 秒或断网，自动平滑切换至「$fallbackLabel」。'
        : '轻量零本地模型设计：开启并配置在线引擎即可获得高拟真音色，未开启或弱网时无缝走「$fallbackLabel」。';

    return Container(
      margin: const EdgeInsets.only(bottom: Tokens.cardGap),
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: [
            accent.withValues(alpha: 0.16),
            Tokens.surface,
          ],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(Tokens.radiusCard),
        border: Border.all(color: accent.withValues(alpha: 0.4)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  color: accent.withValues(alpha: 0.18),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(
                  onlineReady ? Icons.cloud_done_outlined : Icons.graphic_eq_rounded,
                  color: accent,
                  size: 22,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(
                        fontSize: 17,
                        fontWeight: FontWeight.w800,
                        color: Tokens.text,
                      ),
                    ),
                    const SizedBox(height: 2),
                    const Text(
                      '在线合成 · 弱网超时回退系统 TTS',
                      style: TextStyle(fontSize: 12, color: Tokens.textDim),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Text(
            subtitle,
            style: const TextStyle(
              fontSize: 13.5,
              color: Tokens.text,
              height: 1.55,
            ),
          ),
        ],
      ),
    );
  }
}

class _ChecklistTile extends StatelessWidget {
  const _ChecklistTile({
    required this.done,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final bool done;
  final String title;
  final String subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(Tokens.radiusButton),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: Tokens.background,
          borderRadius: BorderRadius.circular(Tokens.radiusButton),
          border: Border.all(
            color: done ? Tokens.accent.withValues(alpha: 0.45) : Tokens.divider,
          ),
        ),
        child: Row(
          children: [
            Icon(
              done
                  ? Icons.check_circle_rounded
                  : Icons.radio_button_unchecked_rounded,
              color: done ? Tokens.accent : Tokens.textDim,
              size: 20,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                      color: Tokens.text,
                    ),
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
            const Icon(Icons.chevron_right_rounded, color: Tokens.textDim),
          ],
        ),
      ),
    );
  }
}
