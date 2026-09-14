import 'package:flutter/material.dart';

import '../content/legal_texts.dart';
import '../theme/tokens.dart';
import '../widgets/legal_doc_view.dart';

/// 免责声明全文页（逐字取自仓库 docs/，不得改写）。
class DisclaimerPage extends StatelessWidget {
  const DisclaimerPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('免责声明')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(
          Tokens.pagePadding,
          8,
          Tokens.pagePadding,
          32,
        ),
        children: [
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Tokens.surface,
              borderRadius: BorderRadius.circular(Tokens.radiusCard),
              border: Border.all(color: Tokens.accent),
            ),
            child: const Text(
              '本文为产品条款与风险告知，请在使用在线朗读功能前完整阅读；'
              '文中加粗部分为与你有重大利害关系的条款。',
              style: TextStyle(
                fontSize: 13,
                color: Tokens.text,
                height: 1.6,
              ),
            ),
          ),
          const SizedBox(height: 12),
          const LegalDocView(markdown: disclaimerFullText),
        ],
      ),
    );
  }
}
