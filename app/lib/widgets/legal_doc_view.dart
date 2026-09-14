import 'package:flutter/material.dart';

import '../theme/tokens.dart';

/// 极简法律文本渲染器（支持 # / ## / 引用 / 列表 / 简易表格 / **加粗**）。
///
/// 用于免责声明全文与 Token Plan 正式条款，保证逐字展示文档原文。
class LegalDocView extends StatelessWidget {
  const LegalDocView({
    super.key,
    required this.markdown,
    this.baseFontSize = 13.5,
    this.headingColor = Tokens.accent,
  });

  final String markdown;
  final double baseFontSize;
  final Color headingColor;

  @override
  Widget build(BuildContext context) {
    final blocks = <Widget>[];
    final lines = markdown.split('\n');
    var i = 0;
    while (i < lines.length) {
      final line = lines[i].trimRight();
      final trimmed = line.trim();
      if (trimmed.isEmpty) {
        blocks.add(const SizedBox(height: 8));
        i++;
        continue;
      }
      if (trimmed.startsWith('|')) {
        // 表格块
        final rows = <String>[];
        while (i < lines.length && lines[i].trim().startsWith('|')) {
          rows.add(lines[i].trim());
          i++;
        }
        blocks.add(_table(rows));
        continue;
      }
      if (trimmed.startsWith('### ')) {
        blocks.add(_heading(trimmed.substring(4), baseFontSize + 0.5));
      } else if (trimmed.startsWith('## ')) {
        blocks.add(_heading(trimmed.substring(3), baseFontSize + 2));
      } else if (trimmed.startsWith('# ')) {
        blocks.add(_heading(trimmed.substring(2), baseFontSize + 4));
      } else if (trimmed.startsWith('> ')) {
        blocks.add(_quote(trimmed.substring(2)));
      } else if (trimmed.startsWith('- ')) {
        blocks.add(_bullet(trimmed.substring(2)));
      } else if (RegExp(r'^\d+\.\s').hasMatch(trimmed)) {
        blocks.add(_numbered(trimmed));
      } else if (trimmed.startsWith('**') && trimmed.endsWith('**') && trimmed.length > 4) {
        blocks.add(_paragraph(trimmed));
      } else {
        blocks.add(_paragraph(trimmed));
      }
      i++;
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: blocks,
    );
  }

  Widget _heading(String text, double size) {
    return Padding(
      padding: const EdgeInsets.only(top: 14, bottom: 6),
      child: Text(
        text,
        style: TextStyle(
          fontSize: size,
          fontWeight: FontWeight.w700,
          color: headingColor,
          height: 1.4,
        ),
      ),
    );
  }

  Widget _paragraph(String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: RichText(
        text: TextSpan(
          style: TextStyle(
            fontSize: baseFontSize,
            color: Tokens.text,
            height: 1.65,
          ),
          children: _inline(text),
        ),
      ),
    );
  }

  Widget _quote(String text) {
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.fromLTRB(10, 8, 10, 8),
      decoration: const BoxDecoration(
        color: Tokens.background,
        border: Border(
          left: BorderSide(color: Tokens.accent, width: 3),
        ),
      ),
      child: RichText(
        text: TextSpan(
          style: TextStyle(
            fontSize: baseFontSize,
            color: Tokens.text,
            height: 1.65,
          ),
          children: _inline(text),
        ),
      ),
    );
  }

  Widget _bullet(String text) {
    return Padding(
      padding: const EdgeInsets.only(left: 4, bottom: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.only(top: 5, right: 8),
            child: Icon(Icons.circle, size: 5, color: Tokens.textDim),
          ),
          Expanded(
            child: RichText(
              text: TextSpan(
                style: TextStyle(
                  fontSize: baseFontSize,
                  color: Tokens.text,
                  height: 1.65,
                ),
                children: _inline(text),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _numbered(String text) {
    final match = RegExp(r'^(\d+)\.\s(.*)$').firstMatch(text);
    final num = match?.group(1) ?? '';
    final body = match?.group(2) ?? text;
    return Padding(
      padding: const EdgeInsets.only(left: 4, bottom: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 1, right: 6),
            child: Text(
              '$num.',
              style: TextStyle(
                fontSize: baseFontSize,
                color: Tokens.accent,
                fontWeight: FontWeight.w600,
                height: 1.65,
              ),
            ),
          ),
          Expanded(
            child: RichText(
              text: TextSpan(
                style: TextStyle(
                  fontSize: baseFontSize,
                  color: Tokens.text,
                  height: 1.65,
                ),
                children: _inline(body),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _table(List<String> rows) {
    final children = <Widget>[];
    for (final row in rows) {
      final cells = row
          .split('|')
          .map((c) => c.trim())
          .where((c) => c.isNotEmpty)
          .toList();
      if (cells.isEmpty) continue;
      final isSeparator =
          cells.every((c) => RegExp(r'^:?-{2,}:?$').hasMatch(c));
      if (isSeparator) continue;
      children.add(
        Container(
          decoration: const BoxDecoration(
            border: Border(bottom: BorderSide(color: Tokens.divider)),
          ),
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (var c = 0; c < cells.length; c++)
                Expanded(
                  flex: c == 0 ? 2 : 3,
                  child: Padding(
                    padding: EdgeInsets.only(right: c == cells.length - 1 ? 0 : 6),
                    child: RichText(
                      text: TextSpan(
                        style: TextStyle(
                          fontSize: baseFontSize - 1,
                          color: Tokens.text,
                          height: 1.55,
                        ),
                        children: _inline(cells[c]),
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: children),
    );
  }

  List<TextSpan> _inline(String text) {
    final spans = <TextSpan>[];
    final pattern = RegExp(r'\*\*(.+?)\*\*');
    var index = 0;
    for (final match in pattern.allMatches(text)) {
      if (match.start > index) {
        spans.add(TextSpan(text: _stripMarks(text.substring(index, match.start))));
      }
      spans.add(TextSpan(
        text: _stripMarks(match.group(1) ?? ''),
        style: const TextStyle(fontWeight: FontWeight.w700),
      ));
      index = match.end;
    }
    if (index < text.length) {
      spans.add(TextSpan(text: _stripMarks(text.substring(index))));
    }
    return spans.isEmpty ? <TextSpan>[TextSpan(text: _stripMarks(text))] : spans;
  }

  String _stripMarks(String text) => text.replaceAll('`', '');
}
