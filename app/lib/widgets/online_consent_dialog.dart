import 'package:flutter/material.dart';

import '../content/legal_texts.dart';
import '../theme/tokens.dart';

/// 开启在线朗读前的单独同意（《个人信息保护法》第二十三条）。
///
/// 返回 `true` 表示已勾选并确认开启；`false`/`null` 表示取消（开关保持关闭）。
Future<bool?> showOnlineConsentDialog(BuildContext context) {
  return showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (_) => const _OnlineConsentDialog(),
  );
}

class _OnlineConsentDialog extends StatefulWidget {
  const _OnlineConsentDialog();

  @override
  State<_OnlineConsentDialog> createState() => _OnlineConsentDialogState();
}

class _OnlineConsentDialogState extends State<_OnlineConsentDialog> {
  bool _checked = false;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text(
        '开启在线朗读前请确认',
        style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
      ),
      content: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              onlineConsentIntro,
              style: TextStyle(fontSize: 13, color: Tokens.text, height: 1.6),
            ),
            const SizedBox(height: 10),
            CheckboxListTile(
              value: _checked,
              onChanged: (v) => setState(() => _checked = v ?? false),
              dense: true,
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
              title: const Text(
                onlineConsentSentence,
                style: TextStyle(fontSize: 12.5, color: Tokens.text, height: 1.45),
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          style: TextButton.styleFrom(foregroundColor: Tokens.textDim),
          child: const Text('取消'),
        ),
        ElevatedButton(
          // 未勾选不可开启（单独同意）
          onPressed:
              _checked ? () => Navigator.of(context).pop(true) : null,
          child: const Text('同意并开启'),
        ),
      ],
    );
  }
}
