import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../platform/system_bridge.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';

/// 诊断页（IM-306）：引擎状态、模型、最近一次朗读数据（可清零）、日志复制。
class DiagnosticsPage extends ConsumerWidget {
  const DiagnosticsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final diag = ref.watch(diagnosticsProvider);

    if (diag.isLoading && !diag.hasValue) {
      return const PageBody(
        children: [
          SectionCard(
            child: Center(
              child: Padding(
                padding: EdgeInsets.all(18),
                child: CircularProgressIndicator(),
              ),
            ),
          ),
        ],
      );
    }

    final data = diag.value;
    final status = data?.status;
    final perf = data?.perf ?? const <String, dynamic>{};
    final logs = data?.logs ?? const <String>[];

    return PageBody(
      children: [
        SectionCard(
          title: '引擎状态',
          trailing: status == null
              ? null
              : StatusChip(
                  label: engineStateLabel(status.state),
                  color: status.state.name == 'ready'
                      ? Tokens.accent
                      : Tokens.danger,
                ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              KeyValueRow(
                label: '运行状态',
                value: status == null ? '正在读取…' : engineStateLabel(status.state),
              ),
              KeyValueRow(
                label: '使用中的模型',
                value: status?.modelId ?? '尚未安装（去「模型」页下载）',
              ),
              KeyValueRow(
                label: '音频质量',
                value: (status == null || status.sampleRate == 0)
                    ? '待模型安装后显示'
                    : '${(status.sampleRate / 1000).toStringAsFixed(1)} 千赫兹',
              ),
              KeyValueRow(
                label: '可用音色',
                value: (status == null || status.speakers == 0)
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
        SectionCard(
          title: '对照朗读（真机 A/B）',
          subtitle: '经系统朗读通道调用本机引擎朗读一段样例：开着在线朗读时走在线合成，关闭时走本机合成。'
              '不改旁白、不写配置；读完回到本页可看「最近一次朗读数据」。',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  FilledButton.icon(
                    onPressed: () async {
                      final ok = await SystemBridge.speakSample(
                        '夜深了，台灯把书桌照成一小块温暖的岛。他翻开书，让文字顺着目光流进心里。',
                      );
                      if (context.mounted) {
                        showAppSnack(context, ok ? '已交给引擎朗读，稍候看下方数据' : '调用失败：未找到本机引擎');
                      }
                    },
                    icon: const Icon(Icons.play_arrow_rounded, size: 18),
                    label: const Text('朗读一次'),
                  ),
                  const SizedBox(width: 12),
                  OutlinedButton.icon(
                    onPressed: () => SystemBridge.stopSample(),
                    icon: const Icon(Icons.stop_rounded, size: 18),
                    label: const Text('停止'),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              const Text(
                '提示：若列表为空，请先在系统「文字转语音」里把「书声本地」设为默认引擎。',
                style: TextStyle(fontSize: 12, color: Tokens.textDim, height: 1.5),
              ),
            ],
          ),
        ),
        SectionCard(
          title: '最近一次朗读数据',
          subtitle: '每次朗读（含试听）结束后自动记录，用于观察本机表现。',
          trailing: perf.isEmpty
              ? null
              : TextButton(
                  onPressed: () async {
                    await ref.read(engineServiceProvider).clearPerfCounters();
                    if (context.mounted) {
                      showAppSnack(context, '已清零');
                    }
                  },
                  child: const Text('清零'),
                ),
          child: perf.isEmpty
              ? const Text(
                  '暂无数据。去「示例朗读」试听一次，这里就会出现记录。',
                  style: TextStyle(
                    fontSize: 13,
                    color: Tokens.textDim,
                    height: 1.5,
                  ),
                )
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    KeyValueRow(
                      label: '首块出声',
                      value: '${perf['firstChunkMs'] ?? '--'} 毫秒',
                      valueColor: Tokens.accent,
                    ),
                    KeyValueRow(
                      label: '总耗时',
                      value: perf['totalMs'] == null
                          ? '--'
                          : '${(perf['totalMs'] / 1000).toStringAsFixed(1)} 秒',
                    ),
                    KeyValueRow(
                      label: '丢块数',
                      value: '${perf['droppedChunks'] ?? 0}',
                    ),
                    KeyValueRow(
                      label: '记录时间',
                      value: _formatTime(perf['perfAt']),
                    ),
                  ],
                ),
        ),
        SectionCard(
          title: '运行日志',
          subtitle: '最近 ${logs.length} 条（新的在上）。排查问题时整段复制给开发者。',
          trailing: TextButton(
            onPressed: logs.isEmpty
                ? null
                : () async {
                    await Clipboard.setData(
                      ClipboardData(text: logs.reversed.join('\n')),
                    );
                    if (context.mounted) {
                      showAppSnack(context, '日志已复制到剪贴板');
                    }
                  },
            child: const Text('复制日志'),
          ),
          child: logs.isEmpty
              ? const Text(
                  '暂无日志。',
                  style: TextStyle(fontSize: 13, color: Tokens.textDim),
                )
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    for (final line in logs.reversed.take(40))
                      Padding(
                        padding: const EdgeInsets.only(bottom: 5),
                        child: Text(
                          '· $line',
                          style: const TextStyle(
                            fontSize: 12,
                            color: Tokens.text,
                            height: 1.5,
                          ),
                        ),
                      ),
                  ],
                ),
        ),
        PrimaryButton(
          label: '刷新诊断数据',
          icon: Icons.refresh,
          onPressed: () => ref.invalidate(diagnosticsProvider),
        ),
      ],
    );
  }

  static String _formatTime(Object? raw) {
    final ms = raw is num ? raw.toInt() : null;
    if (ms == null || ms <= 0) {
      return '--';
    }
    final t = DateTime.fromMillisecondsSinceEpoch(ms);
    String two(int v) => v.toString().padLeft(2, '0');
    return '${two(t.month)} 月 ${two(t.day)} 日 ${two(t.hour)}:${two(t.minute)}:'
        '${two(t.second)}';
  }
}
