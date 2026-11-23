import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../content/legal_texts.dart';
import '../engine/engine_service.dart';
import '../state/providers.dart';
import '../theme/tokens.dart';
import '../widgets/common.dart';
import '../widgets/online_consent_dialog.dart';
import '../widgets/token_plan_dialog.dart';

/// 在线引擎配置页：管理在线开关、三协议切换、鉴权与 MiMo 预置音色。
class OnlineConfigPage extends ConsumerStatefulWidget {
  const OnlineConfigPage({super.key});

  @override
  ConsumerState<OnlineConfigPage> createState() => _OnlineConfigPageState();
}

class _OnlineConfigPageState extends ConsumerState<OnlineConfigPage> {
  final TextEditingController _apiKey = TextEditingController();
  final TextEditingController _mimoBaseOverride = TextEditingController();
  final TextEditingController _providerBaseUrl = TextEditingController();
  final TextEditingController _providerModel = TextEditingController();
  final TextEditingController _providerVoice = TextEditingController();
  final TextEditingController _providerHeaders = TextEditingController();
  final TextEditingController _providerBody = TextEditingController();

  String _providerKind = 'mimo';
  String _responseFormat = 'pcm';
  int _sampleRate = 24000;
  bool _syncedFromConfig = false;
  bool _showAdvancedMimoBase = false;
  bool _validating = false;
  bool _savingProvider = false;
  bool _probingProvider = false;
  String? _manualError;
  String _typedKey = '';

  @override
  void initState() {
    super.initState();
    _apiKey.addListener(() {
      if (_typedKey != _apiKey.text) {
        setState(() => _typedKey = _apiKey.text);
      }
    });
  }

  @override
  void dispose() {
    _apiKey.dispose();
    _mimoBaseOverride.dispose();
    _providerBaseUrl.dispose();
    _providerModel.dispose();
    _providerVoice.dispose();
    _providerHeaders.dispose();
    _providerBody.dispose();
    super.dispose();
  }

