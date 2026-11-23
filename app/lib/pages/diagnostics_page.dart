import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../platform/system_bridge.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';

/// 诊断与日志页：展示在线引擎状态、系统 TTS 兜底记录、通道测试与真实耗时。
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
                padding: EdgeInsets.all(24),
                child: CircularProgressIndicator(),
              ),
            ),
          ),
        ],
      );
    }

    final data = diag.value;
    final status = data?.status;
    final config = data?.config;
    final perf = data?.perf ?? const <String, dynamic>{};
    final logs = data?.logs ?? const <String>[];

    final backendLabel = switch (perf['backend']) {
      'online' => '在线引擎',
      'system_tts' => '系统 TTS 兜底',
      'online+system_tts' => '在线 + 系统 TTS 兜底',
      _ => '--',
    };

    return PageBody(
      children: [
        SectionCard(
          title: '运行概览',
          trailing: const StatusChip(label: '在线 + 系统兜底', color: Tokens.cloud),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              KeyValueRow(
                label: '在线状态',
                value: status == null
                    ? '读取中…'
                    : '${onlineStateLabel(status.online)}（${status.providerKind}）',
                valueColor: status?.online == OnlineState.ready
                    ? Tokens.accent
                    : Tokens.textDim,
              ),
              KeyValueRow(
                label: '默认音色 / 超时',
                value: config == null
                    ? '--'
                    : '${config.voice.isEmpty ? '默认' : config.voice} · ${config.timeoutMs ~/ 1000} 秒',
              ),
              KeyValueRow(
                label: '兜底系统引擎',
                value: status?.fallbackEngineLabel ?? '未检测',
                valueColor: (status?.hasFallbackEngine ?? false)
                    ? Tokens.accent
                    : Tokens.danger,
              ),
              if (status != null && status.onlineLastError.isNotEmpty)
                KeyValueRow(
                  label: '最近在线错误',
                  value: status.onlineLastError,
                  valueColor: Tokens.danger,
                ),
              if (status != null && status.lastFallbackReason.isNotEmpty)
                KeyValueRow(
                  label: '最近回退记录',
                  value:
                      '${status.lastFallbackEngine}（${status.lastFallbackReason} · ${_formatTime(status.lastFallbackAt)}）',
                  valueColor: Tokens.hybrid,
                ),
            ],
          ),
        ),
        SectionCard(
          title: '系统 TTS 通道对照测试',
          subtitle: '模拟微信读书/Legado 等第三方软件通过系统 Android TTS 接口调用本引擎。',
          child: Row(
            children: [
              Expanded(
                flex: 2,
                child: PrimaryButton(
                  label: '经系统 TTS 朗读',
                  icon: Icons.play_arrow_rounded,
                  onPressed: () async {
                    final ok = await SystemBridge.speakSample(
                      '夜深了，台灯把书桌照成一小块温暖的岛。他翻开书，让文字顺着目光流进心里。',
                    );
                    if (context.mounted) {
                      showAppSnack(
                        context,
                        ok ? '已下发到系统 TTS 引擎' : '调用失败：请先在系统 TTS 里选中「书声」',
                        danger: !ok,
                      );
                    }
                  },
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: SecondaryButton(
                  label: '停止',
                  icon: Icons.stop_rounded,
                  onPressed: SystemBridge.stopSample,
                ),
              ),
            ],
          ),
        ),
        SectionCard(
          title: '最近一次朗读性能数据',
          trailing: perf.isEmpty
              ? null
              : TextButton(
                  onPressed: () async {
                    await ref.read(engineServiceProvider).clearPerfCounters();
                    ref.invalidate(diagnosticsProvider);
                    if (context.mounted) showAppSnack(context, '已清零');
                  },
                  child: const Text('清零'),
                ),
          child: perf.isEmpty
              ? const Text(
                  '暂无合成记录。可在「朗读」页试听或点上方「经系统 TTS 朗读」自动记录。',
                  style: TextStyle(
                    fontSize: 13,
                    color: Tokens.textDim,
                    height: 1.5,
                  ),
                )
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    KeyValueRow(label: '实际走线', value: backendLabel),
                    KeyValueRow(
                      label: '首块出声 (TTFB)',
                      value: '${perf['firstChunkMs'] ?? '--'} ms',
                      valueColor: Tokens.accent,
                    ),
                    KeyValueRow(
                      label: '总耗时',
                      value: perf['totalMs'] is! num
                          ? '--'
                          : '${((perf['totalMs'] as num) / 1000).toStringAsFixed(2)} 秒',
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
          subtitle: '最近 ${logs.length} 条（最新在上方）。',
          trailing: TextButton(
            onPressed: logs.isEmpty
                ? null
                : () async {
                    await Clipboard.setData(
                      ClipboardData(text: logs.reversed.join('\n')),
                    );
                    if (context.mounted) showAppSnack(context, '日志已复制到剪贴板');
                  },
            child: const Text('复制'),
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
          label: '刷新诊断',
          icon: Icons.refresh_rounded,
          onPressed: () => ref.invalidate(diagnosticsProvider),
        ),
      ],
    );
  }

  static String _formatTime(Object? raw) {
    final ms = raw is num ? raw.toInt() : null;
    if (ms == null || ms <= 0) return '--';
    final t = DateTime.fromMillisecondsSinceEpoch(ms);
    String two(int v) => v.toString().padLeft(2, '0');
    return '${two(t.month)}-${two(t.day)} ${two(t.hour)}:${two(t.minute)}:${two(t.second)}';
  }
}
