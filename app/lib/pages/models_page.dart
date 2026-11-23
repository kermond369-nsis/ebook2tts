import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../platform/system_bridge.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';

/// 本地语音模型管理页：支持下拉镜像选源、多源并行测速、断点续传下载与取消、以及 SAF 本地文件导入。
class ModelsPage extends ConsumerStatefulWidget {
  const ModelsPage({super.key});

  @override
  ConsumerState<ModelsPage> createState() => _ModelsPageState();
}

class _ModelsPageState extends ConsumerState<ModelsPage> {
  final TextEditingController _mirror = TextEditingController();
  bool _mirrorReady = false;
  String? _importingModelId;

  @override
  void dispose() {
    _mirror.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final models = ref.watch(modelsProvider);
    final progress = ref.watch(downloadProgressProvider);
    final downloadErr = ref.watch(lastDownloadErrorProvider);
    final mirrorCfg =
        ref.watch(mirrorConfigProvider).value ?? MirrorConfigState.defaults();
    final probeState = ref.watch(mirrorProbeProvider);
    final config = ref.watch(configProvider).value;

    if (!_mirrorReady) {
      final initial = mirrorCfg.customUrl.isNotEmpty
          ? mirrorCfg.customUrl
          : (config?.customMirror ?? '');
      if (initial.isNotEmpty || config != null) {
        _mirror.text = initial;
        _mirrorReady = true;
      }
    }

    final selectedId = mirrorCfg.presets.any((p) => p.id == mirrorCfg.selected)
        ? mirrorCfg.selected
        : mirrorCfg.presets.first.id;

    return PageBody(
      children: [
        const SectionCard(
          title: '端侧语音模型',
          subtitle: '支持断点续传、下拉切换加速镜像与本地导入。推荐优先使用 Matcha（中英轻量更流畅）或 Kokoro int8（103 音色）。',
          child: SizedBox.shrink(),
        ),
        _MirrorSelectorCard(
          selectedId: selectedId,
          mirrorCfg: mirrorCfg,
          probeState: probeState,
          mirrorController: _mirror,
          busy: progress != null || _importingModelId != null,
          onSelect: (id) async {
            await ref.read(mirrorConfigProvider.notifier).setSelection(id);
            if (!context.mounted) return;
            final label = mirrorCfg.presets
                .firstWhere(
                  (p) => p.id == id,
                  orElse: () => mirrorCfg.presets.first,
                )
                .label;
            showAppSnack(context, '已切换下载源：$label');
          },
          onProbe: () async {
            await ref.read(mirrorProbeProvider.notifier).run(
                  customUrl: _mirror.text.trim(),
                );
          },
          onPickFastest: (best) async {
            await ref.read(mirrorConfigProvider.notifier).setSelection(best.id);
            if (!context.mounted) return;
            showAppSnack(
              context,
              '已选用最快镜像：${best.label}（${best.shortSummary}）',
            );
          },
          onSaveCustom: () async {
            final url = _mirror.text.trim();
            if (url.isNotEmpty && !url.startsWith('https://')) {
              showAppSnack(context, '镜像地址必须以 https:// 开头', danger: true);
              return;
            }
            await ref.read(mirrorConfigProvider.notifier).saveCustomUrl(url);
            await ref
                .read(configProvider.notifier)
                .save(customMirror: url);
            if (!context.mounted) return;
            showAppSnack(
              context,
              url.isEmpty ? '已清空自定义地址并恢复自动选源' : '自定义镜像已保存并设为优先源',
            );
          },
        ),
        if (downloadErr != null && downloadErr.isNotEmpty) ...[
          InfoBanner(
            title: '上次下载 / 导入未完成',
            body: downloadErr,
            color: Tokens.danger,
            icon: Icons.error_outline_rounded,
            action: TextButton(
              onPressed: () =>
                  ref.read(lastDownloadErrorProvider.notifier).clear(),
              child: const Text('知道了'),
            ),
          ),
          const SizedBox(height: 12),
        ],
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
            _ModelCard(
              model: m,
              progress: progress,
              importingThis: _importingModelId == m.id,
              anyImporting: _importingModelId != null,
              onImport: () => _handleLocalImport(context, m),
            ),
      ],
    );
  }