  void _syncFromConfig(AppConfig cfg) {
    if (_syncedFromConfig) return;
    _syncedFromConfig = true;
    _providerKind = cfg.providerKind;
    _responseFormat = cfg.responseFormat;
    _sampleRate = cfg.sampleRate;
    _providerBaseUrl.text = cfg.providerKind == 'mimo' ? '' : cfg.baseUrl;
    _providerModel.text = cfg.model;
    _providerVoice.text = cfg.voice;
    _providerHeaders.text = cfg.customHeaders;
    _providerBody.text = cfg.customBodyTemplate;
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
                padding: EdgeInsets.all(24),
                child: CircularProgressIndicator(),
              ),
            ),
          ),
        ],
      );
    }

    _syncFromConfig(config);

    final isMimo = _providerKind == 'mimo';
    final isOpenAi = _providerKind == 'openai';
    final isCustom = _providerKind == 'custom_http';
    final isPlan = config.keyKind == 'plan';
    final canEditKey = !isMimo || !isPlan || config.tokenPlanAccepted;

    final effectiveOnlineError = _manualError ??
        ((status?.onlineLastError.isNotEmpty ?? false)
            ? status!.onlineLastError
            : (status?.online == OnlineState.error
                ? '当前在线配置尚未通过鉴权或接口不可达，朗读时将自动回退系统 TTS。'
                : null));

    final trimmedTyped = _typedKey.trim();
    final detectedKind = trimmedTyped.startsWith('tp-')
        ? 'plan'
        : trimmedTyped.startsWith('sk-')
            ? 'billing'
            : null;
    final keyMismatch = isMimo &&
        detectedKind != null &&
        detectedKind != config.keyKind;

    final statusColor = switch (status?.online) {
      OnlineState.ready => Tokens.accent,
      OnlineState.error => Tokens.danger,
      OnlineState.noKey => Tokens.hybrid,
      _ => Tokens.textDim,
    };

    return PageBody(
      children: [
        // ① 在线总开关 & 实时状态
        SectionCard(
          title: '在线合成开关',
          subtitle: '开启前需单独确认外发告知；未开启或弱网超时将自动改用系统自带 TTS。',
          trailing: StatusChip(
            label: status == null ? '读取中' : onlineStateLabel(status.online),
            color: statusColor,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Expanded(
                    child: Text(
                      '启用在线朗读',
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        color: Tokens.text,
                      ),
                    ),
                  ),
                  Switch(
                    value: config.onlineEnabled,
                    onChanged: _toggleOnline,
                  ),
                ],
              ),
              if (isMimo) ...[
                const Divider(height: 22),
                Row(
                  children: [
                    const Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'AI 角色造音色（MiMo 专属）',
                            style: TextStyle(
                              fontSize: 14.5,
                              fontWeight: FontWeight.w600,
                              color: Tokens.text,
                            ),
                          ),
                          SizedBox(height: 2),
                          Text(
                            '识别小说人物对白并为不同角色生成区分音色。',
                            style: TextStyle(fontSize: 12, color: Tokens.textDim),
                          ),
                        ],
                      ),
                    ),
                    Switch(
                      value: config.roleVoiceEnabled,
                      onChanged: config.onlineEnabled ? _toggleRoleVoice : null,
                    ),
                  ],
                ),
                if (config.roleVoiceEnabled)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text(
                      '已建档角色 ${config.roleCount} 个 · 已精标 ${config.roleRefineCount} 次',
                      style: const TextStyle(
                        fontSize: 12,
                        color: Tokens.textDim,
                      ),
                    ),
                  ),
              ],
              if (effectiveOnlineError != null && config.onlineEnabled) ...[
                const SizedBox(height: 12),
                InfoBanner(
                  title: '在线调用提示',
                  body: effectiveOnlineError,
                  color: Tokens.danger,
                  icon: Icons.error_outline_rounded,
                  action: Row(
                    children: [
                      if (isMimo) ...[
                        Expanded(
                          child: SecondaryButton(
                            label: isPlan ? '改用按量计费' : '切换 Token Plan',
                            icon: Icons.swap_horiz_rounded,
                            onPressed: () => _switchKeyKind(
                              isPlan ? 'billing' : 'plan',
                              config,
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                      ],
                      Expanded(
                        child: SecondaryButton(
                          label: '暂时关闭在线',
                          icon: Icons.power_settings_new_rounded,
                          danger: true,
                          onPressed: () => _toggleOnline(false),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ],
          ),
        ),

        // ② 在线引擎协议选择
        SectionCard(
          title: '在线语音引擎',
          subtitle: '按你的服务方自由切换协议，配置保存后立即热生效。',
          trailing: StatusChip(
            label: isOpenAi
                ? 'OpenAI 兼容'
                : isCustom
                    ? '自定义 HTTP'
                    : '小米 MiMo',
            color: Tokens.cloud,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SegmentedButton<String>(
                showSelectedIcon: false,
                segments: const [
                  ButtonSegment(value: 'mimo', label: Text('小米 MiMo')),
                  ButtonSegment(value: 'openai', label: Text('OpenAI 兼容')),
                  ButtonSegment(value: 'custom_http', label: Text('自定义 HTTP')),
                ],
                selected: <String>{_providerKind},
                onSelectionChanged: (s) => _selectProviderKind(s.first),
              ),
              const SizedBox(height: 12),
              Text(
                isMimo
                    ? 'SSE 流式 PCM（24kHz），原生支持小说多角色音色设计；支持按量计费（sk-）与 Token Plan（tp-）。'
                    : isOpenAi
                        ? '对接标准 /v1/audio/speech（OpenAI、硅基流动 CosyVoice2、Fish-Speech、Kokoro-FastAPI 等）。'
                        : '通过 JSON 模板变量对接任意自建或第三方 TTS HTTP 接口（支持 PCM / WAV / MP3 / AAC）。',
                style: const TextStyle(
                  fontSize: 12.5,
                  color: Tokens.textDim,
                  height: 1.5,
                ),
              ),
            ],
          ),
        ),

        // ③ 专属配置卡片
        if (isMimo)
          _buildMimoCard(
            context,
            config,
            isPlan,
            canEditKey,
            keyMismatch,
            detectedKind,
          )
        else
          _buildGenericProviderCard(context, config, isOpenAi, isCustom),
      ],
    );
  }

  Widget _buildMimoCard(
    BuildContext context,
    AppConfig config,
    bool isPlan,
    bool canEditKey,
    bool keyMismatch,
    String? detectedKind,
  ) {
    final activeVoice = config.voice.isNotEmpty ? config.voice : '白桦';
    return Column(
      children: [
        SectionCard(
          title: 'MiMo 默认音色',
          subtitle: '选择旁白与单角色基础音色；开启 AI 角色造音色时，命名角色会自动生成独立声线。',
          trailing: StatusChip(label: activeVoice, color: Tokens.accent),
          child: Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final preset in kMimoVoicePresets)
                ChoiceChip(
                  label: Text(preset.label),
                  selected: activeVoice == preset.id,
                  onSelected: (_) async {
                    await ref
                        .read(configProvider.notifier)
                        .save(voice: preset.id);
                    if (context.mounted) {
                      showAppSnack(context, '默认在线音色已设为：${preset.label}');
                    }
                  },
                ),
            ],
          ),
        ),
        SectionCard(
          title: 'MiMo 密钥与域名',
          subtitle: '两类密钥对应不同接入域名：sk- 走按量计费，tp- 走 Token Plan 集群。',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _SelectRow(
                selected: !isPlan,
                title: '按量计费 API Key（推荐）',
                subtitle: 'sk- 开头 · 按字符按量付费，无订阅规则限制',
                onTap: () => _switchKeyKind('billing', config),
              ),
              const SizedBox(height: 8),
              _SelectRow(
                selected: isPlan,
                title: 'Token Plan 套餐密钥',
                subtitle: 'tp- 开头 · 需先完成平台规则与风险确认',
                danger: true,
                onTap: () => _switchKeyKind('plan', config),
              ),
              if (isPlan || config.tokenPlanAccepted) ...[
                const SizedBox(height: 10),
                InfoBanner(
                  title: 'Token Plan 风险提示',
                  body: tokenPlanResidentWarning,
                  color: Tokens.danger,
                  icon: Icons.warning_amber_rounded,
                  action: TextButton(
                    onPressed: () => showTokenPlanRiskDialog(context),
                    child: const Text('查看风险说明全文'),
                  ),
                ),
              ],
              const SizedBox(height: 14),
              TextField(
                controller: _apiKey,
                enabled: canEditKey,
                obscureText: true,
                decoration: InputDecoration(
                  labelText: 'API Key（密钥）',
                  hintText: isPlan ? '输入 tp- 开头的套餐密钥' : '输入 sk- 开头的按量计费密钥',
                  helperText: canEditKey
                      ? '仅存于本机多进程安全存储，不上传、不转发；留空点「校验密钥」检测已保存密钥。'
                      : '请先选择 Token Plan 并完成风险确认后再填写。',
                  helperMaxLines: 2,
                ),
              ),
              if (keyMismatch && detectedKind != null) ...[
                const SizedBox(height: 10),
                InfoBanner(
                  title: '检测到密钥前缀与当前模式不一致',
                  body: detectedKind == 'plan'
                      ? '你输入的是 tp- 套餐密钥，当前却选择了「按量计费」。切到 Token Plan 并自动对齐域名可避免 401 鉴权失败。'
                      : '你输入的是 sk- 按量密钥，当前却选择了 Token Plan。切回「按量计费」即可匹配域名。',
                  color: Tokens.hybrid,
                  icon: Icons.tips_and_updates_outlined,
                  action: TextButton(
                    onPressed: () => _switchKeyKind(detectedKind, config),
                    child: Text(
                      detectedKind == 'plan' ? '切换为 Token Plan' : '切换为按量计费',
                    ),
                  ),
                ),
              ],
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: PrimaryButton(
                      label: '保存密钥',
                      icon: Icons.save_outlined,
                      onPressed: canEditKey ? () => _saveMimoKey(config) : null,
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
              KeyValueRow(
                label: '已保存密钥',
                value: config.apiKeyMasked.isEmpty ? '未保存' : config.apiKeyMasked,
              ),
              KeyValueRow(
                label: '生效地址',
                value: config.baseUrl,
                valueColor: Tokens.cloud,
              ),
              const SizedBox(height: 4),
              if (!_showAdvancedMimoBase)
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton(
                    onPressed: () =>
                        setState(() => _showAdvancedMimoBase = true),
                    child: const Text('高级：手动覆盖接口地址'),
                  ),
                )
              else ...[
                const Divider(height: 20),
                TextField(
                  controller: _mimoBaseOverride,
                  decoration: const InputDecoration(
                    labelText: '覆盖 Base URL（HTTPS）',
                    hintText: 'https://api.xiaomimimo.com/v1',
                  ),
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(
                      child: PrimaryButton(
                        label: '应用覆盖',
                        onPressed: () async {
                          final url = _mimoBaseOverride.text.trim();
                          if (url.isEmpty) {
                            showAppSnack(context, '覆盖地址不能为空', danger: true);
                            return;
                          }
                          await ref
                              .read(configProvider.notifier)
                              .save(baseUrl: url);
                          setState(() => _manualError = null);
                          if (context.mounted) {
                            showAppSnack(context, '已覆盖 MiMo 接口地址');
                          }
                        },
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: SecondaryButton(
                        label: '恢复自动',
                        onPressed: () async {
                          _mimoBaseOverride.clear();
                          await ref
                              .read(configProvider.notifier)
                              .save(baseUrl: '');
                          setState(() => _manualError = null);
                          if (context.mounted) {
                            showAppSnack(context, '已恢复随密钥类型自动切换');
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
      ],
    );
  }

  Widget _buildGenericProviderCard(
    BuildContext context,
    AppConfig config,
    bool isOpenAi,
    bool isCustom,
  ) {
    return SectionCard(
      title: isOpenAi ? 'OpenAI 兼容参数' : '自定义 HTTP 模板参数',
      subtitle: isOpenAi
          ? '选择常见预设或手动填入兼容端点；自部署免鉴权服务可留空 API Key。'
          : '支持通过 {{text}}、{{voice}}、{{model}}、{{speed}}、{{apiKey}} 模板对接任意 TTS 服务。',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (isOpenAi) ...[
            Wrap(
              spacing: 8,
              runSpacing: 6,
              children: [
                ActionChip(
                  label: const Text('OpenAI 官方'),
                  onPressed: () => _applyPreset(
                    baseUrl: 'https://api.openai.com/v1',
                    model: 'gpt-4o-mini-tts',
                    voice: 'alloy',
                    format: 'pcm',
                  ),
                ),
                ActionChip(
                  label: const Text('硅基流动 CosyVoice2'),
                  onPressed: () => _applyPreset(
                    baseUrl: 'https://api.siliconflow.cn/v1',
                    model: 'FunAudioLLM/CosyVoice2-0.5B',
                    voice: 'FunAudioLLM/CosyVoice2-0.5B:alex',
                    format: 'pcm',
                  ),
                ),
                ActionChip(
                  label: const Text('自建兼容网关'),
                  onPressed: () => _applyPreset(
                    baseUrl: 'https://your-tts-gateway.example.com/v1',
                    model: 'tts-1',
                    voice: 'alloy',
                    format: 'wav',
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
          ],
          TextField(
            controller: _providerBaseUrl,
            decoration: InputDecoration(
              labelText: isCustom ? '完整合成 URL（HTTPS）' : 'Base URL（含 /v1）',
              hintText: isCustom
                  ? 'https://your-tts.example.com/tts'
                  : 'https://api.openai.com/v1',
            ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _providerModel,
                  decoration: const InputDecoration(
                    labelText: '模型（Model）',
                    hintText: 'tts-1',
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: TextField(
                  controller: _providerVoice,
                  decoration: const InputDecoration(
                    labelText: '音色（Voice）',
                    hintText: 'alloy',
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          const Text(
            '响应音频格式',
            style: TextStyle(fontSize: 12.5, color: Tokens.textDim),
          ),
          const SizedBox(height: 6),
          Wrap(
            spacing: 8,
            children: [
              for (final fmt in const ['pcm', 'wav', 'mp3', 'auto'])
                ChoiceChip(
                  label: Text(fmt.toUpperCase()),
                  selected: _responseFormat == fmt,
                  onSelected: (_) => setState(() => _responseFormat = fmt),
                ),
            ],
          ),
          if (_responseFormat == 'pcm') ...[
            const SizedBox(height: 10),
            const Text(
              '无头 PCM 默认采样率',
              style: TextStyle(fontSize: 12.5, color: Tokens.textDim),
            ),
            const SizedBox(height: 6),
            Wrap(
              spacing: 8,
              children: [
                for (final sr in const [16000, 22050, 24000, 44100])
                  ChoiceChip(
                    label: Text('${(sr / 1000).toStringAsFixed(sr % 1000 == 0 ? 0 : 2)}k'),
                    selected: _sampleRate == sr,
                    onSelected: (_) => setState(() => _sampleRate = sr),
                  ),
              ],
            ),
          ],
          if (isCustom) ...[
            const SizedBox(height: 10),
            TextField(
              controller: _providerHeaders,
              maxLines: 2,
              decoration: const InputDecoration(
                labelText: '自定义请求头（每行 Key: Value，可选）',
                hintText: 'X-Api-Key: {{apiKey}}',
              ),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _providerBody,
              maxLines: 4,
              decoration: const InputDecoration(
                labelText: 'JSON 请求体模板',
                hintText:
                    '{"model":"{{model}}","input":"{{text}}","voice":"{{voice}}","speed":{{speed}}}',
              ),
            ),
          ],
          const SizedBox(height: 12),
          TextField(
            controller: _apiKey,
            obscureText: true,
            decoration: InputDecoration(
              labelText: 'Bearer API Key（可选）',
              hintText: config.apiKeyMasked.isEmpty
                  ? '免鉴权自建服务可留空'
                  : '已保存：${config.apiKeyMasked}（留空则保持不变）',
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: PrimaryButton(
                  label: _savingProvider ? '保存中…' : '保存配置',
                  icon: Icons.save_outlined,
                  onPressed: _savingProvider ? null : _saveGenericAll,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: SecondaryButton(
                  label: _probingProvider ? '测试中…' : '测试连通性',
                  icon: Icons.network_check_rounded,
                  onPressed: _probingProvider ? null : _probeGeneric,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _toggleOnline(bool value) async {
    if (value) {
      final agreed = await showOnlineConsentDialog(context);
      if (agreed != true) {
        if (mounted) showAppSnack(context, '已取消开启，当前使用系统 TTS 兜底');
        return;
      }
      await ref.read(configProvider.notifier).save(onlineEnabled: true);
      if (mounted) {
        setState(() => _manualError = null);
        showAppSnack(context, '在线朗读已开启');
      }
    } else {
      await ref.read(configProvider.notifier).save(onlineEnabled: false);
      if (mounted) {
        setState(() => _manualError = null);
        showAppSnack(context, '在线朗读已关闭，将使用系统 TTS');
      }
    }
  }

  Future<void> _toggleRoleVoice(bool value) async {
    await ref.read(configProvider.notifier).save(roleVoiceEnabled: value);
    if (!mounted) return;
    showAppSnack(
      context,
      value ? 'AI 角色音色已开启' : 'AI 角色音色已关闭：使用默认在线音色',
    );
  }

  Future<void> _selectProviderKind(String kind) async {
    if (kind == _providerKind) return;
    setState(() {
      _providerKind = kind;
      _manualError = null;
      if (kind == 'openai' &&
          (_providerBaseUrl.text.trim().isEmpty ||
              _providerBaseUrl.text.contains('xiaomimimo.com'))) {
        _providerBaseUrl.text = 'https://api.openai.com/v1';
        _providerModel.text = 'tts-1';
        _providerVoice.text = 'alloy';
      } else if (kind == 'mimo') {
        _providerModel.text = 'mimo-v2.5-tts';
        if (_providerVoice.text.trim().isEmpty ||
            _providerVoice.text.trim() == 'alloy') {
          _providerVoice.text = '白桦';
        }
      }
    });
    await ref.read(configProvider.notifier).save(
          providerKind: kind,
          baseUrl: kind == 'mimo' ? '' : _providerBaseUrl.text.trim(),
          model: _providerModel.text.trim(),
          voice: _providerVoice.text.trim(),
          responseFormat: _responseFormat,
          sampleRate: _sampleRate,
          customHeaders: _providerHeaders.text.trim(),
          customBodyTemplate: _providerBody.text.trim(),
        );
  }

  void _applyPreset({
    required String baseUrl,
    required String model,
    required String voice,
    required String format,
  }) {
    setState(() {
      _providerKind = 'openai';
      _providerBaseUrl.text = baseUrl;
      _providerModel.text = model;
      _providerVoice.text = voice;
      _responseFormat = format;
      _manualError = null;
    });
  }

  Future<void> _switchKeyKind(String kind, AppConfig config) async {
    if (kind == config.keyKind) return;
    if (kind == 'plan' && !config.tokenPlanAccepted) {
      final agreed = await showTokenPlanRiskDialog(context);
      if (agreed != true) {
        if (mounted) showAppSnack(context, '已取消，仍保持「按量计费 API Key」');
        return;
      }
      await ref
          .read(configProvider.notifier)
          .save(keyKind: 'plan', tokenPlanAccepted: true);
      if (mounted) {
        setState(() => _manualError = null);
        showAppSnack(context, '已切换为 Token Plan');
      }
      return;
    }
    await ref.read(configProvider.notifier).save(keyKind: kind);
    if (mounted) {
      setState(() => _manualError = null);
      showAppSnack(
        context,
        kind == 'plan' ? '已切换为 Token Plan' : '已切换为按量计费 API Key',
      );
    }
  }

  Future<void> _saveMimoKey(AppConfig config) async {
    final key = _apiKey.text.trim();
    if (key.isEmpty) {
      showAppSnack(context, '请先填写密钥', danger: true);
      return;
    }
    if (key.startsWith('tp-') && config.keyKind != 'plan') {
      if (!config.tokenPlanAccepted) {
        final agreed = await showTokenPlanRiskDialog(context);
        if (agreed != true) return;
      }
      await ref.read(configProvider.notifier).save(
            keyKind: 'plan',
            tokenPlanAccepted: true,
            apiKey: key,
          );
    } else if (key.startsWith('sk-') && config.keyKind != 'billing') {
      await ref.read(configProvider.notifier).save(
            keyKind: 'billing',
            apiKey: key,
          );
    } else {
      await ref.read(configProvider.notifier).save(apiKey: key);
    }
    _apiKey.clear();
    if (!mounted) return;
    setState(() => _manualError = null);
    showAppSnack(context, '密钥已保存到本机并完成域名匹配');
  }

  Future<void> _validateKey(AppConfig config) async {
    final typed = _apiKey.text.trim();
    final usingSaved = typed.isEmpty;
    if (usingSaved && config.apiKeyMasked.trim().isEmpty) {
      showAppSnack(context, '尚未保存密钥：请先填写并保存密钥', danger: true);
      return;
    }
    setState(() {
      _validating = true;
      _manualError = null;
    });
    final err = await ref.read(configProvider.notifier).validateOnline(
          keyKind: config.keyKind,
          apiKey: usingSaved ? null : typed,
        );
    if (!mounted) return;
    setState(() {
      _validating = false;
      _manualError = err;
    });
    if (err == null) {
      showAppSnack(
        context,
        usingSaved ? '校验通过：已保存密钥可用' : '校验通过：输入密钥可用（请记得保存）',
      );
    }
  }

  Future<void> _saveGenericAll() async {
    setState(() {
      _savingProvider = true;
      _manualError = null;
    });
    final typedKey = _apiKey.text.trim();
    await ref.read(configProvider.notifier).save(
          providerKind: _providerKind,
          baseUrl: _providerBaseUrl.text.trim(),
          model: _providerModel.text.trim(),
          voice: _providerVoice.text.trim(),
          responseFormat: _responseFormat,
          sampleRate: _sampleRate,
          customHeaders: _providerHeaders.text.trim(),
          customBodyTemplate: _providerBody.text.trim(),
          apiKey: typedKey.isNotEmpty ? typedKey : null,
        );
    if (typedKey.isNotEmpty) {
      _apiKey.clear();
    }
    if (!mounted) return;
    setState(() => _savingProvider = false);
    showAppSnack(context, '在线引擎配置已保存并热生效');
  }

  Future<void> _probeGeneric() async {
    setState(() {
      _probingProvider = true;
      _manualError = null;
    });
    await ref.read(configProvider.notifier).save(
          providerKind: _providerKind,
          baseUrl: _providerBaseUrl.text.trim(),
          model: _providerModel.text.trim(),
          voice: _providerVoice.text.trim(),
          responseFormat: _responseFormat,
          sampleRate: _sampleRate,
          customHeaders: _providerHeaders.text.trim(),
          customBodyTemplate: _providerBody.text.trim(),
        );
    final typedKey = _apiKey.text.trim();
    final err = await ref
        .read(configProvider.notifier)
        .validateOnline(apiKey: typedKey.isEmpty ? null : typedKey);
    if (!mounted) return;
    setState(() {
      _probingProvider = false;
      _manualError = err;
    });
    if (err == null) {
      showAppSnack(context, '在线引擎连通性测试通过');
    }
  }
}

class _SelectRow extends StatelessWidget {
  const _SelectRow({
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
    final activeColor = danger ? Tokens.danger : Tokens.accent;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(Tokens.radiusButton),
      child: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: selected
              ? activeColor.withValues(alpha: 0.08)
              : Tokens.background,
          borderRadius: BorderRadius.circular(Tokens.radiusButton),
          border: Border.all(
            color: selected ? activeColor : Tokens.divider,
            width: selected ? 1.5 : 1,
          ),
        ),
        child: Row(
          children: [
            Icon(
              selected
                  ? Icons.radio_button_checked_rounded
                  : Icons.radio_button_unchecked_rounded,
              size: 20,
              color: selected ? activeColor : Tokens.textDim,
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
                      color: selected ? activeColor : Tokens.text,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: const TextStyle(
                      fontSize: 12,
                      color: Tokens.textDim,
                      height: 1.4,
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
