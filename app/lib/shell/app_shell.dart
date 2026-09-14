import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../pages/diagnostics_page.dart';
import '../pages/guide_page.dart';
import '../pages/models_page.dart';
import '../pages/sample_page.dart';
import '../pages/settings_page.dart';
import '../pages/voices_page.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';

/// 应用壳：底部导航 + 六个页面（规格 §3，IM-301~307）。
///
/// 六个页面全部常驻（不重建、不空白）；页面内一律中文文案。
class AppShell extends ConsumerWidget {
  const AppShell({super.key});

  static const List<String> titles = <String>[
    '引导',
    '模型管理',
    '音色库',
    '示例朗读',
    '设置',
    '诊断',
  ];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final index = ref.watch(navIndexProvider);
    final status = ref.watch(statusProvider).value;
    final models = ref.watch(modelsProvider).value;
    // 徽标要区分两种"非就绪"：**没装模型**（需要引导下载）与**已装但引擎尚未载入**
    // （引擎在首次朗读时才载入模型，属正常中间态，不该显示成红色告警）。
    final modelInstalled = models?.any((m) => m.installed) ?? false;
    final ready = status?.state.name == 'ready';
    final badgeLabel = status == null
        ? '正在读取'
        : (ready || !modelInstalled)
            ? engineStateLabel(status.state)
            : '待朗读时启动';
    final badgeColor = status == null
        ? Tokens.textDim
        : (ready
            ? Tokens.accent
            : (modelInstalled ? Tokens.textDim : Tokens.danger));

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            const Text('书声本地'),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                ' · ${titles[index]}',
                style: const TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w400,
                  color: Tokens.textDim,
                ),
              ),
            ),
          ],
        ),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 12),
            child: Center(
              child: StatusChip(
                label: badgeLabel,
                color: badgeColor,
              ),
            ),
          ),
        ],
      ),
      body: IndexedStack(
        index: index,
        children: const <Widget>[
          GuidePage(),
          ModelsPage(),
          VoicesPage(),
          SamplePage(),
          SettingsPage(),
          DiagnosticsPage(),
        ],
      ),
      bottomNavigationBar: Container(
        decoration: const BoxDecoration(
          color: Tokens.surface,
          border: Border(top: BorderSide(color: Tokens.divider)),
        ),
        child: BottomNavigationBar(
          currentIndex: index,
          onTap: (i) => ref.read(navIndexProvider.notifier).go(i),
          type: BottomNavigationBarType.fixed,
          backgroundColor: Tokens.surface,
          selectedItemColor: Tokens.accent,
          unselectedItemColor: Tokens.textDim,
          selectedFontSize: 10.5,
          unselectedFontSize: 10.5,
          iconSize: 22,
          items: const <BottomNavigationBarItem>[
            BottomNavigationBarItem(
              icon: Icon(Icons.explore_outlined),
              activeIcon: Icon(Icons.explore),
              label: '引导',
            ),
            BottomNavigationBarItem(
              icon: Icon(Icons.download_for_offline_outlined),
              activeIcon: Icon(Icons.download_for_offline),
              label: '模型',
            ),
            BottomNavigationBarItem(
              icon: Icon(Icons.record_voice_over_outlined),
              activeIcon: Icon(Icons.record_voice_over),
              label: '音色',
            ),
            BottomNavigationBarItem(
              icon: Icon(Icons.graphic_eq),
              activeIcon: Icon(Icons.multitrack_audio),
              label: '朗读',
            ),
            BottomNavigationBarItem(
              icon: Icon(Icons.tune_outlined),
              activeIcon: Icon(Icons.tune),
              label: '设置',
            ),
            BottomNavigationBarItem(
              icon: Icon(Icons.monitor_heart_outlined),
              activeIcon: Icon(Icons.monitor_heart),
              label: '诊断',
            ),
          ],
        ),
      ),
    );
  }
}