  Future<void> _handleLocalImport(
    BuildContext context,
    ModelInfo model,
  ) async {
    setState(() => _importingModelId = model.id);
    ref.read(lastDownloadErrorProvider.notifier).clear();
    final res = await SystemBridge.importLocalModel(model.id);
    if (!mounted) return;
    setState(() => _importingModelId = null);
    if (res.cancelled) {
      showAppSnack(context, '已取消选择文件');
      return;
    }
    if (res.ok) {
      ref.invalidate(modelsProvider);
      ref.invalidate(statusProvider);
      ref.invalidate(voicesProvider);
      showAppSnack(
        context,
        res.message.isNotEmpty ? res.message : '导入成功并已设为当前模型：${model.label}',
      );
    } else {
      final msg = res.message.isNotEmpty ? res.message : '导入失败，请确认压缩包与当前模型匹配';
      ref.read(lastDownloadErrorProvider.notifier).setError(msg);
      showAppSnack(context, msg, danger: true);
    }
  }
}

class _MirrorSelectorCard extends StatelessWidget {
  const _MirrorSelectorCard({
    required this.selectedId,
    required this.mirrorCfg,
    required this.probeState,
    required this.mirrorController,
    required this.busy,
    required this.onSelect,
    required this.onProbe,
    required this.onPickFastest,
    required this.onSaveCustom,
  });

  final String selectedId;
  final MirrorConfigState mirrorCfg;
  final MirrorProbeState probeState;
  final TextEditingController mirrorController;
  final bool busy;
  final Future<void> Function(String id) onSelect;
  final Future<void> Function() onProbe;
  final Future<void> Function(MirrorProbeResult best) onPickFastest;
  final Future<void> Function() onSaveCustom;

  @override
  Widget build(BuildContext context) {
    final best = probeState.fastestOk;
    return SectionCard(
      title: '下载源与镜像测速',
      subtitle: '可直接在下拉列表中指定国内镜像，或先一键测速选择最快通道（失败时自动依次回退）。',
      trailing: selectedId == 'auto'
          ? const StatusChip(label: '自动选优', color: Tokens.cloud)
          : const StatusChip(label: '已手动指定', color: Tokens.accent),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          InputDecorator(
            decoration: const InputDecoration(
              labelText: '选择下载镜像源',
              contentPadding: EdgeInsets.symmetric(horizontal: 14, vertical: 6),
            ),
            child: DropdownButtonHideUnderline(
              child: DropdownButton<String>(
                value: selectedId,
                isExpanded: true,
                dropdownColor: Tokens.surfaceAlt,
                items: [
                  for (final p in mirrorCfg.presets)
                    DropdownMenuItem<String>(
                      value: p.id,
                      child: _PresetDropdownRow(
                        label: p.label,
                        probe: probeState.resultFor(p.id),
                      ),
                    ),
                ],
                onChanged: busy
                    ? null
                    : (v) {
                        if (v != null) onSelect(v);
                      },
              ),
            ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: SecondaryButton(
                  label: probeState.probing ? '正在并发测速…' : '一键测速',
                  icon: Icons.speed_rounded,
                  onPressed: probeState.probing ? null : onProbe,
                ),
              ),
              if (best != null) ...[
                const SizedBox(width: 10),
                Expanded(
                  child: PrimaryButton(
                    label: '采用最快节点',
                    icon: Icons.flash_on_rounded,
                    onPressed: busy ? null : () => onPickFastest(best),
                  ),
                ),
              ],
            ],
          ),
          if (probeState.results.isNotEmpty) ...[
            const SizedBox(height: 10),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: Tokens.background,
                borderRadius: BorderRadius.circular(Tokens.radiusButton),
                border: Border.all(color: Tokens.divider),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    '镜像测速结果（64KB 实测吞吐）',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: Tokens.textDim,
                    ),
                  ),
                  const SizedBox(height: 6),
                  for (final r in probeState.results)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 2),
                      child: Row(
                        children: [
                          Icon(
                            r.ok ? Icons.check_circle_outline : Icons.cancel_outlined,
                            size: 14,
                            color: r.ok ? Tokens.accent : Tokens.danger,
                          ),
                          const SizedBox(width: 6),
                          Expanded(
                            child: Text(
                              r.label,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 12,
                                color: Tokens.text,
                              ),
                            ),
                          ),
                          Text(
                            r.shortSummary,
                            style: TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                              color: r.ok ? Tokens.accent : Tokens.danger,
                            ),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ),
          ],
          if (selectedId == 'custom') ...[
            const SizedBox(height: 12),
            TextField(
              controller: mirrorController,
              decoration: const InputDecoration(
                labelText: '自定义镜像前缀 / 目录基址 / 直链（HTTPS）',
                hintText: '如 https://gh-proxy.com 或完整 .tar.bz2 直链',
              ),
            ),
            const SizedBox(height: 10),
            PrimaryButton(
              label: '保存自定义镜像',
              icon: Icons.save_outlined,
              onPressed: onSaveCustom,
            ),
          ],
        ],
      ),
    );
  }
}

