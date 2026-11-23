import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';

const String _previewText = '这是一段试听样音，用来感受这个音色的清晰度与节奏。';

enum _VoiceFilter { all, zh, en }

/// 本地音色库页：筛选中文/英文音色；支持单点「试听（不改旁白）」与「设旁白」。
class VoicesPage extends ConsumerStatefulWidget {
  const VoicesPage({super.key});

  @override
  ConsumerState<VoicesPage> createState() => _VoicesPageState();
}

class _VoicesPageState extends ConsumerState<VoicesPage> {
  _VoiceFilter _filter = _VoiceFilter.all;

  @override
  Widget build(BuildContext context) {
    final voices = ref.watch(voicesProvider);
    final preview = ref.watch(previewProvider);

    final list = voices.value ?? const <VoiceInfo>[];
    final zh = list.where((v) => v.lang == 'zh').toList();
    final en = list.where((v) => v.lang != 'zh').toList();
    final narrator = list.where((v) => v.isNarrator).toList();

    return PageBody(
      children: [
        SectionCard(
          title: '当前旁白',
          subtitle: narrator.isEmpty
              ? '尚未选择（默认使用第一个中文音色）；试听不会改变旁白设置。'
              : '正在使用「${narrator.first.name}」。试听样音不会覆盖你的全局旁白选择。',
          trailing: narrator.isEmpty
              ? null
              : StatusChip(label: narrator.first.name, color: Tokens.accent),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (preview.playing) ...[
                InfoBanner(
                  title: previewStageLabel(preview.stage),
                  body: '${_nameOf(list, preview.voiceId)}（旁白未改动）',
                  color: Tokens.accent,
                  icon: Icons.graphic_eq_rounded,
                  action: TextButton.icon(
                    onPressed: () =>
                        ref.read(previewProvider.notifier).stop(),
                    icon: const Icon(Icons.stop_rounded, size: 17),
                    label: const Text('停止试听'),
                  ),
                ),
                const SizedBox(height: 10),
              ] else if (preview.lastError != null &&
                  preview.lastError!.isNotEmpty) ...[
                InfoBanner(
                  title: '上次试听未成功',
                  body: preview.lastError!,
                  color: Tokens.danger,
                  icon: Icons.error_outline_rounded,
                  action: TextButton(
                    onPressed: () =>
                        ref.read(previewProvider.notifier).clearError(),
                    child: const Text('知道了'),
                  ),
                ),
                const SizedBox(height: 10),
              ],
              Wrap(
                spacing: 8,
                children: [
                  ChoiceChip(
                    label: Text('全部 (${list.length})'),
                    selected: _filter == _VoiceFilter.all,
                    onSelected: (_) =>
                        setState(() => _filter = _VoiceFilter.all),
                  ),
                  ChoiceChip(
                    label: Text('中文 (${zh.length})'),
                    selected: _filter == _VoiceFilter.zh,
                    onSelected: (_) =>
                        setState(() => _filter = _VoiceFilter.zh),
                  ),
                  if (en.isNotEmpty)
                    ChoiceChip(
                      label: Text('英文 (${en.length})'),
                      selected: _filter == _VoiceFilter.en,
                      onSelected: (_) =>
                          setState(() => _filter = _VoiceFilter.en),
                    ),
                ],
              ),
            ],
          ),
        ),
        if (voices.isLoading && !voices.hasValue)
          const SectionCard(
            child: Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: CircularProgressIndicator(),
              ),
            ),
          )
        else ...[
          if (_filter == _VoiceFilter.all || _filter == _VoiceFilter.zh) ...[
            if (zh.isNotEmpty) ...[
              const SectionHeader('中文音色（优先）'),
              for (final v in zh)
                _VoiceRow(
                  voice: v,
                  previewing: preview.playing && preview.voiceId == v.id,
                ),
            ],
          ],
          if (_filter == _VoiceFilter.en) ...[
            const SectionHeader('英文音色'),
            for (final v in en)
              _VoiceRow(
                voice: v,
                previewing: preview.playing && preview.voiceId == v.id,
              ),
          ] else if (_filter == _VoiceFilter.all && en.isNotEmpty)
            _EnglishSection(voices: en, preview: preview),
        ],
      ],
    );
  }

  static String _nameOf(List<VoiceInfo> list, String? id) {
    if (id == null) return '旁白';
    for (final v in list) {
      if (v.id == id) return v.name;
    }
    return id;
  }
}

class _EnglishSection extends StatelessWidget {
  const _EnglishSection({required this.voices, required this.preview});

  final List<VoiceInfo> voices;
  final PreviewState preview;

  @override
  Widget build(BuildContext context) {
    final narratorInEn = voices.any((v) => v.isNarrator);
    return Container(
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
            initiallyExpanded: narratorInEn,
            tilePadding: const EdgeInsets.symmetric(horizontal: 16),
            childrenPadding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            iconColor: Tokens.accent,
            collapsedIconColor: Tokens.textDim,
            title: const Text(
              '英文音色（点击展开）',
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w600,
                color: Tokens.text,
              ),
            ),
            subtitle: Text(
              '共 ${voices.length} 个，默认折叠',
              style: const TextStyle(fontSize: 12, color: Tokens.textDim),
            ),
            children: [
              for (final v in voices)
                _VoiceRow(
                  voice: v,
                  previewing: preview.playing && preview.voiceId == v.id,
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _VoiceRow extends ConsumerWidget {
  const _VoiceRow({
    required this.voice,
    required this.previewing,
  });

  final VoiceInfo voice;
  final bool previewing;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: voice.isNarrator
            ? Tokens.accent.withValues(alpha: 0.08)
            : Tokens.surface,
        borderRadius: BorderRadius.circular(Tokens.radiusCard),
        border: Border.all(
          color: voice.isNarrator ? Tokens.accent : Tokens.divider,
          width: voice.isNarrator ? 1.4 : 1,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  voice.name,
                  style: const TextStyle(
                    fontSize: 14.5,
                    fontWeight: FontWeight.w700,
                    color: Tokens.text,
                  ),
                ),
              ),
              if (voice.isNarrator)
                const StatusChip(label: '当前旁白', color: Tokens.accent),
            ],
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: previewing
                    ? SecondaryButton(
                        label: '停止',
                        icon: Icons.stop_rounded,
                        danger: true,
                        onPressed: () =>
                            ref.read(previewProvider.notifier).stop(),
                      )
                    : SecondaryButton(
                        label: '试听',
                        icon: Icons.play_arrow_rounded,
                        onPressed: () async {
                          await ref.read(previewProvider.notifier).preview(
                                text: _previewText,
                                voiceId: voice.id,
                              );
                          if (context.mounted) {
                            showAppSnack(
                              context,
                              '正在试听：${voice.name}（未更改全局旁白）',
                            );
                          }
                        },
                      ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: PrimaryButton(
                  label: voice.isNarrator ? '已是旁白' : '设旁白',
                  icon: voice.isNarrator
                      ? Icons.check_rounded
                      : Icons.record_voice_over_outlined,
                  onPressed: voice.isNarrator
                      ? null
                      : () async {
                          await ref
                              .read(engineServiceProvider)
                              .setNarratorVoice(voice.id);
                          if (context.mounted) {
                            showAppSnack(context, '旁白已设为：${voice.name}');
                          }
                        },
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
