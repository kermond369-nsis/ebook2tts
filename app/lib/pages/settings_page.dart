import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../content/legal_texts.dart';
import '../engine/engine_service.dart';
import '../platform/system_bridge.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';
import '../widgets/online_consent_dialog.dart';
import '../widgets/token_plan_dialog.dart';
import 'disclaimer_page.dart';

/// 设置页（IM-305）。
///
/// ① 语速 ② 在线朗读总开关（默认关）③ 密钥类型（按量计费 / Token Plan）
/// ④ API Key 输入 ⑤ Base URL 展示（自动跟随，可高级覆盖）⑥ 允许使用数据流量（默认关）
/// ⑦ 自定义下载镜像 ⑧ 系统 TTS 设置入口 / 完整免责声明入口。
class SettingsPage extends ConsumerStatefulWidget {
  const SettingsPage({super.key});

  @override
  ConsumerState<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends ConsumerState<SettingsPage> {
  final TextEditingController _apiKey = TextEditingController();
  final TextEditingController _baseUrlOverride = TextEditingController();
  final TextEditingController _mirror = TextEditingController();

  double _speed = 1.0;
  bool _speedSynced = false;
  bool _mirrorSynced = false;
  bool _advancedBase = false;
  String? _keyError;
  bool _validating = false;

  @override
  void dispose() {
    _apiKey.dispose();
    _baseUrlOverride.dispose();
    _mirror.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final config = ref.watch(configProvider).value;
    final status = ref.watch(statusProvider).value;

    if (config == null) {
      return const PageBody(
        children: [
          SectionCard(
            child: Center(
              child: Padding(
                padding: EdgeInsets.all(18),
                child: CircularProgressIndicator(),
              ),
            ),
          ),
        ],
      );
    }

    if (!_speedSynced) {
      _speed = config.speed;
      _speedSynced = true;
    }
    if (!_mirrorSynced) {
      _mirror.text = config.customMirror;
      _mirrorSynced = true;
    }

    final isPlan = config.keyKind == 'plan';
    final canEditKey = !isPlan || config.tokenPlanAccepted;
    final onlineError = _keyError ??
        (status?.online == OnlineState.error ? '平台方拒绝了当前密钥，请检查后重试。' : null);

    return PageBody(
      children: [
        // ① 语速
        SectionCard(
          title: '语速',
          subtitle: '影响全部朗读（离线与在线）。拖动后松手即保存。',
          trailing: StatusChip(label: '${_speed.toStringAsFixed(1)} 倍'),
          child: Slider(
            value: _speed,
            min: 0.5,
            max: 2.0,
            divisions: 15,
            label: '${_speed.toStringAsFixed(1)} 倍',
            onChanged: (v) => setState(() => _speed = v),
            onChangeEnd: (v) async {
              await ref.read(configProvider.notifier).save(speed: v);
              if (context.mounted) {
                showAppSnack(context, '语速已保存：${v.toStringAsFixed(1)} 倍');
              }
            },
          ),
        ),

        // ② 在线朗读总开关（默认关）
        SectionCard(
          title: '在线朗读',
          subtitle: '默认关闭。关闭时全部朗读由本机模型完成，不向任何服务器发送文本。',
          trailing: StatusChip(
            label: status == null ? '读取中' : onlineStateLabel(status.online),
            color: (status?.online == OnlineState.ready)
                ? Tokens.accent
                : Tokens.textDim,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Expanded(
                    child: Text(
                      '在线朗读总开关',
                      style: TextStyle(fontSize: 14.5, color: Tokens.text),
                    ),
                  ),
                  Switch(
                    value: config.onlineEnabled,
                    onChanged: (v) => _toggleOnline(v, config),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              Text(
                config.onlineEnabled
                    ? '已开启：朗读文本将发送至第三方服务商合成。可随时关闭以撤回同意。'
                    : '已关闭：朗读只在本机完成。开启前会先请你单独确认外发告知。',
                style: const TextStyle(
                  fontSize: 12.5,
                  color: Tokens.textDim,
                  height: 1.5,
                ),
              ),
              if (onlineError != null) ...[
                const SizedBox(height: 10),
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: Tokens.background,
                    borderRadius: BorderRadius.circular(Tokens.radiusButton),
                    border: Border.all(color: Tokens.danger),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const Icon(Icons.error_outline,
                              size: 16, color: Tokens.danger),
                          const SizedBox(width: 6),
                          const Expanded(
                            child: Text(
                              '密钥未能通过校验',
                              style: TextStyle(
                                fontSize: 13.5,
                                fontWeight: FontWeight.w700,
                                color: Tokens.danger,
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 6),
                      Text(
                        onlineError,
                        style: const TextStyle(
                          fontSize: 12.5,
                          color: Tokens.text,
                          height: 1.5,
                        ),
                      ),
                      const SizedBox(height: 10),
                      const Text(
                        '可以试试：改用按量计费 API Key，或关闭在线朗读（两条路都不影响离线朗读）。',
                        style: TextStyle(
                          fontSize: 12,
                          color: Tokens.textDim,
                          height: 1.5,
                        ),
                      ),
                      const SizedBox(height: 10),
                      Row(
                        children: [
                          Expanded(
                            child: SecondaryButton(
                              label: '改用按量计费',
                              icon: Icons.swap_horiz,
                              onPressed: () => _switchKeyKind('billing', config),
                            ),
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: SecondaryButton(
                              label: '关闭在线朗读',
                              icon: Icons.power_settings_new,
                              danger: true,
                              onPressed: () => _toggleOnline(false, config),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ],
          ),
        ),

        // ③ 密钥类型 + ④ API Key + ⑤ Base URL
        SectionCard(
          title: '在线密钥',
          subtitle: '两类密钥与接口地址相互绑定、不可混用：按量计费用 sk- 开头，'
              'Token Plan 用 tp- 开头。',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _OptionRow(
                selected: !isPlan,
                title: '按量计费 API Key（推荐）',
                subtitle: '无订阅合规风险，不用不花钱；建议优先使用。',
                onTap: () => _switchKeyKind('billing', config),
              ),
              const SizedBox(height: 8),
              _OptionRow(
                selected: isPlan,
                title: 'Token Plan 套餐密钥',
                subtitle: '可能违反平台方使用规则（封禁、停服、费用不退），需先完成风险确认。',
                danger: true,
                onTap: () => _switchKeyKind('plan', config),
              ),
              if (isPlan || config.tokenPlanAccepted) ...[
                const SizedBox(height: 10),
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: Tokens.background,
                    borderRadius: BorderRadius.circular(Tokens.radiusButton),
                    border: Border.all(color: Tokens.danger),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Row(
                        children: [
                          Icon(Icons.warning_amber_rounded,
                              size: 16, color: Tokens.danger),
                          SizedBox(width: 6),
                          Expanded(
                            child: Text(
                              'Token Plan 风险提示',
                              style: TextStyle(
                                fontSize: 13,
                                fontWeight: FontWeight.w700,
                                color: Tokens.danger,
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 6),
                      const Text(
                        tokenPlanResidentWarning,
                        style: TextStyle(
                          fontSize: 12.5,
                          color: Tokens.text,
                          height: 1.5,
                        ),
                      ),
                      TextButton(
                        onPressed: () => showTokenPlanRiskDialog(context),
                        child: const Text('查看风险说明全文'),
                      ),
                    ],
                  ),
                ),
              ],
              const SizedBox(height: 12),
              TextField(
                controller: _apiKey,
                enabled: canEditKey,
                obscureText: true,
                decoration: InputDecoration(
                  labelText: 'API Key（密钥）',
                  hintText: isPlan ? 'tp-开头的套餐密钥' : 'sk-开头的按量计费密钥',
                  helperText: canEditKey
                      ? '密钥只保存在本机，用于直接向平台方发起请求；本 App 不上传、不转发。'
                      : '选择 Token Plan 后，需先完成风险确认才能填写密钥。',
                  helperMaxLines: 3,
                ),
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  Expanded(
                    child: PrimaryButton(
                      label: '保存密钥',
                      icon: Icons.save_outlined,
                      onPressed: canEditKey
                          ? () async {
                              final key = _apiKey.text.trim();
                              if (key.isEmpty) {
                                showAppSnack(context, '请先填写密钥', danger: true);
                                return;
                              }
                              await ref
                                  .read(configProvider.notifier)
                                  .save(apiKey: key);
                              _apiKey.clear();
                              if (context.mounted) {
                                showAppSnack(context, '密钥已保存到本机');
                              }
                            }
                          : null,
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: SecondaryButton(
                      label: _validating ? '校验中…' : '校验密钥',
                      icon: Icons.verified_outlined,
                      onPressed: (!canEditKey || _validating)
                          ? null
                          : () => _validateKey(config),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                config.apiKeyMasked.isEmpty
                    ? '尚未保存密钥'
                    : '已保存密钥：${config.apiKeyMasked}',
                style: const TextStyle(fontSize: 12.5, color: Tokens.textDim),
              ),
              const Divider(height: 26),
              const Text(
                '接口地址（Base URL）',
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: Tokens.text,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                config.baseUrl,
                style: const TextStyle(
                  fontSize: 12.5,
                  color: Tokens.accent,
                  height: 1.5,
                ),
              ),
              const SizedBox(height: 4),
              const Text(
                '默认跟随密钥类型自动切换；一般无需修改。',
                style: TextStyle(fontSize: 12, color: Tokens.textDim),
              ),
              if (!_advancedBase)
                TextButton(
                  onPressed: () => setState(() => _advancedBase = true),
                  child: const Text('高级：手动覆盖接口地址'),
                )
              else ...[
                const SizedBox(height: 8),
                TextField(
                  controller: _baseUrlOverride,
                  decoration: const InputDecoration(
                    hintText: '例如 https://api.xiaomimimo.com/v1',
                  ),
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(
                      child: PrimaryButton(
                        label: '应用覆盖',
                        onPressed: () async {
                          final url = _baseUrlOverride.text.trim();
                          if (url.isEmpty) {
                            showAppSnack(context, '覆盖地址不能为空；'
                                '想恢复自动跟随请点右侧按钮', danger: true);
                            return;
                          }
                          await ref
                              .read(configProvider.notifier)
                              .save(baseUrl: url);
                          if (context.mounted) {
                            showAppSnack(context, '接口地址已覆盖');
                          }
                        },
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: SecondaryButton(
                        label: '恢复自动跟随',
                        onPressed: () async {
                          _baseUrlOverride.clear();
                          await ref
                              .read(configProvider.notifier)
                              .save(baseUrl: '');
                          if (context.mounted) {
                            showAppSnack(context, '已恢复为跟随密钥类型自动切换');
                          }
                        },
                      ),
                    ),
                  ],
                ),
              ],
            ],
          ),
        ),

        // ⑥ 数据流量 + ⑦ 自定义镜像
        SectionCard(
          title: '网络与下载',
          subtitle: '省流策略：默认只用无线网络下载模型与在线朗读。',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Expanded(
                    child: Text(
                      '允许使用数据流量',
                      style: TextStyle(fontSize: 14.5, color: Tokens.text),
                    ),
                  ),
                  Switch(
                    value: config.allowMobileData,
                    onChanged: (v) async {
                      await ref
                          .read(configProvider.notifier)
                          .save(allowMobileData: v);
                      if (context.mounted) {
                        showAppSnack(
                          context,
                          v ? '已允许使用数据流量' : '已关闭：仅无线网络下联网',
                        );
                      }
                    },
                  ),
                ],
              ),
              const SizedBox(height: 4),
              Text(
                config.allowMobileData
                    ? '已允许：没有无线网络时也可下载模型、使用在线朗读。'
                    : '已关闭（默认）：只在无线网络下下载模型与联网朗读。',
                style: const TextStyle(
                  fontSize: 12.5,
                  color: Tokens.textDim,
                  height: 1.5,
                ),
              ),
              const Divider(height: 26),
              const Text(
                '自定义下载镜像',
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: Tokens.text,
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _mirror,
                decoration: const InputDecoration(
                  hintText: '留空使用官方镜像；例如 https://你的镜像地址/模型目录',
                ),
              ),
              const SizedBox(height: 8),
              PrimaryButton(
                label: '保存镜像',
                icon: Icons.cloud_download_outlined,
                onPressed: () async {
                  await ref
                      .read(configProvider.notifier)
                      .save(customMirror: _mirror.text.trim());
                  if (context.mounted) {
                    showAppSnack(
                      context,
                      _mirror.text.trim().isEmpty
                          ? '已恢复使用官方镜像'
                          : '镜像已保存，下载将优先使用',
                    );
                  }
                },
              ),
            ],
          ),
        ),

        // ⑧ 入口：系统 TTS 设置 / 完整免责声明
        SectionCard(
          title: '系统与法律',
          child: Column(
            children: [
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.settings_voice_outlined),
                title: const Text(
                  '系统文字转语音设置',
                  style: TextStyle(fontSize: 14.5),
                ),
                subtitle: const Text(
                  '把「书声本地」设为系统默认朗读引擎',
                  style: TextStyle(fontSize: 12, color: Tokens.textDim),
                ),
                trailing: const Icon(Icons.chevron_right, color: Tokens.textDim),
                onTap: () => _openSystemSettings(),
              ),
              const Divider(height: 8),
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.gavel_outlined),
                title: const Text(
                  '完整免责声明',
                  style: TextStyle(fontSize: 14.5),
                ),
                subtitle: const Text(
                  '在线朗读的风险、费用与个人信息告知（全文）',
                  style: TextStyle(fontSize: 12, color: Tokens.textDim),
                ),
                trailing: const Icon(Icons.chevron_right, color: Tokens.textDim),
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) => const DisclaimerPage(),
                  ),
                ),
              ),
            ],
          ),
        ),

        SectionCard(
          title: '关于',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const KeyValueRow(label: '应用', value: '书声本地（离线电子书朗读）'),
              const KeyValueRow(label: '版本', value: '0.2.0-alpha.1（P3 界面）'),
              const KeyValueRow(label: '作者', value: 'Kermond'),
              const KeyValueRow(label: '许可', value: 'MIT'),
              const SizedBox(height: 4),
              const Text(
                '界面与朗读引擎同属一个应用；本页面为演示数据，'
                '真实模型与合成由本机引擎提供。',
                style: TextStyle(
                  fontSize: 12,
                  color: Tokens.textDim,
                  height: 1.5,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  // ------------------------------------------------------------ 交互

  Future<void> _toggleOnline(bool value, AppConfig config) async {
    if (value) {
      // 开启前单独同意（《个人信息保护法》第二十三条）
      final agreed = await showOnlineConsentDialog(context);
      if (agreed != true) {
        if (mounted) {
          showAppSnack(context, '已取消开启，在线朗读保持关闭');
        }
        return;
      }
      await ref.read(configProvider.notifier).save(onlineEnabled: true);
      if (mounted) {
        showAppSnack(context, '在线朗读已开启');
      }
    } else {
      await ref.read(configProvider.notifier).save(onlineEnabled: false);
      if (mounted) {
        showAppSnack(context, '在线朗读已关闭，文本只在本机合成');
      }
    }
  }

  Future<void> _switchKeyKind(String kind, AppConfig config) async {
    if (kind == config.keyKind) {
      return;
    }
    if (kind == 'plan' && !config.tokenPlanAccepted) {
      // 门槛：滑到底 + 勾选 + 同意，三者齐备才落状态；取消则回退，不记录同意
      final agreed = await showTokenPlanRiskDialog(context);
      if (agreed != true) {
        if (mounted) {
          showAppSnack(context, '已取消，仍保持「按量计费 API Key」');
        }
        return;
      }
      await ref
          .read(configProvider.notifier)
          .save(keyKind: 'plan', tokenPlanAccepted: true);
      if (mounted) {
        showAppSnack(context, '已切换为 Token Plan，请填写 tp- 开头的密钥');
      }
      return;
    }
    await ref.read(configProvider.notifier).save(keyKind: kind);
    if (mounted) {
      showAppSnack(
        context,
        kind == 'plan' ? '已切换为 Token Plan' : '已切换为按量计费 API Key',
      );
    }
  }

  Future<void> _validateKey(AppConfig config) async {
    final key = _apiKey.text.trim();
    if (key.isEmpty) {
      showAppSnack(context, '请先填写密钥再校验', danger: true);
      return;
    }
    setState(() {
      _validating = true;
      _keyError = null;
    });
    final error = await ref.read(configProvider.notifier).validateKey(
          keyKind: config.keyKind,
          apiKey: key,
        );
    if (!mounted) {
      return;
    }
    setState(() {
      _validating = false;
      _keyError = error;
    });
    if (error == null) {
      showAppSnack(context, '校验通过：密钥可用');
    }
  }

  Future<void> _openSystemSettings() async {
    final result = await SystemBridge.openSystemTtsSettings();
    if (!mounted) return;
    switch (result) {
      case 'tts':
        showAppSnack(context, '已打开系统「文字转语音」设置，请选择「书声本地」');
      case 'settings':
        showAppSnack(context, '已打开系统设置，请手动进入「文字转语音」');
      default:
        showAppSnack(context, '未能打开系统设置，请手动前往系统设置中的「文字转语音」',
            danger: true);
    }
  }
}

/// 单选项行（自绘，避免控件版本差异）。
class _OptionRow extends StatelessWidget {
  const _OptionRow({
    required this.selected,
    required this.title,
    required this.subtitle,
    required this.onTap,
    this.danger = false,
  });

  final bool selected;
  final String title;
  final String subtitle;
  final VoidCallback onTap;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    final color = danger ? Tokens.danger : Tokens.accent;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(Tokens.radiusButton),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: selected ? Tokens.surfaceAlt : Tokens.background,
          borderRadius: BorderRadius.circular(Tokens.radiusButton),
          border: Border.all(
            color: selected ? color : Tokens.divider,
            width: selected ? 1.4 : 1,
          ),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              selected
                  ? Icons.radio_button_checked
                  : Icons.radio_button_unchecked,
              size: 20,
              color: selected ? color : Tokens.textDim,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                      color: selected ? color : Tokens.text,
                    ),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    subtitle,
                    style: const TextStyle(
                      fontSize: 12,
                      color: Tokens.textDim,
                      height: 1.5,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
