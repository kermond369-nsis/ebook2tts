import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../platform/system_bridge.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';

/// 引导页（IM-301）：三步说明 + 当前状态 + 主按钮「去下载模型」。
class GuidePage extends ConsumerWidget {
  const GuidePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = ref.watch(statusProvider).value;
    final models = ref.watch(modelsProvider).value ?? const [];
    final hasModel = models.any((m) => m.installed) ||
        (status != null && status.state.name == 'ready');

    return PageBody(
      children: [
        SectionCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: Tokens.accent,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: const Icon(
                      Icons.auto_stories,
                      color: Tokens.onAccent,
                      size: 26,
                    ),
                  ),
                  const SizedBox(width: 12),
                  const Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '书声本地',
                          style: TextStyle(
                            fontSize: 20,
                            fontWeight: FontWeight.w700,
                            color: Tokens.text,
                          ),
                        ),
                        SizedBox(height: 2),
                        Text(
                          '离线电子书朗读，文本不出本机',
                          style: TextStyle(fontSize: 13, color: Tokens.textDim),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              const Text(
                '把电子书丢进来，用本机语音模型念给你听。默认全程离线；'
                '在线朗读是可选增强，默认关闭，开启前会单独征求你的同意。',
                style: TextStyle(
                  fontSize: 13.5,
                  color: Tokens.text,
                  height: 1.6,
                ),
              ),
            ],
          ),
        ),
        const _StepCard(
          step: '第一步',
          title: '离线优先，隐私放心',
          body: '全部朗读默认由本机模型完成，不联网、不上传。'
              '只有你主动打开「在线朗读」并把开关保持开启，文本才会发送给第三方服务商。',
          icon: Icons.shield_outlined,
        ),
        const _StepCard(
          step: '第二步',
          title: '下载一个语音模型',
          body: '三档模型任选：最小的一百多兆，主推档音色最丰富。'
              '下载支持断点续传与自定义镜像，装好即可离线朗读。',
          icon: Icons.download_for_offline_outlined,
        ),
        const _StepCard(
          step: '第三步',
          title: '设为系统朗读',
          body: '在系统的「文字转语音」设置里把「书声本地」设为默认，'
              '其他阅读软件也能直接调用本机朗读。',
          icon: Icons.settings_voice_outlined,
        ),
        SectionCard(
          title: '当前状态',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              KeyValueRow(
                label: '引擎',
                value: status == null
                    ? '正在读取…'
                    : engineStateLabel(status.state),
                valueColor: (status != null && status.state.name == 'ready')
                    ? Tokens.accent
                    : Tokens.danger,
              ),
              KeyValueRow(
                label: '使用中的模型',
                value: _modelLabel(models, status?.modelId),
              ),
              KeyValueRow(
                label: '可用音色',
                value: status == null || status.speakers == 0
                    ? '待模型安装后显示'
                    : '${status.speakers} 个',
              ),
              KeyValueRow(
                label: '在线朗读',
                value: status == null ? '正在读取…' : onlineStateLabel(status.online),
              ),
            ],
          ),
        ),
        PrimaryButton(
          label: hasModel ? '开始示例朗读' : '去下载模型',
          icon: hasModel ? Icons.play_arrow : Icons.download,
          onPressed: () =>
              ref.read(navIndexProvider.notifier).go(hasModel ? 3 : 1),
        ),
        const SizedBox(height: 10),
        SecondaryButton(
          label: '打开系统朗读设置',
          icon: Icons.open_in_new,
          onPressed: () => _openSystemSettings(context),
        ),
        const SizedBox(height: 10),
        const Text(
          '没有模型也能先逛逛：模型页可下载，音色页可试听，'
          '设置页可开关在线朗读与数据流量。',
          style: TextStyle(fontSize: 12.5, color: Tokens.textDim, height: 1.5),
        ),
      ],
    );
  }

  String _modelLabel(List<ModelInfo> models, String? modelId) {
    if (modelId == null) {
      return '尚未安装';
    }
    for (final m in models) {
      if (m.id == modelId) {
        return m.label;
      }
    }
    return modelId;
  }

  Future<void> _openSystemSettings(BuildContext context) async {
    final result = await SystemBridge.openSystemTtsSettings();
    if (!context.mounted) return;
    switch (result) {
      case 'tts':
        showAppSnack(context, '已打开系统「文字转语音」设置，请选择「书声本地」');
      case 'settings':
        showAppSnack(context, '已打开系统设置，请手动进入「文字转语音」');
      default:
        showAppSnack(context, '未能打开系统设置，请手动前往系统设置中的「文字转语音」',
            danger: true);
    }
  }
}

class _StepCard extends StatelessWidget {
  const _StepCard({
    required this.step,
    required this.title,
    required this.body,
    required this.icon,
  });

  final String step;
  final String title;
  final String body;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return SectionCard(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 38,
            height: 38,
            decoration: BoxDecoration(
              color: Tokens.surfaceAlt,
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(icon, size: 20, color: Tokens.accent),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  step,
                  style: const TextStyle(
                    fontSize: 11.5,
                    fontWeight: FontWeight.w700,
                    color: Tokens.accent,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: Tokens.text,
                  ),
                ),
                const SizedBox(height: 5),
                Text(
                  body,
                  style: const TextStyle(
                    fontSize: 13,
                    color: Tokens.textDim,
                    height: 1.55,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
