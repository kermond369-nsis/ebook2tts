import 'package:flutter/material.dart';

import '../theme/tokens.dart';

/// 页面骨架：统一的边距与滚动容器。
class PageBody extends StatelessWidget {
  const PageBody({super.key, required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(
        Tokens.pagePadding,
        10,
        Tokens.pagePadding,
        32,
      ),
      children: children,
    );
  }
}

/// 现代轮廓卡片：1px 细边框 + 可选高亮边 + 统一内边距。
class SectionCard extends StatelessWidget {
  const SectionCard({
    super.key,
    this.title,
    this.subtitle,
    this.trailing,
    this.color,
    this.borderColor,
    required this.child,
  });

  final String? title;
  final String? subtitle;
  final Widget? trailing;
  final Color? color;
  final Color? borderColor;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: Tokens.cardGap),
      decoration: BoxDecoration(
        color: color ?? Tokens.surface,
        borderRadius: BorderRadius.circular(Tokens.radiusCard),
        border: Border.all(color: borderColor ?? Tokens.divider, width: 1),
      ),
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (title != null || trailing != null)
            Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                if (title != null)
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          title!,
                          style: const TextStyle(
                            fontSize: 15.5,
                            fontWeight: FontWeight.w700,
                            color: Tokens.text,
                          ),
                        ),
                        if (subtitle != null)
                          Padding(
                            padding: const EdgeInsets.only(top: 4),
                            child: Text(
                              subtitle!,
                              style: const TextStyle(
                                fontSize: 12.5,
                                color: Tokens.textDim,
                                height: 1.45,
                              ),
                            ),
                          ),
                      ],
                    ),
                  )
                else
                  const Spacer(),
                if (trailing != null) ...[
                  const SizedBox(width: 8),
                  trailing!,
                ],
              ],
            ),
          if (title != null && subtitle == null && child is! SizedBox)
            const SizedBox(height: 12)
          else if (title != null && subtitle != null && child is! SizedBox)
            const SizedBox(height: 12),
          Material(
            type: MaterialType.transparency,
            child: child,
          ),
        ],
      ),
    );
  }
}

/// 分组小标题。
class SectionHeader extends StatelessWidget {
  const SectionHeader(this.title, {super.key, this.trailing});

  final String title;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 6, 4, 8),
      child: Row(
        children: [
          Expanded(
            child: Text(
              title,
              style: const TextStyle(
                fontSize: 12.5,
                fontWeight: FontWeight.w700,
                color: Tokens.textDim,
                letterSpacing: 0.4,
              ),
            ),
          ),
          ?trailing,
        ],
      ),
    );
  }
}

/// 状态标签：薄底 + 描边 + 彩色字（高对比度）。
class StatusChip extends StatelessWidget {
  const StatusChip({
    super.key,
    required this.label,
    this.color = Tokens.accent,
    this.icon,
  });

  final String label;
  final Color color;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.14),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color.withValues(alpha: 0.45)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 13, color: color),
            const SizedBox(width: 4),
          ],
          Text(
            label,
            style: TextStyle(
              fontSize: 11.5,
              fontWeight: FontWeight.w600,
              color: color,
            ),
          ),
        ],
      ),
    );
  }
}

/// 轻提示/告警横幅（纯语义高亮条）。
class InfoBanner extends StatelessWidget {
  const InfoBanner({
    super.key,
    required this.title,
    required this.body,
    this.color = Tokens.accent,
    this.icon = Icons.info_outline_rounded,
    this.action,
  });

  final String title;
  final String body;
  final Color color;
  final IconData icon;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(Tokens.radiusButton),
        border: Border.all(color: color.withValues(alpha: 0.4)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 17, color: color),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  title,
                  style: TextStyle(
                    fontSize: 13.5,
                    fontWeight: FontWeight.w700,
                    color: color,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            body,
            style: const TextStyle(
              fontSize: 12.5,
              color: Tokens.text,
              height: 1.5,
            ),
          ),
          if (action != null) ...[
            const SizedBox(height: 8),
            action!,
          ],
        ],
      ),
    );
  }
}

/// 键值对行。
class KeyValueRow extends StatelessWidget {
  const KeyValueRow({
    super.key,
    required this.label,
    required this.value,
    this.valueColor,
  });

  final String label;
  final String value;
  final Color? valueColor;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        children: [
          Text(
            label,
            style: const TextStyle(fontSize: 13.5, color: Tokens.textDim),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              value,
              textAlign: TextAlign.right,
              overflow: TextOverflow.ellipsis,
              maxLines: 2,
              style: TextStyle(
                fontSize: 13.5,
                fontWeight: FontWeight.w600,
                color: valueColor ?? Tokens.text,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 主操作按钮。
class PrimaryButton extends StatelessWidget {
  const PrimaryButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.icon,
    this.expand = true,
  });

  final String label;
  final VoidCallback? onPressed;
  final IconData? icon;
  final bool expand;

  @override
  Widget build(BuildContext context) {
    return FilledButton(
      onPressed: onPressed,
      child: Row(
        mainAxisSize: expand ? MainAxisSize.max : MainAxisSize.min,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          if (icon != null) ...[
            Icon(
              icon,
              size: 18,
              color: onPressed == null ? Tokens.textDim : Tokens.onAccent,
            ),
            const SizedBox(width: 6),
          ],
          Flexible(
            child: Text(
              label,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}

/// 次级按钮。
class SecondaryButton extends StatelessWidget {
  const SecondaryButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.icon,
    this.danger = false,
    this.expand = true,
  });

  final String label;
  final VoidCallback? onPressed;
  final IconData? icon;
  final bool danger;
  final bool expand;

  @override
  Widget build(BuildContext context) {
    final color = danger ? Tokens.danger : Tokens.accent;
    return OutlinedButton(
      onPressed: onPressed,
      style: OutlinedButton.styleFrom(
        foregroundColor: color,
        backgroundColor: Tokens.surfaceAlt.withValues(alpha: 0.45),
        side: BorderSide(
          color: onPressed == null ? Tokens.divider : color.withValues(alpha: 0.6),
        ),
      ),
      child: Row(
        mainAxisSize: expand ? MainAxisSize.max : MainAxisSize.min,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          if (icon != null) ...[
            Icon(
              icon,
              size: 17,
              color: onPressed == null ? Tokens.textDim : color,
            ),
            const SizedBox(width: 6),
          ],
          Flexible(
            child: Text(
              label,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}

/// 试听/朗读阶段 → 中文文案。
String previewStageLabel(String? stage) => switch (stage) {
      'connecting_online' => '正在连接在线引擎…',
      'synthesizing' => '正在合成语音…',
      'fallback_system' => '已回退系统 TTS…',
      'playing' => '播放中',
      _ => '播放中',
    };

/// 在线状态 → 中文文案。
String onlineStateLabel(dynamic state) {
  switch (state.toString()) {
    case 'OnlineState.off':
      return '已关闭';
    case 'OnlineState.ready':
      return '已就绪';
    case 'OnlineState.noKey':
      return '未配置密钥';
    case 'OnlineState.error':
      return '异常';
    default:
      return '未知';
  }
}

/// 统一提示条。
void showAppSnack(BuildContext context, String message, {bool danger = false}) {
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Text(
        message,
        style: TextStyle(
          color: danger ? Tokens.danger : Tokens.text,
          fontSize: 13.5,
        ),
      ),
      duration: const Duration(seconds: 3),
    ),
  );
}
