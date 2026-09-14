import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';

/// 试听样音文本（不改动全局旁白）。
const String _previewText = '这是一段试听样音，用来感受这个音色的音质与节奏。';

/// 音色库页（IM-303）：中文音色优先、英文折叠；每行「试听 / 设旁白」两个独立按钮。
class VoicesPage extends ConsumerWidget {
  const VoicesPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
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
              ? '尚未设置（默认使用第一个中文音色）'
              : '${narrator.first.name}。试听只播放样音、不会改动旁白；'
                  '要让某个音色成为全局旁白，请点它右侧的「设旁白」。',
          trailing: narrator.isEmpty
              ? null
              : const StatusChip(label: '旁白音色'),
          child: preview.playing
              ? Row(
                  children: [
                    const Icon(Icons.graphic_eq, size: 18, color: Tokens.accent),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '正在试听：${_nameOf(list, preview.voiceId)}'
                        '${preview.voiceId == null ? '' : ''}（旁白未改动）',
                        style: const TextStyle(
                          fontSize: 13,
                          color: Tokens.accent,
                        ),
                      ),
                    ),
                  ],
                )
              : const Text(
                  '提示：试听与「设旁白」互不影响，可放心对比音色。',
                  style: TextStyle(fontSize: 13, color: Tokens.textDim),
                ),
        ),
        if (voices.isLoading && !voices.hasValue)
          const SectionCard(
            child: Center(
              child: Padding(
                padding: EdgeInsets.all(18),
                child: CircularProgressIndicator(),
              ),
            ),
          )
        else ...[
          const _SectionTitle('中文音色（优先）'),
          for (final v in zh)
            _VoiceRow(
              voice: v,
              previewing: preview.playing && preview.voiceId == v.id,
              anyPreviewing: preview.playing,
            ),
          _EnglishSection(voices: en, preview: preview),
        ],
      ],
    );
  }

  static String _nameOf(List<VoiceInfo> list, String? id) {
    if (id == null) {
      return '旁白';
    }
    for (final v in list) {
      if (v.id == id) {
        return v.name;
      }
    }
    return id;
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 6, 4, 8),
      child: Text(
        text,
        style: const TextStyle(
          fontSize: 13,
          fontWeight: FontWeight.w700,
          color: Tokens.textDim,
        ),
      ),
    );
  }
}

/// 英文音色：默认折叠。
class _EnglishSection extends StatelessWidget {
  const _EnglishSection({required this.voices, required this.preview});

  final List<VoiceInfo> voices;
  final PreviewState preview;

  @override
  Widget build(BuildContext context) {
    if (voices.isEmpty) {
      return const SizedBox.shrink();
    }
    final narratorInEn = voices.any((v) => v.isNarrator);
    return Container(
      margin: const EdgeInsets.only(bottom: Tokens.cardGap),
      decoration: BoxDecoration(
        color: Tokens.surface,
        borderRadius: BorderRadius.circular(Tokens.radiusCard),
      ),
      child: Theme(
        data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
        child: ExpansionTile(
          initiallyExpanded: narratorInEn,
          tilePadding: const EdgeInsets.symmetric(horizontal: 14),
          childrenPadding: const EdgeInsets.fromLTRB(10, 0, 10, 10),
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
            '共 ${voices.length} 个，默认折叠；适合朗读英文书名或原文段落',
            style: const TextStyle(fontSize: 12.5, color: Tokens.textDim),
          ),
          children: [
            for (final v in voices)
              _VoiceRow(
                voice: v,
                previewing: preview.playing && preview.voiceId == v.id,
                anyPreviewing: preview.playing,
              ),
          ],
        ),
      ),
    );
  }
}

/// 单个音色行：名称 + 「试听」「设旁白」两个独立按钮。
class _VoiceRow extends ConsumerWidget {
  const _VoiceRow({
    required this.voice,
    required this.previewing,
    required this.anyPreviewing,
  });

  final VoiceInfo voice;
  final bool previewing;
  final bool anyPreviewing;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: BoxDecoration(
        color: Tokens.surface,
        borderRadius: BorderRadius.circular(Tokens.radiusCard),
        border: voice.isNarrator
            ? Border.all(color: Tokens.accent, width: 1.2)
            : null,
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
                    fontWeight: FontWeight.w600,
                    color: Tokens.text,
                  ),
                ),
              ),
              if (voice.isNarrator) const StatusChip(label: '当前旁白'),
            ],
          ),
          const SizedBox(height: 9),
          Row(
            children: [
              // 试听：只播放样音，绝不改动全局旁白
              Expanded(
                child: previewing
                    ? SecondaryButton(
                        label: '停止',
                        icon: Icons.stop,
                        onPressed: () =>
                            ref.read(previewProvider.notifier).stop(),
                      )
                    : SecondaryButton(
                        label: '试听',
                        icon: Icons.play_arrow,
                        onPressed: () async {
                          await ref.read(previewProvider.notifier).preview(
                                text: _previewText,
                                voiceId: voice.id,
                              );
                          if (context.mounted) {
                            showAppSnack(
                              context,
                              '正在试听：${voice.name}（全局旁白未改动）',
                            );
                          }
                        },
                      ),
              ),
              const SizedBox(width: 10),
              // 设旁白：仅此按钮改动全局旁白
              Expanded(
                child: PrimaryButton(
                  label: voice.isNarrator ? '已是旁白' : '设旁白',
                  icon: Icons.record_voice_over,
                  onPressed: voice.isNarrator
                      ? null
                      : () async {
                          await ref
                              .read(engineServiceProvider)
                              .setNarratorVoice(voice.id);
                          if (context.mounted) {
                            showAppSnack(context, '已把旁白设为：${voice.name}');
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
