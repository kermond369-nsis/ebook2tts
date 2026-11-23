import 'package:flutter/material.dart';

import '../platform/system_bridge.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';

/// 开局 / 模式切换页（RQ-513 / RQ-514 / RQ-515）。
///
/// 三种清晰模式一屏直选：
/// 1. **纯在线模式**（`only_online`）：只显示在线引擎配置，无需下载本地模型；
/// 2. **纯本地模式**（`only_local`）：完全离线，只显示本地模型与音色；首次选中无条件弹出「本地合成最低要求」告知；
/// 3. **混合模式**（`prefer_online` / `prefer_local`）：双引擎均可配置，按所选优先级自动兜底。
class ModeSetupPage extends StatefulWidget {
  const ModeSetupPage({
    super.key,
    required this.onDone,
    this.initialMode,
    this.isInitialSetup = true,
  });

  final Future<void> Function() onDone;
  final String? initialMode;
  final bool isInitialSetup;

  @override
  State<ModeSetupPage> createState() => _ModeSetupPageState();
}

enum _TopMode { onlineOnly, localOnly, hybrid }

class _ModeSetupPageState extends State<ModeSetupPage> {
  _TopMode? _selected;
  bool _hybridPreferOnline = true;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    switch (widget.initialMode) {
      case 'only_online':
        _selected = _TopMode.onlineOnly;
      case 'only_local':
        _selected = _TopMode.localOnly;
      case 'prefer_local':
        _selected = _TopMode.hybrid;
        _hybridPreferOnline = false;
      case 'prefer_online':
        _selected = _TopMode.hybrid;
        _hybridPreferOnline = true;
      default:
        // 首次引导不预选，强制由用户主动拍板
        _selected = null;
    }
  }

  String get _resolvedModeId => switch (_selected) {
        _TopMode.onlineOnly => 'only_online',
        _TopMode.localOnly => 'only_local',
        _TopMode.hybrid =>
          _hybridPreferOnline ? 'prefer_online' : 'prefer_local',
        null => 'prefer_online',
      };

  Future<void> _selectLocal() async {
    if (_selected == _TopMode.localOnly) return;
    await _confirmLocalNotice();
    if (!mounted) return;
    setState(() => _selected = _TopMode.localOnly);
  }

  Future<void> _confirmLocalNotice() async {
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        title: const Text('本地合成最低要求'),
        content: const Text(
          '本地合成最低要求：骁龙 8 Gen 1 及以上／天玑 9300 及以上（含其后发布的旗舰芯片）。\n\n'
          '低于该要求的设备上本地合成可能极慢且不作性能承诺；需要流畅体验请改用在线合成。',
          style: TextStyle(height: 1.55),
        ),
        actions: [
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('我知道了'),
          ),
        ],
      ),
    );
  }

  Future<void> _confirm() async {
    if (_selected == null) return;
    setState(() => _saving = true);
    final saved = await SystemBridge.setRouteMode(_resolvedModeId);
    if (!mounted) return;
    setState(() => _saving = false);
    if (saved == null) {
      showAppSnack(context, '未能保存模式选择，请重试', danger: true);
      return;
    }
    await widget.onDone();
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: !widget.isInitialSetup,
        title: Text(widget.isInitialSetup ? '欢迎使用书声' : '切换工作模式'),
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text(
                '选择你的朗读模式',
                style: TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.w800,
                  color: Tokens.text,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                '页面与配置项会按所选模式自动精简，后续可随时在「设置」中切换。',
                style: TextStyle(
                  fontSize: 13.5,
                  color: colorScheme.onSurfaceVariant,
                  height: 1.5,
                ),
              ),
              const SizedBox(height: 18),
              Expanded(
                child: SingleChildScrollView(
                  child: Column(
                    children: [
                      _ModeOptionCard(
                        icon: Icons.cloud_done_outlined,
                        accentColor: Tokens.cloud,
                        title: '纯在线模式',
                        badge: '轻量推荐',
                        subtitle: '无需下载任何本地语音模型；界面仅保留在线引擎与密钥配置，即填即听。',
                        selected: _selected == _TopMode.onlineOnly,
                        onTap: () =>
                            setState(() => _selected = _TopMode.onlineOnly),
                      ),
                      const SizedBox(height: 12),
                      _ModeOptionCard(
                        icon: Icons.memory_rounded,
                        accentColor: Tokens.accent,
                        title: '纯本地模式',
                        badge: '完全离线',
                        subtitle: '全程零联网、文本不出本机；界面仅保留本地模型管理与离线音色库。',
                        selected: _selected == _TopMode.localOnly,
                        onTap: _selectLocal,
                      ),
                      const SizedBox(height: 12),
                      _ModeOptionCard(
                        icon: Icons.merge_type_rounded,
                        accentColor: Tokens.hybrid,
                        title: '混合模式',
                        badge: '双引擎兜底',
                        subtitle: '同时展示在线引擎与本地模型；一条链路不可用时自动切换另一条保障不断流。',
                        selected: _selected == _TopMode.hybrid,
                        onTap: () =>
                            setState(() => _selected = _TopMode.hybrid),
                        footer: _selected == _TopMode.hybrid
                            ? Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  const Divider(height: 20),
                                  const Text(
                                    '双引擎同时可用时优先使用：',
                                    style: TextStyle(
                                      fontSize: 12.5,
                                      color: Tokens.textDim,
                                    ),
                                  ),
                                  const SizedBox(height: 8),
                                  SegmentedButton<bool>(
                                    showSelectedIcon: false,
                                    segments: const [
                                      ButtonSegment<bool>(
                                        value: true,
                                        label: Text('在线优先（失败走本地）'),
                                      ),
                                      ButtonSegment<bool>(
                                        value: false,
                                        label: Text('本地优先（不可用走在线）'),
                                      ),
                                    ],
                                    selected: <bool>{_hybridPreferOnline},
                                    onSelectionChanged: (s) => setState(
                                      () => _hybridPreferOnline = s.first,
                                    ),
                                  ),
                                ],
                              )
                            : null,
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 14),
              FilledButton.icon(
                onPressed: (_selected == null || _saving) ? null : _confirm,
                icon: Icon(
                  widget.isInitialSetup
                      ? Icons.arrow_forward_rounded
                      : Icons.check_rounded,
                ),
                label: Text(
                  _saving
                      ? '正在应用…'
                      : (widget.isInitialSetup ? '进入应用' : '保存并应用'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ModeOptionCard extends StatelessWidget {
  const _ModeOptionCard({
    required this.icon,
    required this.accentColor,
    required this.title,
    required this.badge,
    required this.subtitle,
    required this.selected,
    required this.onTap,
    this.footer,
  });

  final IconData icon;
  final Color accentColor;
  final String title;
  final String badge;
  final String subtitle;
  final bool selected;
  final VoidCallback onTap;
  final Widget? footer;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(Tokens.radiusCard),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 160),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: selected
              ? accentColor.withValues(alpha: 0.1)
              : Tokens.surface,
          borderRadius: BorderRadius.circular(Tokens.radiusCard),
          border: Border.all(
            color: selected ? accentColor : Tokens.divider,
            width: selected ? 1.8 : 1,
          ),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  width: 40,
                  height: 40,
                  decoration: BoxDecoration(
                    color: accentColor.withValues(alpha: 0.16),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(icon, color: accentColor, size: 22),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Row(
                    children: [
                      Text(
                        title,
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w700,
                          color: Tokens.text,
                        ),
                      ),
                      const SizedBox(width: 8),
                      StatusChip(label: badge, color: accentColor),
                    ],
                  ),
                ),
                Icon(
                  selected
                      ? Icons.check_circle_rounded
                      : Icons.radio_button_unchecked_rounded,
                  color: selected ? accentColor : Tokens.textDim,
                  size: 22,
                ),
              ],
            ),
            const SizedBox(height: 10),
            Text(
              subtitle,
              style: const TextStyle(
                fontSize: 13,
                color: Tokens.textDim,
                height: 1.5,
              ),
            ),
            ?footer,
          ],
        ),
      ),
    );
  }
}
