import 'package:flutter/material.dart';

import '../platform/system_bridge.dart';

/// 开局朗读模式选择（RQ-513 / RQ-514）。
///
/// **不预置任何模型**：首启先选「在线模式 / 本地模式」，再选「仅 X / 谁优先」，
/// 结果经系统桥写入引擎配置（`route.mode`）并置"已选择"。
///
/// RQ-515（2026-09-21 修订）：**不做机型判定、不做任何限制**。用户选择**本地模式**后
/// **无条件**弹一次「本地合成最低要求」告知——频次＝首次进入 App 时一次（甲方 2026-09-21 确认），
/// 本页只出现在首启流程，故天然满足「只弹一次」。
class ModeSetupPage extends StatefulWidget {
  const ModeSetupPage({super.key, required this.onDone});

  /// 完成回调（已完成落库）；由闸门重新读取状态后进入主界面。
  final Future<void> Function() onDone;

  @override
  State<ModeSetupPage> createState() => _ModeSetupPageState();
}

class _ModeSetupPageState extends State<ModeSetupPage> {
  bool? _onlineMode;      // true=在线模式、false=本地模式
  bool? _only;            // true=仅 X、false=谁优先
  bool _saving = false;

  String get _modeId {
    final online = _onlineMode ?? true;
    final only = _only ?? false;
    if (online && only) return 'only_online';
    if (online) return 'prefer_online';
    if (only) return 'only_local';
    return 'prefer_local';
  }

  Future<void> _confirm() async {
    setState(() => _saving = true);
    final saved = await SystemBridge.setRouteMode(_modeId);
    if (!mounted) return;
    setState(() => _saving = false);
    if (saved == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('未能保存选择，请重试')),
      );
      return;
    }
    await widget.onDone();
  }

  /// 选择「本地模式」后的**无条件告知**（RQ-515，2026-09-21 修订）。
  ///
  /// 不做机型判定、不做任何限制：任意机型均弹最低要求告知，确认后即可使用。
  Future<void> _confirmLocalNotice() async {
    await showDialog<void>(
      context: context,
      barrierDismissible: false, // 复核 4.2：点外部/返回不得"关闭即被动选中"，须显式确认
      builder: (ctx) => AlertDialog(
        title: const Text('本地合成最低要求'),
        content: const Text(
          '本地合成最低要求：骁龙 8 Gen 1 及以上／天玑 9300 及以上（含其后发布的旗舰芯片）。\n\n'
          '低于该要求的设备上本地合成可能极慢且不作性能承诺；需要流畅体验请改用在线合成。',
        ),
        actions: [
          FilledButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('我知道了')),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final stepOne = _onlineMode == null;
    return Scaffold(
      appBar: AppBar(
        title: Text(stepOne ? '选择朗读方式（1/2）' : '选择优先方式（2/2）'),
        automaticallyImplyLeading: false,
      ),
      body: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              stepOne
                  ? '本应用不预置语音模型。请先选择朗读方式，随后可随时在设置中更改。'
                  : '在两条链路都可用时，优先使用哪一条？（另一条仍会在需要时启用）',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
            const SizedBox(height: 16),
            if (stepOne) ...[
              _Card(
                title: '在线模式',
                subtitle: '使用在线语音（需自备密钥；音质稳定、速度与设备无关）',
                selected: _onlineMode == true,
                onTap: () => setState(() => _onlineMode = true),
              ),
              const SizedBox(height: 12),
              _Card(
                title: '本地模式',
                subtitle: '使用本机推理（无需套餐；高性能机型体验佳，低配机型可能很慢）',
                selected: _onlineMode == false,
                onTap: () async {
                  if (_onlineMode == false) return;          // 复核 4.1：本页内不重复弹窗
                  await _confirmLocalNotice();               // RQ-515：选择本地后无条件告知
                  if (!mounted) return;
                  setState(() => _onlineMode = false);
                },
              ),
            ] else ...[
              _Card(
                title: _onlineMode == true ? '仅在线' : '仅本地',
                subtitle: '只使用这一条链路；不可用时明确报错，不会悄悄换用另一条',
                selected: _only == true,
                onTap: () => setState(() => _only = true),
              ),
              const SizedBox(height: 12),
              _Card(
                title: '谁优先',
                subtitle: '优先使用所选方式；不可用时自动使用另一条链路',
                selected: _only == false,
                onTap: () => setState(() => _only = false),
              ),
            ],
            const Spacer(),
            Row(
              children: [
                if (!stepOne)
                  TextButton(
                    onPressed: _saving ? null : () => setState(() => _onlineMode = null),
                    child: const Text('上一步'),
                  ),
                const Spacer(),
                FilledButton(
                  onPressed: _saving
                      ? null
                      : (stepOne
                          ? (_onlineMode == null ? null : () => setState(() => _only = null))
                          : (_only == null ? null : _confirm)),
                  child: Text(stepOne ? '下一步' : (_saving ? '保存中…' : '完成')),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// 选项卡片（与设置页视觉一致的最小实现）。
class _Card extends StatelessWidget {
  const _Card({
    required this.title,
    required this.subtitle,
    required this.selected,
    required this.onTap,
  });

  final String title;
  final String subtitle;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(14),
          border: Border.all(
            color: selected ? scheme.primary : scheme.outlineVariant,
            width: selected ? 2 : 1,
          ),
          color: selected ? scheme.primaryContainer.withValues(alpha: 0.35) : null,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(child: Text(title, style: Theme.of(context).textTheme.titleMedium)),
                if (selected) Icon(Icons.check_circle, color: scheme.primary, size: 20),
              ],
            ),
            const SizedBox(height: 6),
            Text(subtitle, style: Theme.of(context).textTheme.bodySmall),
          ],
        ),
      ),
    );
  }
}