class _PresetDropdownRow extends StatelessWidget {
  const _PresetDropdownRow({required this.label, this.probe});

  final String label;
  final MirrorProbeResult? probe;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Text(
            label,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 13.5, color: Tokens.text),
          ),
        ),
        if (probe != null) ...[
          const SizedBox(width: 8),
          Text(
            probe!.shortSummary,
            style: TextStyle(
              fontSize: 11.5,
              fontWeight: FontWeight.w600,
              color: probe!.ok ? Tokens.accent : Tokens.danger,
            ),
          ),
        ],
      ],
    );
  }
}

class _ModelCard extends ConsumerWidget {
  const _ModelCard({
    required this.model,
    required this.progress,
    required this.importingThis,
    required this.anyImporting,
    required this.onImport,
  });

  final ModelInfo model;
  final DownloadProgress? progress;
  final bool importingThis;
  final bool anyImporting;
  final VoidCallback onImport;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final active = progress != null && progress!.modelId == model.id;
    final anyDownload = progress != null || anyImporting;
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

    final isIndeterminate = active &&
        const {'probe', 'verify', 'extract', 'promote', 'import'}
            .contains(progress!.phase);

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
              if (progress?.sourceLabel != null &&
                  progress!.sourceLabel!.isNotEmpty &&
                  active)
                _MetaTag(
                  icon: Icons.dns_outlined,
                  text: '当前通道：${progress!.sourceLabel}',
                ),
            ],
          ),
          const SizedBox(height: 12),
          if (active) ...[
            LinearProgressIndicator(
              value: isIndeterminate ? null : progress!.fraction,
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
              onPressed: () async {
                await ref.read(engineServiceProvider).cancelDownload();
                if (context.mounted) {
                  showAppSnack(context, '已停止下载，已下载分片进度已保留');
                }
              },
            ),
          ] else if (importingThis) ...[
            const LinearProgressIndicator(),
            const SizedBox(height: 8),
            const Text(
              '正在校验并解压本地导入的模型文件，请稍候…',
              style: TextStyle(
                fontSize: 13,
                color: Tokens.accent,
                fontWeight: FontWeight.w600,
              ),
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
          else ...[
            Row(
              children: [
                Expanded(
                  flex: 3,
                  child: PrimaryButton(
                    label: '下载并安装',
                    icon: Icons.download_rounded,
                    onPressed: anyDownload
                        ? null
                        : () {
                            ref
                                .read(lastDownloadErrorProvider.notifier)
                                .clear();
                            ref
                                .read(engineServiceProvider)
                                .startDownload(model.id);
                          },
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  flex: 2,
                  child: SecondaryButton(
                    label: '从本地导入',
                    icon: Icons.folder_open_rounded,
                    onPressed: anyDownload ? null : onImport,
                  ),
                ),
              ],
            ),
            if (isMatcha)
              const Padding(
                padding: EdgeInsets.only(top: 6),
                child: Text(
                  '提示：本地导入需同时包含或先导入主包（.tar.bz2）与声码器（vocos-16khz-univ.onnx），亦可先导主包再点「下载并安装」自动补齐声码器。',
                  style: TextStyle(
                    fontSize: 11.5,
                    color: Tokens.textDim,
                    height: 1.45,
                  ),
                ),
              ),
          ],
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
