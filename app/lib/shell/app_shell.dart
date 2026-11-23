import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../pages/diagnostics_page.dart';
import '../pages/guide_page.dart';
import '../pages/online_config_page.dart';
import '../pages/sample_page.dart';
import '../pages/settings_page.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';

class _NavTab {
  const _NavTab({
    required this.destination,
    required this.icon,
    required this.selectedIcon,
    required this.page,
  });

  final String destination;
  final IconData icon;
  final IconData selectedIcon;
  final Widget page;
}

/// 主导航壳：固定四页（首页 / 在线 / 朗读 / 设置）。
class AppShell extends ConsumerWidget {
  const AppShell({super.key});

  static const List<_NavTab> _tabs = <_NavTab>[
    _NavTab(
      destination: '首页',
      icon: Icons.home_outlined,
      selectedIcon: Icons.home_rounded,
      page: GuidePage(),
    ),
    _NavTab(
      destination: '在线',
      icon: Icons.cloud_outlined,
      selectedIcon: Icons.cloud_rounded,
      page: OnlineConfigPage(),
    ),
    _NavTab(
      destination: '朗读',
      icon: Icons.graphic_eq_outlined,
      selectedIcon: Icons.graphic_eq_rounded,
      page: SamplePage(),
    ),
    _NavTab(
      destination: '设置',
      icon: Icons.tune_outlined,
      selectedIcon: Icons.tune_rounded,
      page: SettingsPage(),
    ),
  ];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final rawIndex = ref.watch(navIndexProvider);
    final index = rawIndex.clamp(0, _tabs.length - 1);
    final status = ref.watch(statusProvider).value;

    final (String healthLabel, Color healthColor) = switch (status?.online) {
      OnlineState.ready => ('在线就绪', Tokens.accent),
      OnlineState.error => ('在线异常', Tokens.danger),
      OnlineState.noKey => ('待填密钥', Tokens.hybrid),
      OnlineState.off => ('系统兜底', Tokens.cloud),
      null => ('读取中', Tokens.textDim),
    };

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Container(
              width: 28,
              height: 28,
              decoration: BoxDecoration(
                color: Tokens.accent,
                borderRadius: BorderRadius.circular(8),
              ),
              child: const Icon(
                Icons.auto_stories_rounded,
                size: 17,
                color: Tokens.onAccent,
              ),
            ),
            const SizedBox(width: 9),
            const Text('书声'),
          ],
        ),
        actions: [
          Center(child: StatusChip(label: healthLabel, color: healthColor)),
          IconButton(
            tooltip: '运行诊断与日志',
            icon: const Icon(Icons.monitor_heart_outlined, size: 21),
            onPressed: () {
              Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => Scaffold(
                    appBar: AppBar(title: const Text('诊断与日志')),
                    body: const DiagnosticsPage(),
                  ),
                ),
              );
            },
          ),
        ],
      ),
      body: IndexedStack(
        index: index,
        children: [for (final t in _tabs) t.page],
      ),
      bottomNavigationBar: Container(
        decoration: const BoxDecoration(
          border: Border(top: BorderSide(color: Tokens.divider, width: 1)),
        ),
        child: NavigationBar(
          selectedIndex: index,
          onDestinationSelected: (i) =>
              ref.read(navIndexProvider.notifier).go(i),
          destinations: [
            for (final t in _tabs)
              NavigationDestination(
                icon: Icon(t.icon),
                selectedIcon: Icon(t.selectedIcon),
                label: t.destination,
              ),
          ],
        ),
      ),
    );
  }
}
