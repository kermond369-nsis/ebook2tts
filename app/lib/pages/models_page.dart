import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';

/// 模型管理页（IM-302）：三档卡片 + 假进度（探测→下载→校验→解压→收尾）+ 自定义镜像。
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
        SectionCard(
          title: '下载镜像（可选）',
          subtitle: '留空使用官方镜像。网络不畅时可填入自建镜像地址，下载会优先走这里。',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TextField(
                controller: _mirror,
                decoration: const InputDecoration(
                  hintText: '例如 https://你的镜像地址/模型目录',
                ),
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  Expanded(
                    child: PrimaryButton(
                      label: '保存镜像',
                      onPressed: () async {
                        await ref
                            .read(configProvider.notifier)
                            .save(customMirror: _mirror.text.trim());
                        if (context.mounted) {
                          showAppSnack(
                            context,
                            _mirror.text.trim().isEmpty
                                ? '已恢复使用官方镜像'
                                : '镜像已保存，下载将优先使用',
                          );
                        }
                      },
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
        if (models.isLoading && !models.hasValue)
          const SectionCard(
            child: Center(
              child: Padding(
                padding: EdgeInsets.all(18),
                child: CircularProgressIndicator(),
              ),
            ),
          )
        else
          for (final m in models.value ?? const <ModelInfo>[])
            _ModelCard(model: m, progress: progress),
        SectionCard(
          title: '说明',
          subtitle: '模型体积为实测值；下载支持断点续传。已安装的模型会显示「使用中」，'
              '不能重复下载；如需换用，先「设为使用中」或删除后重下。',
          child: const SizedBox.shrink(),
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

    return SectionCard(
      title: model.label,
      subtitle: model.note,
      trailing: model.active
          ? const StatusChip(label: '使用中')
          : model.installed
              ? const StatusChip(label: '已安装', color: Tokens.textDim)
              : model.recommended
                  ? const StatusChip(label: '主推')
                  : null,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              _MetaTag(
                icon: Icons.download_outlined,
                text: '下载 ${formatMb(model.downloadBytes)}',
              ),
              const SizedBox(width: 8),
              _MetaTag(
                icon: Icons.unarchive_outlined,
                text: '解压后 ${formatMb(model.extractedBytes)}',
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
                    '${progress!.phase == 'download' ? '（${(progress!.fraction * 100).toStringAsFixed(0)}%）' : '…'}',
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
                          '${(progress!.total / 1000000).toStringAsFixed(1)} 兆',
                  style: const TextStyle(fontSize: 12, color: Tokens.textDim),
                ),
              ],
            ),
            const SizedBox(height: 10),
            SecondaryButton(
              label: '取消下载',
              icon: Icons.close,
              danger: true,
              onPressed: () =>
                  ref.read(engineServiceProvider).cancelDownload(),
            ),
          ] else if (model.active)
            PrimaryButton(
              label: '使用中',
              icon: Icons.check_circle_outline,
              onPressed: null,
            )
          else if (model.installed)
            Row(
              children: [
                Expanded(
                  child: PrimaryButton(
                    label: '设为使用中',
                    icon: Icons.play_circle_outline,
                    onPressed: () => _setActive(context, ref),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: SecondaryButton(
                    label: '删除',
                    icon: Icons.delete_outline,
                    danger: true,
                    onPressed: () => _confirmDelete(context, ref),
                  ),
                ),
              ],
            )
          else
            Row(
              children: [
                Expanded(
                  child: PrimaryButton(
                    label: '下载',
                    icon: Icons.download,
                    onPressed: anyDownload
                        ? null
                        : () => ref
                            .read(engineServiceProvider)
                            .startDownload(model.id),
                  ),
                ),
                const SizedBox(width: 10),
                const Expanded(
                  child: SecondaryButton(
                    label: '删除',
                    icon: Icons.delete_outline,
                    danger: true,
                    onPressed: null,
                  ),
                ),
              ],
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
          '将删除「${model.label}」的本机文件（约 ${formatMb(model.extractedBytes)}）。'
          '删除后需要重新下载才能离线朗读。',
          style: const TextStyle(fontSize: 13.5, height: 1.5, color: Tokens.text),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            style: TextButton.styleFrom(foregroundColor: Tokens.textDim),
            child: const Text('取消'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            style: ElevatedButton.styleFrom(
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
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
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
