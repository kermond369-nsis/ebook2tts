import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';

/// 本地语音模型管理页：突出 Matcha 流畅轻量与 Kokoro 多音色；自定义镜像折叠收纳。
class ModelsPage extends ConsumerStatefulWidget {
  const ModelsPage({super.key});

  @override
  ConsumerState<ModelsPage> createState() => _ModelsPageState();
}

class _ModelsPageState extends ConsumerState<ModelsPage> {
  final TextEditingController _mirror = TextEditingController();
  bool _mirrorReady = false;

  @override
  void dispose() {
    _mirror.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final models = ref.watch(modelsProvider);
    final progress = ref.watch(downloadProgressProvider);
    final config = ref.watch(configProvider).value;

    if (!_mirrorReady && config != null) {
      _mirror.text = config.customMirror;
      _mirrorReady = true;
    }

    return PageBody(
      children: [
        const SectionCard(
          title: '端侧语音模型',
          subtitle: '支持断点续传。推荐优先下载 Matcha（更轻量、句间更快），或选 Kokoro int8 获得上百音色。',
          child: SizedBox.shrink(),
        ),
        if (models.isLoading && !models.hasValue)
          const SectionCard(
            child: Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: CircularProgressIndicator(),
              ),
            ),
          )
        else
          for (final m in models.value ?? const <ModelInfo>[])
            _ModelCard(model: m, progress: progress),
        Container(
          margin: const EdgeInsets.only(bottom: Tokens.cardGap),
          decoration: BoxDecoration(
            color: Tokens.surface,
            borderRadius: BorderRadius.circular(Tokens.radiusCard),
            border: Border.all(color: Tokens.divider),
          ),
          child: Material(
            type: MaterialType.transparency,
            child: Theme(
              data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
              child: ExpansionTile(
                tilePadding: const EdgeInsets.symmetric(horizontal: 16),
                childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                iconColor: Tokens.accent,
                collapsedIconColor: Tokens.textDim,
                title: const Text(
                  '高级：自定义下载镜像',
                  style: TextStyle(
                    fontSize: 14.5,
                    fontWeight: FontWeight.w600,
                    color: Tokens.text,
                  ),
                ),
                subtitle: const Text(
                  '默认内置多个国内高速镜像，网络不通畅时可手动覆盖',
                  style: TextStyle(fontSize: 12, color: Tokens.textDim),
                ),
                children: [
                  TextField(
                    controller: _mirror,
                    decoration: const InputDecoration(
                      hintText: 'https://你的镜像地址/模型目录（留空恢复官方测速）',
                    ),
                  ),
                  const SizedBox(height: 10),
                  PrimaryButton(
                    label: '保存镜像设置',
                    icon: Icons.save_outlined,
                    onPressed: () async {
                      await ref
                          .read(configProvider.notifier)
                          .save(customMirror: _mirror.text.trim());
                      if (context.mounted) {
                        showAppSnack(
                          context,
                          _mirror.text.trim().isEmpty
                              ? '已恢复官方与内置加速镜像'
                              : '自定义镜像已保存，下载将优先使用',
                        );
                      }
                    },
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _ModelCard extends ConsumerWidget {
  const _ModelCard({required this.model, required this.progress});

  final ModelInfo model;
  final DownloadProgress? progress;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final active = progress != null && progress!.modelId == model.id;
    final anyDownload = progress != null;
    final isMatcha = model.id == 'matcha-zh-en';

    final Widget? badge = model.active
        ? const StatusChip(label: '使用中', color: Tokens.accent)
        : model.installed
            ? const StatusChip(label: '已安装', color: Tokens.cloud)
            : isMatcha
                ? const StatusChip(label: '流畅推荐', color: Tokens.accent)
                : model.recommended
                    ? const StatusChip(label: '多音色主推', color: Tokens.hybrid)
                    : null;

    return SectionCard(
      title: model.label,
      subtitle: model.note,
      borderColor: model.active
          ? Tokens.accent.withValues(alpha: 0.65)
          : Tokens.divider,
      trailing: badge,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 8,
            runSpacing: 6,
            children: [
              _MetaTag(
                icon: Icons.download_outlined,
                text: '下载 ${formatMb(model.downloadBytes)}',
              ),
              _MetaTag(
                icon: Icons.unarchive_outlined,
                text: '解压 ${formatMb(model.extractedBytes)}',
              ),
            ],
          ),
          const SizedBox(height: 12),
          if (active) ...[
            LinearProgressIndicator(
              value: progress!.phase == 'probe' ? null : progress!.fraction,
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: Text(
                    '${phaseLabel(progress!.phase)}'
                    '${(progress!.phase == 'download' || progress!.phase == 'download_vocoder') ? '（${(progress!.fraction * 100).toStringAsFixed(0)}%）' : '…'}',
                    style: const TextStyle(
                      fontSize: 13,
                      color: Tokens.accent,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                Text(
                  progress!.total <= 0
                      ? ''
                      : '${(progress!.received / 1000000).toStringAsFixed(1)} / '
                          '${(progress!.total / 1000000).toStringAsFixed(1)} MB',
                  style: const TextStyle(fontSize: 12, color: Tokens.textDim),
                ),
              ],
            ),
            const SizedBox(height: 10),
            SecondaryButton(
              label: '取消下载',
              icon: Icons.close_rounded,
              danger: true,
              onPressed: () =>
                  ref.read(engineServiceProvider).cancelDownload(),
            ),
          ] else if (model.active)
            const PrimaryButton(
              label: '正在使用该模型',
              icon: Icons.check_circle_outline_rounded,
              onPressed: null,
            )
          else if (model.installed)
            Row(
              children: [
                Expanded(
                  child: PrimaryButton(
                    label: '切换为当前模型',
                    icon: Icons.play_circle_outline_rounded,
                    onPressed: () => _setActive(context, ref),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: SecondaryButton(
                    label: '删除',
                    icon: Icons.delete_outline_rounded,
                    danger: true,
                    onPressed: () => _confirmDelete(context, ref),
                  ),
                ),
              ],
            )
          else
            PrimaryButton(
              label: '下载并安装',
              icon: Icons.download_rounded,
              onPressed: anyDownload
                  ? null
                  : () =>
                      ref.read(engineServiceProvider).startDownload(model.id),
            ),
        ],
      ),
    );
  }

  Future<void> _setActive(BuildContext context, WidgetRef ref) async {
    await ref.read(engineServiceProvider).setActiveModel(model.id);
    if (context.mounted) {
      showAppSnack(context, '已设为使用中：${model.label}');
    }
  }

  Future<void> _confirmDelete(BuildContext context, WidgetRef ref) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除模型', style: TextStyle(fontSize: 16)),
        content: Text(
          '将删除「${model.label}」的本机文件（约 ${formatMb(model.extractedBytes)}）。删除后需重新下载。',
          style: const TextStyle(fontSize: 13.5, height: 1.5, color: Tokens.text),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            style: FilledButton.styleFrom(
              backgroundColor: Tokens.danger,
              foregroundColor: Tokens.text,
            ),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (ok == true) {
      await ref.read(engineServiceProvider).deleteModel(model.id);
      if (context.mounted) {
        showAppSnack(context, '已删除：${model.label}');
      }
    }
  }
}

class _MetaTag extends StatelessWidget {
  const _MetaTag({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: Tokens.surfaceAlt,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 14, color: Tokens.textDim),
          const SizedBox(width: 5),
          Text(
            text,
            style: const TextStyle(fontSize: 12, color: Tokens.textDim),
          ),
        ],
      ),
    );
  }
}
