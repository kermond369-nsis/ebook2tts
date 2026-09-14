import 'package:flutter/material.dart';

/// P3 设计令牌（《P3-APP实施规格》§2，禁止偏离）。
///
/// 对比度已实测：薄荷绿 `#7DCEA0` 之上必须使用深色字 `#0F1419`（8.2–9.6:1），
/// 严禁薄荷绿底 + 白字（1.87:1 不达标）。
abstract final class Tokens {
  /// 页面背景（墨蓝）
  static const Color background = Color(0xFF0F1419);

  /// 卡片/表面
  static const Color surface = Color(0xFF1A2332);

  /// 主强调（薄荷绿）
  static const Color accent = Color(0xFF7DCEA0);

  /// 强调色之上的文字（深色，保证对比度）
  static const Color onAccent = Color(0xFF0F1419);

  /// 主文字
  static const Color text = Color(0xFFF4EFE6);

  /// 次要文字
  static const Color textDim = Color(0xFF8A93A6);

  /// 危险/警示
  static const Color danger = Color(0xFFE2725B);

  /// 派生色：比卡片更浅一档的表面（次级按钮/输入框内填充）
  static const Color surfaceAlt = Color(0xFF223047);

  /// 派生色：分隔线
  static const Color divider = Color(0xFF2A3547);

  /// 卡片圆角
  static const double radiusCard = 16;

  /// 按钮圆角
  static const double radiusButton = 12;

  /// 页面边距
  static const double pagePadding = 16;

  /// 卡片间距
  static const double cardGap = 12;

  static ThemeData theme() {
    const scheme = ColorScheme.dark(
      primary: accent,
      onPrimary: onAccent,
      primaryContainer: accent,
      onPrimaryContainer: onAccent,
      secondary: accent,
      onSecondary: onAccent,
      surface: surface,
      onSurface: text,
      surfaceContainerHighest: surfaceAlt,
      onSurfaceVariant: textDim,
      error: danger,
      onError: text,
      outline: divider,
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
          fontSize: 17,
          fontWeight: FontWeight.w600,
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
        ),
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: surface,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusCard),
        ),
      ),
      navigationBarTheme: const NavigationBarThemeData(
        backgroundColor: surface,
        indicatorColor: Colors.transparent,
        labelTextStyle: WidgetStatePropertyAll(
          TextStyle(fontSize: 11, color: textDim),
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
          textStyle: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: accent,
          disabledForegroundColor: textDim,
          side: const BorderSide(color: accent),
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
          textStyle: const TextStyle(fontSize: 14),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: background,
        hintStyle: const TextStyle(color: textDim, fontSize: 14),
        labelStyle: const TextStyle(color: textDim, fontSize: 14),
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
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
          borderSide: const BorderSide(color: accent),
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
          (states) => states.contains(WidgetState.selected)
              ? accent
              : surfaceAlt,
        ),
        trackOutlineColor:
            const WidgetStatePropertyAll(Colors.transparent),
      ),
      sliderTheme: const SliderThemeData(
        activeTrackColor: accent,
        thumbColor: accent,
        inactiveTrackColor: surfaceAlt,
        overlayColor: Color(0x337DCEA0),
      ),
      progressIndicatorTheme: const ProgressIndicatorThemeData(
        color: accent,
        linearTrackColor: surfaceAlt,
        circularTrackColor: surfaceAlt,
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: surfaceAlt,
        contentTextStyle: const TextStyle(color: text, fontSize: 14),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusButton),
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
