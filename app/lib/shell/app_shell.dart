import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../engine/engine_service.dart';
import '../platform/system_bridge.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';
import '../pages/diagnostics_page.dart';
import '../pages/guide_page.dart';
import '../pages/local_hub_page.dart';
import '../pages/models_page.dart';
import '../pages/online_config_page.dart';
import '../pages/sample_page.dart';
import '../pages/settings_page.dart';
import '../pages/voices_page.dart';

class _NavTab {
  const _NavTab({
    required this.title,
    required this.destination,
    required this.icon,
    required this.selectedIcon,
    required this.page,
  });

  final String title;
  final String destination;
  final IconData icon;
  final IconData selectedIcon;
  final Widget page;
}

/// 主导航壳：严格按当前工作模式（纯在线 / 纯本地 / 混合）动态筛选页面与底部导航。
class AppShell extends ConsumerWidget {
  const AppShell({super.key});

  static List<_NavTab> _tabsFor(AppWorkMode mode) => switch (mode) {
        AppWorkMode.onlineOnly => const [
            _NavTab(
              title: '首页概览',
              destination: '首页',
              icon: Icons.home_outlined,
              selectedIcon: Icons.home_rounded,
              page: GuidePage(),
            ),
            _NavTab(
              title: '在线引擎',
              destination: '在线',
              icon: Icons.cloud_outlined,
              selectedIcon: Icons.cloud_rounded,
              page: OnlineConfigPage(),
            ),
            _NavTab(
              title: '示例朗读',
              destination: '朗读',
              icon: Icons.graphic_eq_outlined,
              selectedIcon: Icons.graphic_eq_rounded,
              page: SamplePage(),
            ),
            _NavTab(
              title: '设置',
              destination: '设置',
              icon: Icons.tune_outlined,
              selectedIcon: Icons.tune_rounded,
              page: SettingsPage(),
            ),
          ],
        AppWorkMode.localOnly => const [
            _NavTab(
              title: '首页概览',
              destination: '首页',
              icon: Icons.home_outlined,
              selectedIcon: Icons.home_rounded,
              page: GuidePage(),
            ),
            _NavTab(
              title: '本地模型',
              destination: '模型',
              icon: Icons.download_for_offline_outlined,
              selectedIcon: Icons.download_for_offline_rounded,
              page: ModelsPage(),
            ),
            _NavTab(
              title: '本地音色',
              destination: '音色',
              icon: Icons.record_voice_over_outlined,
              selectedIcon: Icons.record_voice_over_rounded,
              page: VoicesPage(),
            ),
            _NavTab(
              title: '示例朗读',
              destination: '朗读',
              icon: Icons.graphic_eq_outlined,
              selectedIcon: Icons.graphic_eq_rounded,
              page: SamplePage(),
            ),
            _NavTab(
              title: '设置',
              destination: '设置',
              icon: Icons.tune_outlined,
              selectedIcon: Icons.tune_rounded,
              page: SettingsPage(),
            ),
          ],
        AppWorkMode.hybrid => const [
            _NavTab(
              title: '首页概览',
              destination: '首页',
              icon: Icons.home_outlined,
              selectedIcon: Icons.home_rounded,
              page: GuidePage(),
            ),
            _NavTab(
              title: '在线引擎',
              destination: '在线',
              icon: Icons.cloud_outlined,
              selectedIcon: Icons.cloud_rounded,
              page: OnlineConfigPage(),
            ),
            _NavTab(
              title: '本地引擎',
              destination: '本地',
              icon: Icons.storage_outlined,
              selectedIcon: Icons.storage_rounded,
              page: LocalHubPage(),
            ),
            _NavTab(
              title: '示例朗读',
              destination: '朗读',
              icon: Icons.graphic_eq_outlined,
              selectedIcon: Icons.graphic_eq_rounded,
              page: SamplePage(),
            ),
            _NavTab(
              title: '设置',
              destination: '设置',
              icon: Icons.tune_outlined,
              selectedIcon: Icons.tune_rounded,
              page: SettingsPage(),
            ),
          ],
      };

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final route = ref.watch(routeModeProvider).value ??
        const RouteModeState(mode: 'prefer_online', chosen: true);
    final workMode = route.workMode;
    final tabs = _tabsFor(workMode);
    final rawIndex = ref.watch(navIndexProvider);
    final index = rawIndex.clamp(0, tabs.length - 1);

    final status = ref.watch(statusProvider).value;
    final models = ref.watch(modelsProvider).value ?? const <ModelInfo>[];
    final localInstalled = models.any((m) => m.installed);

    final (String healthLabel, Color healthColor) = switch (workMode) {
      AppWorkMode.onlineOnly => switch (status?.online) {
          OnlineState.ready => ('在线就绪', Tokens.accent),
          OnlineState.error => ('在线异常', Tokens.danger),
          OnlineState.noKey => ('待填密钥', Tokens.hybrid),
          OnlineState.off => ('在线未开', Tokens.textDim),
          null => ('读取中', Tokens.textDim),
        },
      AppWorkMode.localOnly => switch (status?.state) {
          EngineState.ready => ('本地就绪', Tokens.accent),
          EngineState.loading => ('模型载入中', Tokens.cloud),
          EngineState.reloading => ('重载中', Tokens.cloud),
          EngineState.error => ('本地异常', Tokens.danger),
          EngineState.noModel =>
            localInstalled ? ('待朗读启动', Tokens.textDim) : ('未装模型', Tokens.danger),
          null => ('读取中', Tokens.textDim),
        },
      AppWorkMode.hybrid => () {
          final onlineReady = status?.online == OnlineState.ready;
          final localReady = status?.state == EngineState.ready || localInstalled;
          if (onlineReady && localReady) {
            return ('双引擎就绪', Tokens.accent);
          }
          if (onlineReady) return ('在线就绪', Tokens.cloud);
          if (status?.state == EngineState.ready) return ('本地就绪', Tokens.accent);
          if (localInstalled) return ('本地待命', Tokens.textDim);
          if (status?.online == OnlineState.error) {
            return ('在线异常', Tokens.danger);
          }
          return ('待配置', Tokens.hybrid);
        }(),
    };

    final modeBadge = switch (workMode) {
      AppWorkMode.onlineOnly => ('纯在线', Tokens.cloud),
      AppWorkMode.localOnly => ('纯本地', Tokens.accent),
      AppWorkMode.hybrid => ('混合', Tokens.hybrid),
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
            const SizedBox(width: 8),
            StatusChip(label: modeBadge.$1, color: modeBadge.$2),
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
        children: [for (final t in tabs) t.page],
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
            for (final t in tabs)
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
