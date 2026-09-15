import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';

/// 默认示例文本（可编辑）。
const String _defaultSample =
    '夜深了，台灯把书桌照成一小块温暖的岛。他翻开书，让文字顺着目光流进心里，'
    '再被一个温和的声音读出来——不急不缓，一字一顿，像是有人在旁边陪着。';

/// 多角色示例文本：每句都带**明确说话人提示 + 引号对白**，
/// 供引擎的说话人识别（`TextAnalyzer`）与多角色分配（在线＝voicedesign 造音色）落地验证。
const String _multiRoleSample =
    '林安说：“你先别急着走。”\n'
    '苏岑笑道：“我听着呢。”\n'
    '吴伯提醒道：“天不早了，明天还要早起。”';

/// 示例朗读页（IM-304）：可编辑文本 + 播放/停止 + 当前音色/语速显示。
class SamplePage extends ConsumerStatefulWidget {
  const SamplePage({super.key});

  @override
  ConsumerState<SamplePage> createState() => _SamplePageState();
}

class _SamplePageState extends ConsumerState<SamplePage> {
  final TextEditingController _text = TextEditingController(text: _defaultSample);

  @override
  void initState() {
    super.initState();
    // 字数统计随输入实时刷新
    _text.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final voices = ref.watch(voicesProvider).value ?? const <VoiceInfo>[];
    final config = ref.watch(configProvider).value;
    final preview = ref.watch(previewProvider);

    final narrator = voices.where((v) => v.isNarrator).toList();
    final narratorId = narrator.isEmpty ? null : narrator.first.id;
    final narratorName = narrator.isEmpty ? '（默认音色）' : narrator.first.name;
    final speed = config?.speed ?? 1.0;
    final playing = preview.playing;

    return PageBody(
      children: [
        SectionCard(
          title: '要朗读的文本',
          subtitle: '可直接编辑；点「开始朗读」用当前旁白音色试播这段文字。',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TextField(
                controller: _text,
                maxLines: 6,
                minLines: 4,
                decoration: const InputDecoration(
                  hintText: '在这里输入或粘贴要朗读的文字',
                ),
                style: const TextStyle(
                  fontSize: 14,
                  color: Tokens.text,
                  height: 1.6,
                ),
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  Text(
                    '共 ${_text.text.runes.length} 字',
                    style: const TextStyle(fontSize: 12, color: Tokens.textDim),
                  ),
                  const Spacer(),
                  TextButton(
                    onPressed: () {
                      setState(() => _text.text = _multiRoleSample);
                    },
                    child: const Text('多角色示例'),
                  ),
                  TextButton(
                    onPressed: () {
                      setState(() => _text.text = _defaultSample);
                    },
                    child: const Text('恢复默认'),
                  ),
                ],
              ),
            ],
          ),
        ),
        SectionCard(
          title: '朗读设置',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              KeyValueRow(
                label: '当前音色',
                value: narratorName,
                valueColor: Tokens.accent,
              ),
              KeyValueRow(label: '语速', value: '${speed.toStringAsFixed(1)} 倍'),
              KeyValueRow(
                label: '模式',
                value: (config?.onlineEnabled ?? false)
                    ? '在线朗读已开启（优先在线，失败回退本机）'
                    : '离线朗读（本机模型，全程不联网）',
              ),
              const SizedBox(height: 8),
              const Text(
                '想换音色去「音色库」点「设旁白」；想调语速去「设置」拖动语速滑杆。',
                style: TextStyle(
                  fontSize: 12.5,
                  color: Tokens.textDim,
                  height: 1.5,
                ),
              ),
            ],
          ),
        ),
        PrimaryButton(
          label: playing ? '正在朗读…' : '开始朗读',
          icon: Icons.play_arrow,
          onPressed: playing
              ? null
              : () async {
                  if (_text.text.trim().isEmpty) {
                    showAppSnack(context, '请先输入要朗读的文字', danger: true);
                    return;
                  }
                  await ref.read(previewProvider.notifier).preview(
                        text: _text.text,
                        voiceId: narratorId ?? '',
                      );
                },
        ),
        const SizedBox(height: 10),
        SecondaryButton(
          label: '停止',
          icon: Icons.stop,
          onPressed: playing
              ? () => ref.read(previewProvider.notifier).stop()
              : null,
        ),
        if (playing) ...[
          const SizedBox(height: 12),
          const SectionCard(
            child: Row(
              children: [
                SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2.4),
                ),
                SizedBox(width: 10),
                Expanded(
                  child: Text(
                    '正在用当前旁白朗读示例文本（演示流程，正式版为真实语音）',
                    style: TextStyle(fontSize: 13, color: Tokens.text),
                  ),
                ),
              ],
            ),
          ),
        ],
      ],
    );
  }
}
