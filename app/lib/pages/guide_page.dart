import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../platform/system_bridge.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';
import 'mode_setup_page.dart';

/// 首页概览：根据当前工作模式（纯在线 / 纯本地 / 混合）动态呈现状态、待办与核心入口。
class GuidePage extends ConsumerWidget {
  const GuidePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final route = ref.watch(routeModeProvider).value ??
        const RouteModeState(mode: 'prefer_online', chosen: true);
    final mode = route.workMode;
    final status = ref.watch(statusProvider).value;
    final config = ref.watch(configProvider).value;
    final models = ref.watch(modelsProvider).value ?? const <ModelInfo>[];
    final meta = ref.watch(runtimeMetaProvider).value ?? const RuntimeMeta();

    final installedModel = models.where((m) => m.installed).toList();
    final activeModel = models.where((m) => m.active).toList();
    final hasLocalModel = installedModel.isNotEmpty ||
        status?.state == EngineState.ready;
    final onlineReady = status?.online == OnlineState.ready;

    return PageBody(
      children: [
        _HeroOverviewCard(
          mode: mode,
          routeModeId: route.mode,
          status: status,
          onlineReady: onlineReady,
          hasLocalModel: hasLocalModel,
          activeModelLabel:
              activeModel.isNotEmpty ? activeModel.first.label : null,
          onSwitchMode: () => _openModeSwitch(context, ref, route.mode),
        ),
        if (mode != AppWorkMode.localOnly &&
            meta.onlineLastError.isNotEmpty &&
            status?.online == OnlineState.error) ...[
          InfoBanner(
            title: '在线合成待处理',
            body: meta.onlineLastError,
            color: Tokens.danger,
            icon: Icons.cloud_off_rounded,
            action: TextButton(
              onPressed: () => ref
                  .read(navIndexProvider.notifier)
                  .goDestination(AppDestination.online, mode),
              child: const Text('前往在线引擎排查'),
            ),
          ),
          const SizedBox(height: 12),
        ],
        if (mode == AppWorkMode.onlineOnly)
          _buildOnlineOnlySection(context, ref, status, config)
        else if (mode == AppWorkMode.localOnly)
          _buildLocalOnlySection(
            context,
            ref,
            status,
            hasLocalModel,
            activeModel.isNotEmpty ? activeModel.first.label : null,
          )
        else
          _buildHybridSection(
            context,
            ref,
            route,
            status,
            config,
            onlineReady,
            hasLocalModel,
            activeModel.isNotEmpty ? activeModel.first.label : null,
          ),
        const SizedBox(height: 6),
        SectionCard(
          title: '系统 TTS 对接',
          subtitle: '将「书声」设为系统默认文字转语音引擎后，微信读书、Legado 等外部阅读器可直接调用。',
          child: Row(
            children: [
              Expanded(
                child: SecondaryButton(
                  label: '打开系统朗读设置',
                  icon: Icons.settings_voice_outlined,
                  onPressed: () => _openSystemSettings(context),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildOnlineOnlySection(
    BuildContext context,
    WidgetRef ref,
    EngineStatus? status,
    AppConfig? config,
  ) {
    final enabled = config?.onlineEnabled ?? false;
    final onlineReady = status?.online == OnlineState.ready;
    final hasKeyOrCustom = (config?.apiKeyMasked.isNotEmpty ?? false) ||
        onlineReady;

    return Column(
      children: [
        SectionCard(
          title: '快速启用清单',
          subtitle: '纯在线模式无需下载几百兆离线模型，配好接口即可直接朗读。',
          child: Column(
            children: [
              _ChecklistTile(
                done: hasKeyOrCustom,
                title: '1. 配置在线引擎与鉴权',
                subtitle: '支持小米 MiMo（含角色音色）、OpenAI 兼容（硅基流动等）与自定义 HTTP。',
                onTap: () => ref
                    .read(navIndexProvider.notifier)
                    .goDestination(AppDestination.online, AppWorkMode.onlineOnly),
              ),
              const SizedBox(height: 8),
              _ChecklistTile(
                done: enabled,
                title: '2. 打开「在线朗读」开关',
                subtitle: '开启前完成外发告知确认，随时可关闭。',
                onTap: () => ref
                    .read(navIndexProvider.notifier)
                    .goDestination(AppDestination.online, AppWorkMode.onlineOnly),
              ),
              const SizedBox(height: 8),
              _ChecklistTile(
                done: onlineReady,
                title: '3. 试播一段测试文字并设系统默认 TTS',
                subtitle: '进入「朗读」页验证实际音频流，确认顺畅后再去系统设为默认。',
                onTap: () => ref
                    .read(navIndexProvider.notifier)
                    .goDestination(
                      onlineReady ? AppDestination.sample : AppDestination.online,
                      AppWorkMode.onlineOnly,
                    ),
              ),
            ],
          ),
        ),
        PrimaryButton(
          label: onlineReady ? '开始示例朗读' : '去配置在线引擎',
          icon: onlineReady ? Icons.play_arrow_rounded : Icons.cloud_outlined,
          onPressed: () => ref.read(navIndexProvider.notifier).goDestination(
                onlineReady ? AppDestination.sample : AppDestination.online,
                AppWorkMode.onlineOnly,
              ),
        ),
      ],
    );
  }

  Widget _buildLocalOnlySection(
    BuildContext context,
    WidgetRef ref,
    EngineStatus? status,
    bool hasLocalModel,
    String? activeModelLabel,
  ) {
    final speakers = status?.speakers ?? 0;
    return Column(
      children: [
        SectionCard(
          title: '纯离线运行状态',
          subtitle: '本模式下彻底断网，全部合成由端侧推理完成。',
          child: Column(
            children: [
              KeyValueRow(
                label: '引擎状态',
                value: status == null
                    ? '读取中…'
                    : engineStateLabel(status.state),
                valueColor: status?.state == EngineState.ready
                    ? Tokens.accent
                    : (hasLocalModel ? Tokens.text : Tokens.danger),
              ),
              KeyValueRow(
                label: '当前模型',
                value: activeModelLabel ?? (hasLocalModel ? '已安装（待激活）' : '尚未安装'),
              ),
              KeyValueRow(
                label: '本地音色数',
                value: speakers > 0 ? '$speakers 个' : '待加载模型后显示',
              ),
            ],
          ),
        ),
        PrimaryButton(
          label: hasLocalModel ? '去示例朗读' : '下载本地语音模型（推荐 Matcha）',
          icon: hasLocalModel
              ? Icons.play_arrow_rounded
              : Icons.download_rounded,
          onPressed: () => ref.read(navIndexProvider.notifier).goDestination(
                hasLocalModel
                    ? AppDestination.sample
                    : AppDestination.localModels,
                AppWorkMode.localOnly,
              ),
        ),
      ],
    );
  }

  Widget _buildHybridSection(
    BuildContext context,
    WidgetRef ref,
    RouteModeState route,
    EngineStatus? status,
    AppConfig? config,
    bool onlineReady,
    bool hasLocalModel,
    String? activeModelLabel,
  ) {
    final preferOnline = route.prefersOnline;
    return Column(
      children: [
        SectionCard(
          title: '双引擎协作总览',
          subtitle: '主链路不可用或断网时自动平滑接续到备用链路，避免阅读中断。',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SegmentedButton<String>(
                showSelectedIcon: false,
                segments: const [
                  ButtonSegment(value: 'prefer_online', label: Text('在线优先')),
                  ButtonSegment(value: 'prefer_local', label: Text('本地优先')),
                ],
                selected: <String>{
                  preferOnline ? 'prefer_online' : 'prefer_local',
                },
                onSelectionChanged: (s) async {
                  await ref.read(routeModeProvider.notifier).setMode(s.first);
                },
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: _EngineMiniCard(
                      icon: Icons.cloud_outlined,
                      color: Tokens.cloud,
                      title: '在线引擎',
                      statusText: status == null
                          ? '读取中'
                          : onlineStateLabel(status.online),
                      ready: onlineReady,
                      onTap: () => ref
                          .read(navIndexProvider.notifier)
                          .goDestination(
                            AppDestination.online,
                            AppWorkMode.hybrid,
                          ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: _EngineMiniCard(
                      icon: Icons.memory_rounded,
                      color: Tokens.accent,
                      title: '本地引擎',
                      statusText: activeModelLabel ??
                          (hasLocalModel ? '已安装模型' : '未装模型'),
                      ready: hasLocalModel,
                      onTap: () => ref
                          .read(navIndexProvider.notifier)
                          .goDestination(
                            AppDestination.localModels,
                            AppWorkMode.hybrid,
                          ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
        PrimaryButton(
          label: (onlineReady || hasLocalModel) ? '开始示例朗读' : '去配置引擎或模型',
          icon: (onlineReady || hasLocalModel)
              ? Icons.play_arrow_rounded
              : Icons.tune_rounded,
          onPressed: () {
            final dest = (onlineReady || hasLocalModel)
                ? AppDestination.sample
                : (preferOnline
                    ? AppDestination.online
                    : AppDestination.localModels);
            ref
                .read(navIndexProvider.notifier)
                .goDestination(dest, AppWorkMode.hybrid);
          },
        ),
      ],
    );
  }

  Future<void> _openModeSwitch(
    BuildContext context,
    WidgetRef ref,
    String currentMode,
  ) async {
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

class _HeroOverviewCard extends StatelessWidget {
  const _HeroOverviewCard({
    required this.mode,
    required this.routeModeId,
    required this.status,
    required this.onlineReady,
    required this.hasLocalModel,
    required this.activeModelLabel,
    required this.onSwitchMode,
  });

  final AppWorkMode mode;
  final String routeModeId;
  final EngineStatus? status;
  final bool onlineReady;
  final bool hasLocalModel;
  final String? activeModelLabel;
  final VoidCallback onSwitchMode;

  @override
  Widget build(BuildContext context) {
    final (Color accent, IconData icon, String title, String subtitle) =
        switch (mode) {
      AppWorkMode.onlineOnly => (
          Tokens.cloud,
          Icons.cloud_done_outlined,
          '当前：纯在线模式',
          onlineReady
              ? '在线语音已就绪，可直接朗读小说或供第三方阅读软件调用。'
              : '仅保留在线配置页；填好密钥并打开总开关即可开始朗读，无需本地模型。',
        ),
      AppWorkMode.localOnly => (
          Tokens.accent,
          Icons.shield_outlined,
          '当前：纯本地模式',
          hasLocalModel
              ? '端侧模型已安装（${activeModelLabel ?? '可用'}），全程离线运行、文本零外发。'
              : '请先前往「模型」页下载一个端侧模型（推荐 Matcha 中英轻量，更快更自然）。',
        ),
      AppWorkMode.hybrid => (
          Tokens.hybrid,
          Icons.merge_type_rounded,
          '当前：${routeModeLabel(routeModeId)}',
          '同时支持在线多引擎与本地端侧合成；主路径遇阻时自动切换备用引擎。',
        ),
    };

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
                child: Icon(icon, color: accent, size: 22),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  title,
                  style: const TextStyle(
                    fontSize: 17,
                    fontWeight: FontWeight.w800,
                    color: Tokens.text,
                  ),
                ),
              ),
              TextButton.icon(
                onPressed: onSwitchMode,
                icon: const Icon(Icons.swap_horiz_rounded, size: 17),
                label: const Text('换模式'),
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

class _EngineMiniCard extends StatelessWidget {
  const _EngineMiniCard({
    required this.icon,
    required this.color,
    required this.title,
    required this.statusText,
    required this.ready,
    required this.onTap,
  });

  final IconData icon;
  final Color color;
  final String title;
  final String statusText;
  final bool ready;
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
            color: ready ? color.withValues(alpha: 0.5) : Tokens.divider,
          ),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, size: 18, color: color),
                const SizedBox(width: 6),
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 13.5,
                    fontWeight: FontWeight.w700,
                    color: Tokens.text,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              statusText,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 12.5,
                fontWeight: FontWeight.w600,
                color: ready ? color : Tokens.textDim,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
