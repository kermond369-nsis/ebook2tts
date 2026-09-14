import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../content/legal_texts.dart';
import '../theme/tokens.dart';
import 'legal_doc_view.dart';

/// 弹出 Token Plan 风险确认弹窗。
///
/// 返回 `true` 表示用户滑到底、勾选并点了「同意并继续」；`false`/`null` 表示取消
/// （含返回键/手势关闭），此时**不记录同意**。
Future<bool?> showTokenPlanRiskDialog(BuildContext context) {
  return showDialog<bool>(
    context: context,
    barrierDismissible: false, // 模态：不可点外部关闭，仅「取消」或「同意」
    builder: (_) => const TokenPlanRiskDialog(),
  );
}

/// 风险确认弹窗（严格按《弹窗文本-TokenPlan风险确认.md》渲染）。
///
/// 门槛规则（硬性，逐条对应文档 §一/§三）：
/// 1. 正文必须滑动到最底部，「同意并继续」才由置灰转为可点；
/// 2. 未到底时按钮不可点，且按钮下方常驻提示"请滑动阅读至底部"，误触会抖动；
/// 3. 勾选框在滑到底后出现，需**同时勾选**并点击「同意并继续」；
/// 4. 返回键/手势关闭 = 取消（不记录同意）；
/// 5. 绝不预勾选、绝不静默接受。
class TokenPlanRiskDialog extends StatefulWidget {
  const TokenPlanRiskDialog({super.key});

  @override
  State<TokenPlanRiskDialog> createState() => _TokenPlanRiskDialogState();
}

class _TokenPlanRiskDialogState extends State<TokenPlanRiskDialog>
    with TickerProviderStateMixin {
  final ScrollController _scroll = ScrollController();

  /// 是否已滑动到正文底部（本弹窗的门槛开关）。
  bool _reachedBottom = false;
  bool _checked = false;

  late final AnimationController _shake = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 420),
  );
  late final AnimationController _hintPulse = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 260),
  );

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _checkBottom(_scroll.position);
    });
  }

  @override
  void dispose() {
    _scroll.removeListener(_onScroll);
    _scroll.dispose();
    _shake.dispose();
    _hintPulse.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_scroll.hasClients) return;
    _checkBottom(_scroll.position);
  }

  void _checkBottom(ScrollPosition position) {
    final atBottom =
        position.maxScrollExtent <= 0 ||
        position.pixels >= position.maxScrollExtent - 12;
    if (atBottom && !_reachedBottom) {
      setState(() => _reachedBottom = true);
    }
  }

  bool get _canAgree => _reachedBottom && _checked;

  void _onInvalidTap() {
    _shake.forward(from: 0);
    _hintPulse.forward(from: 0);
  }

  @override
  Widget build(BuildContext context) {
    final maxHeight = MediaQuery.of(context).size.height * 0.88;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) {
          // 返回键/手势关闭 = 取消（不记录同意）
          Navigator.of(context).pop(false);
        }
      },
      child: Dialog(
        insetPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 24),
        child: ConstrainedBox(
          constraints: BoxConstraints(maxHeight: maxHeight),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _header(),
              Expanded(
                child: SingleChildScrollView(
                  controller: _scroll,
                  padding: const EdgeInsets.fromLTRB(16, 12, 16, 20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // 【置顶 · 最大字号 · 加粗】三句白话（逐字不改）
                      for (final line in tokenPlanPunchLines)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 14),
                          child: Text(
                            line,
                            style: const TextStyle(
                              fontSize: 21, // ≥ 正文 1.5 倍
                              fontWeight: FontWeight.w800,
                              height: 1.4,
                              color: Tokens.text,
                            ),
                          ),
                        ),
                      const Divider(color: Tokens.divider, height: 26),
                      // 正式条款（正文，可滑动）
                      const LegalDocView(markdown: tokenPlanClauses),
                      const SizedBox(height: 36),
                    ],
                  ),
                ),
              ),
              _footer(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _header() {
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 12),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: Tokens.divider)),
      ),
      child: const Row(
        children: [
          Icon(Icons.warning_amber_rounded, color: Tokens.danger, size: 20),
          SizedBox(width: 8),
          Expanded(
            child: Text(
              'Token Plan 风险确认',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w700,
                color: Tokens.text,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _footer() {
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 14),
      decoration: const BoxDecoration(
        border: Border(top: BorderSide(color: Tokens.divider)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (_reachedBottom)
            Theme(
              data: Theme.of(context).copyWith(
                checkboxTheme: CheckboxThemeData(
                  fillColor: WidgetStateProperty.resolveWith(
                    (states) => states.contains(WidgetState.selected)
                        ? Tokens.accent
                        : Colors.transparent,
                  ),
                  checkColor: const WidgetStatePropertyAll(Tokens.onAccent),
                  side: const BorderSide(color: Tokens.textDim),
                ),
              ),
              child: CheckboxListTile(
                value: _checked,
                onChanged: (v) => setState(() => _checked = v ?? false),
                dense: true,
                contentPadding: EdgeInsets.zero,
                controlAffinity: ListTileControlAffinity.leading,
                title: const Text(
                  tokenPlanConfirmSentence,
                  style: TextStyle(fontSize: 12.5, color: Tokens.text, height: 1.45),
                ),
              ),
            ),
          const SizedBox(height: 6),
          Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: () => Navigator.of(context).pop(false),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: Tokens.textDim,
                    side: const BorderSide(color: Tokens.divider),
                    minimumSize: const Size(0, 46),
                  ),
                  child: const Text('取消'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: AnimatedBuilder(
                  animation: _shake,
                  builder: (context, child) {
                    final t = _shake.value;
                    final offset = math.sin(t * math.pi * 6) * 7 * (1 - t);
                    return Transform.translate(
                      offset: Offset(offset, 0),
                      child: child,
                    );
                  },
                  child: Stack(
                    children: [
                      SizedBox(
                        width: double.infinity,
                        child: ElevatedButton(
                          onPressed: _canAgree
                              ? () => Navigator.of(context).pop(true)
                              : null,
                          child: const Text('同意并继续'),
                        ),
                      ),
                      if (!_canAgree)
                        // 未达门槛：拦截点击并给出抖动提示（绝不静默接受）
                        Positioned.fill(
                          child: GestureDetector(
                            behavior: HitTestBehavior.opaque,
                            onTap: _onInvalidTap,
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ],
          ),
          if (!_reachedBottom)
            // 常驻提示（置于按钮下方）
            AnimatedBuilder(
              animation: _hintPulse,
              builder: (context, child) => Transform.scale(
                scale: 1 + 0.08 * math.sin(_hintPulse.value * math.pi),
                child: child,
              ),
              child: const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text(
                  '请滑动阅读至底部',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: Tokens.danger,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
