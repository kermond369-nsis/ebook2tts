import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';

const String _defaultSample =
    '夜深了，台灯把书桌照成一小块温暖的岛。他翻开书，让文字顺着目光流进心里，'
    '再被一个温和的声音读出来——不急不缓，一字一顿，像是有人在旁边陪着。';

const String _multiRoleSample =
    '林安说：“你先别急着走。”\n'
    '苏岑笑道：“我听着呢。”\n'
    '吴伯提醒道：“天不早了，明天还要早起。”';

/// 示例朗读页：即时试播当前在线引擎（弱网或未开在线时自动无缝回退系统 TTS）。
class SamplePage extends ConsumerStatefulWidget {
  const SamplePage({super.key});

  @override
  ConsumerState<SamplePage> createState() => _SamplePageState();
}

class _SamplePageState extends ConsumerState<SamplePage> {
  final TextEditingController _text =
      TextEditingController(text: _defaultSample);
  double? _pendingSpeed;

  @override
  void initState() {
    super.initState();
    _text.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final config = ref.watch(configProvider).value;
    final status = ref.watch(statusProvider).value;
    final preview = ref.watch(previewProvider);

    final currentSpeed = _pendingSpeed ?? config?.speed ?? 1.0;
    final playing = preview.playing;

    final providerLabel = switch (config?.providerKind) {
      'openai' => 'OpenAI 兼容',
      'custom_http' => '自定义 HTTP',
      _ => '小米 MiMo',
    };

    final onlineSummary = status?.online == OnlineState.ready
        ? '$providerLabel · ${config?.voice.isNotEmpty == true ? config!.voice : '默认音色'}'
        : '在线未就绪（${onlineStateLabel(status?.online ?? OnlineState.off)}）';
    final fallbackSummary = status?.fallbackEngineLabel ?? '未检测到其他系统 TTS';

    return PageBody(
      children: [
        SectionCard(
          title: '要朗读的文本',
          subtitle: '可直接编辑或粘贴小说片段；弱网超时或在线未就绪时自动回退系统自带 TTS。',
          trailing: Wrap(
            spacing: 6,
            children: [
              ActionChip(
                label: const Text('多角色示例'),
                onPressed: () => setState(() => _text.text = _multiRoleSample),
              ),
              ActionChip(
                label: const Text('默认示例'),
                onPressed: () => setState(() => _text.text = _defaultSample),
              ),
            ],
          ),
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
                  fontSize: 14.5,
                  color: Tokens.text,
                  height: 1.65,
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
                  Text(
                    playing ? previewStageLabel(preview.stage) : '就绪',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: playing ? Tokens.accent : Tokens.textDim,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
        SectionCard(
          title: '朗读链路与即时语速',
          trailing: StatusChip(
            label: '${currentSpeed.toStringAsFixed(1)}×',
            color: Tokens.accent,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              KeyValueRow(
                label: '主在线链路',
                value: onlineSummary,
                valueColor: status?.online == OnlineState.ready
                    ? Tokens.cloud
                    : Tokens.textDim,
              ),
              KeyValueRow(
                label: '弱网兜底',
                value: fallbackSummary,
                valueColor: Tokens.accent,
              ),
              const SizedBox(height: 8),
              Slider(
                value: currentSpeed.clamp(0.5, 2.0),
                min: 0.5,
                max: 2.0,
                divisions: 15,
                label: '${currentSpeed.toStringAsFixed(1)} 倍',
                onChanged: (v) => setState(() => _pendingSpeed = v),
                onChangeEnd: (v) async {
                  await ref.read(configProvider.notifier).save(speed: v);
                  if (mounted) {
                    setState(() => _pendingSpeed = null);
                    showAppSnack(context, '语速已更新：${v.toStringAsFixed(1)} 倍');
                  }
                },
              ),
            ],
          ),
        ),
        if (!playing &&
            preview.lastError != null &&
            preview.lastError!.isNotEmpty) ...[
          InfoBanner(
            title: '上次试听未成功',
            body: preview.lastError!,
            color: Tokens.danger,
            icon: Icons.error_outline_rounded,
            action: TextButton(
              onPressed: () => ref.read(previewProvider.notifier).clearError(),
              child: const Text('知道了'),
            ),
          ),
          const SizedBox(height: 12),
        ],
        Row(
          children: [
            Expanded(
              flex: 2,
              child: PrimaryButton(
                label: playing ? previewStageLabel(preview.stage) : '开始朗读',
                icon: Icons.play_arrow_rounded,
                onPressed: playing
                    ? null
                    : () async {
                        if (_text.text.trim().isEmpty) {
                          showAppSnack(context, '请先输入要朗读的文字', danger: true);
                          return;
                        }
                        await ref
                            .read(previewProvider.notifier)
                            .preview(text: _text.text);
                      },
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: SecondaryButton(
                label: '停止',
                icon: Icons.stop_rounded,
                danger: playing,
                onPressed: playing
                    ? () => ref.read(previewProvider.notifier).stop()
                    : null,
              ),
            ),
          ],
        ),
      ],
    );
  }
}
