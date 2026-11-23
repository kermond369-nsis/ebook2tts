import 'package:flutter/material.dart';

/// 设计令牌（2026-11 界面重构版）。
///
/// 高对比度深邃墨夜配色：
/// - `#3DD6A3` 翡翠绿 / `#6CA8FF` 云海蓝 / `#F5B74F` 混合琥珀
/// - 主按钮绿底搭配 `#062019` 深色文字（对比度 > 9:1）
abstract final class Tokens {
  static const Color background = Color(0xFF0B1017);
  static const Color surface = Color(0xFF131B26);
  static const Color surfaceAlt = Color(0xFF1B2636);
  static const Color divider = Color(0xFF243247);

  static const Color accent = Color(0xFF3DD6A3);
  static const Color onAccent = Color(0xFF062019);

  static const Color cloud = Color(0xFF6CA8FF);
  static const Color hybrid = Color(0xFFF5B74F);

  static const Color text = Color(0xFFF3F5F8);
  static const Color textDim = Color(0xFF8D9BB0);
  static const Color danger = Color(0xFFF06A6A);

  static const double radiusCard = 18;
  static const double radiusButton = 14;
  static const double pagePadding = 16;
  static const double cardGap = 14;

  static ThemeData theme() {
    const scheme = ColorScheme.dark(
      primary: accent,
      onPrimary: onAccent,
      primaryContainer: Color(0xFF133A2E),
      onPrimaryContainer: Color(0xFFB4F4DC),
      secondary: cloud,
      onSecondary: Color(0xFF071A33),
      surface: surface,
      onSurface: text,
      surfaceContainerHighest: surfaceAlt,
      onSurfaceVariant: textDim,
      error: danger,
      onError: text,
      outline: divider,
      outlineVariant: divider,
    );

    final base = ThemeData(brightness: Brightness.dark, useMaterial3: true);
    return base.copyWith(
      colorScheme: scheme,
      scaffoldBackgroundColor: background,
      canvasColor: background,
      dividerColor: divider,
      splashFactory: InkRipple.splashFactory,
      textTheme: base.textTheme.apply(bodyColor: text, displayColor: text),
      appBarTheme: const AppBarTheme(
        backgroundColor: background,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        centerTitle: false,
        titleTextStyle: TextStyle(
          color: text,
          fontSize: 18,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.2,
        ),
        iconTheme: IconThemeData(color: text),
      ),
      cardTheme: CardThemeData(
        color: surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusCard),
          side: const BorderSide(color: divider, width: 1),
        ),
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: surface,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusCard),
          side: const BorderSide(color: divider, width: 1),
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        height: 68,
        indicatorColor: const Color(0x263DD6A3),
        iconTheme: WidgetStateProperty.resolveWith((states) {
          final selected = states.contains(WidgetState.selected);
          return IconThemeData(
            size: 22,
            color: selected ? accent : textDim,
          );
        }),
        labelTextStyle: WidgetStateProperty.resolveWith((states) {
          final selected = states.contains(WidgetState.selected);
          return TextStyle(
            fontSize: 11.5,
            fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
            color: selected ? accent : textDim,
          );
        }),
      ),
      segmentedButtonTheme: SegmentedButtonThemeData(
        style: ButtonStyle(
          backgroundColor: WidgetStateProperty.resolveWith((states) {
            if (states.contains(WidgetState.selected)) {
              return const Color(0x263DD6A3);
            }
            return background;
          }),
          foregroundColor: WidgetStateProperty.resolveWith((states) {
            return states.contains(WidgetState.selected) ? accent : textDim;
          }),
          side: WidgetStateProperty.all(const BorderSide(color: divider)),
          shape: WidgetStateProperty.all(
            RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(radiusButton),
            ),
          ),
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: accent,
          foregroundColor: onAccent,
          disabledBackgroundColor: surfaceAlt,
          disabledForegroundColor: textDim,
          elevation: 0,
          minimumSize: const Size(0, 46),
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(radiusButton),
          ),
          textStyle: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14.5),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: accent,
          foregroundColor: onAccent,
          disabledBackgroundColor: surfaceAlt,
          disabledForegroundColor: textDim,
          elevation: 0,
          minimumSize: const Size(0, 46),
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(radiusButton),
          ),
          textStyle: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14.5),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: accent,
          disabledForegroundColor: textDim,
          side: const BorderSide(color: divider),
          minimumSize: const Size(0, 44),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(radiusButton),
          ),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: accent,
          disabledForegroundColor: textDim,
          textStyle: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600),
        ),
      ),
      chipTheme: ChipThemeData(
        backgroundColor: surfaceAlt,
        selectedColor: const Color(0x2E3DD6A3),
        side: const BorderSide(color: divider),
        labelStyle: const TextStyle(fontSize: 12.5, color: text),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(10),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: background,
        hintStyle: const TextStyle(color: textDim, fontSize: 13.5),
        labelStyle: const TextStyle(color: textDim, fontSize: 13.5),
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(radiusButton),
          borderSide: const BorderSide(color: divider),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(radiusButton),
          borderSide: const BorderSide(color: divider),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(radiusButton),
          borderSide: const BorderSide(color: accent, width: 1.4),
        ),
        disabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(radiusButton),
          borderSide: const BorderSide(color: divider),
        ),
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.selected) ? onAccent : textDim,
        ),
        trackColor: WidgetStateProperty.resolveWith(
          (states) =>
              states.contains(WidgetState.selected) ? accent : surfaceAlt,
        ),
        trackOutlineColor:
            const WidgetStatePropertyAll(Colors.transparent),
      ),
      sliderTheme: const SliderThemeData(
        activeTrackColor: accent,
        thumbColor: accent,
        inactiveTrackColor: surfaceAlt,
        overlayColor: Color(0x333DD6A3),
      ),
      progressIndicatorTheme: const ProgressIndicatorThemeData(
        color: accent,
        linearTrackColor: surfaceAlt,
        circularTrackColor: surfaceAlt,
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: surfaceAlt,
        contentTextStyle: const TextStyle(color: text, fontSize: 13.5),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusButton),
          side: const BorderSide(color: divider),
        ),
      ),
      listTileTheme: const ListTileThemeData(
        iconColor: textDim,
        textColor: text,
      ),
      dividerTheme: const DividerThemeData(color: divider, thickness: 1),
    );
  }
}
