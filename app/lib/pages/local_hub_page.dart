import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../state/providers.dart';
import '../theme/tokens.dart';
import 'models_page.dart';
import 'voices_page.dart';

/// 混合模式下的「本地」聚合页：顶部两段式切换「模型管理 / 音色库」。
class LocalHubPage extends ConsumerWidget {
  const LocalHubPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tab = ref.watch(localHubTabProvider);

    return Column(
      children: [
        Container(
          color: Tokens.background,
          padding: const EdgeInsets.fromLTRB(
            Tokens.pagePadding,
            8,
            Tokens.pagePadding,
            6,
          ),
          child: SizedBox(
            width: double.infinity,
            child: SegmentedButton<int>(
              showSelectedIcon: false,
              segments: const [
                ButtonSegment(
                  value: 0,
                  icon: Icon(Icons.download_for_offline_outlined, size: 17),
                  label: Text('模型管理'),
                ),
                ButtonSegment(
                  value: 1,
                  icon: Icon(Icons.record_voice_over_outlined, size: 17),
                  label: Text('音色库'),
                ),
              ],
              selected: <int>{tab},
              onSelectionChanged: (s) =>
                  ref.read(localHubTabProvider.notifier).select(s.first),
            ),
          ),
        ),
        Expanded(
          child: IndexedStack(
            index: tab,
            children: const [
              ModelsPage(),
              VoicesPage(),
            ],
          ),
        ),
      ],
    );
  }
}
