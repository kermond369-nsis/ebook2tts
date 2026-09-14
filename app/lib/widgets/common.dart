import 'package:flutter/material.dart';

import '../theme/tokens.dart';

/// 页面骨架：统一的边距与滚动容器（每页都有真实内容，不允许空白页）。
class PageBody extends StatelessWidget {
  const PageBody({super.key, required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(
        Tokens.pagePadding,
        8,
        Tokens.pagePadding,
        28,
      ),
      children: children,
    );
  }
}

/// 卡片：标题 + 可选副标题 + 内容。
class SectionCard extends StatelessWidget {
  const SectionCard({
    super.key,
    this.title,
    this.subtitle,
    this.trailing,
    this.color,
    required this.child,
  });

  final String? title;
  final String? subtitle;
  final Widget? trailing;
  final Color? color;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: Tokens.cardGap),
      decoration: BoxDecoration(
        color: color ?? Tokens.surface,
        borderRadius: BorderRadius.circular(Tokens.radiusCard),
      ),
      padding: const EdgeInsets.all(14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (title != null)
            Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title!,
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
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
                ),
                ?trailing,
              ],
            ),
          if (title != null) const SizedBox(height: 10),
          child,
        ],
      ),
    );
  }
}

/// 状态标签。
class StatusChip extends StatelessWidget {
  const StatusChip({super.key, required this.label, this.color});

  final String label;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final bg = color ?? Tokens.accent;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: const TextStyle(
          // 强调色之上必须深色字（对比度 8.2–9.6:1）
          color: Tokens.onAccent,
          fontSize: 12,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

/// 键值行（诊断页/详情页）。
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
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 96,
            child: Text(
              label,
              style: const TextStyle(fontSize: 13.5, color: Tokens.textDim),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: TextStyle(
                fontSize: 13.5,
                color: valueColor ?? Tokens.text,
                height: 1.4,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 主按钮（薄荷绿底 + 深色字）。
class PrimaryButton extends StatelessWidget {
  const PrimaryButton({
    super.key,
    required this.label,
    this.onPressed,
    this.icon,
    this.expand = true,
  });

  final String label;
  final VoidCallback? onPressed;
  final IconData? icon;
  final bool expand;

  @override
  Widget build(BuildContext context) {
    final child = Row(
      mainAxisSize: expand ? MainAxisSize.max : MainAxisSize.min,
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        if (icon != null) ...[
          Icon(icon, size: 18, color: Tokens.onAccent),
          const SizedBox(width: 6),
        ],
        Flexible(
          child: Text(
            label,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
          ),
        ),
      ],
    );
    return ElevatedButton(onPressed: onPressed, child: child);
  }
}

/// 次按钮（描边）。
class SecondaryButton extends StatelessWidget {
  const SecondaryButton({
    super.key,
    required this.label,
    this.onPressed,
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
        side: BorderSide(
          color: onPressed == null ? Tokens.divider : color,
        ),
      ),
      child: Row(
        mainAxisSize: expand ? MainAxisSize.max : MainAxisSize.min,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 17, color: onPressed == null ? Tokens.textDim : color),
            const SizedBox(width: 6),
          ],
          Flexible(
            child: Text(
              label,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
            ),
          ),
        ],
      ),
    );
  }
}

/// 体积格式化：按 1 兆 = 100 万字节（与清单实测值口径一致）。
String formatMb(int bytes) => '${bytes ~/ 1000000} 兆';

/// 下载阶段 → 中文文案。
String phaseLabel(String phase) {
  switch (phase) {
    case 'probe':
      return '正在选择最快镜像';
    case 'download':
      return '下载中';
    case 'verify':
      return '校验文件完整性';
    case 'extract':
      return '解压中';
    case 'promote':
      return '安装收尾';
    case 'done':
      return '完成';
    case 'error':
      return '失败';
    default:
      return '等待中';
  }
}

/// 引擎状态 → 中文文案。
String engineStateLabel(dynamic state) {
  switch (state.toString()) {
    case 'EngineState.noModel':
      return '未安装模型';
    case 'EngineState.loading':
      return '模型载入中';
    case 'EngineState.ready':
      return '就绪';
    case 'EngineState.reloading':
      return '重载中';
    case 'EngineState.error':
      return '故障';
    default:
      return '未知';
  }
}

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
          fontSize: 14,
        ),
      ),
      duration: const Duration(seconds: 3),
    ),
  );
}
