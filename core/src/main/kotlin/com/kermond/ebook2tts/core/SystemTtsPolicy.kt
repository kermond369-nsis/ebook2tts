package com.kermond.ebook2tts.core

import java.util.Locale

/** 设备上已安装的 TTS 引擎候选条目（纯数据，无 Android 依赖）。 */
data class TtsEngineCandidate(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
)

/**
 * 系统自带 TTS 兜底引擎选优策略（纯逻辑）：
 * - 硬性排除自身包名与空包名，防止系统 TTS 回调形成自我递归死循环；
 * - 用户显式指定包名优先；
 * - 其次按主流厂商内置系统语音引擎表序优先，再按系统应用优先，最后按展示名排序。
 */
object SystemTtsSelector {

    const val AUTO_ENGINE = ""

    val PREFERRED_SYSTEM_PACKAGES: List<String> = listOf(
        // 小米 / MIUI / HyperOS
        "com.xiaomi.mibrain.speech",
        "com.miui.voiceassist",
        "com.xiaomi.ai.tts",
        // 华为 / 荣耀
        "com.huawei.hiai",
        "com.huawei.tts",
        "com.hihonor.tts",
        // OPPO / 一加 / realme
        "com.heytap.speechassist",
        "com.coloros.speechassist",
        "com.oplus.speechassist",
        // vivo / iQOO
        "com.vivo.ai.tts",
        "com.vivo.tts",
        // 三星 / 魅族 / Google / 预装讯飞
        "com.samsung.SMT",
        "com.meizu.tts",
        "com.google.android.tts",
        "com.iflytek.speechsuite",
    )

    fun rankCandidates(
        installed: List<TtsEngineCandidate>,
        selfPackage: String,
        preferredPackage: String = AUTO_ENGINE,
    ): List<TtsEngineCandidate> {
        val self = selfPackage.trim()
        val preferred = preferredPackage.trim()
        val seen = HashSet<String>()
        val filtered = ArrayList<TtsEngineCandidate>(installed.size)
        for (item in installed) {
            val pkg = item.packageName.trim()
            if (pkg.isEmpty() || pkg == self || !seen.add(pkg)) continue
            filtered += item.copy(
                packageName = pkg,
                label = item.label.trim().ifEmpty { pkg },
            )
        }
        return filtered.sortedWith(
            compareBy<TtsEngineCandidate>(
                { if (preferred.isNotEmpty() && it.packageName == preferred) 0 else 1 },
                {
                    val idx = PREFERRED_SYSTEM_PACKAGES.indexOf(it.packageName)
                    if (idx >= 0) idx else Int.MAX_VALUE
                },
                { if (it.isSystemApp) 0 else 1 },
                { it.label.lowercase(Locale.ROOT) },
                { it.packageName },
            ),
        )
    }
}

/**
 * 弱网熔断器（纯逻辑，线程安全）：
 * 连续达到 [failureThreshold] 次弱网/超时故障后进入 [State.OPEN]，
 * 在 [cooldownMs] 冷却期内直接跳过在线请求 0ms 回退系统 TTS；
 * 冷却期满后进入 [State.HALF_OPEN] 放行探测，成功即复位 [State.CLOSED]，再败即重新冷却。
 */
class WeakNetworkBreaker(
    private val failureThreshold: Int = 2,
    private val cooldownMs: Long = 15_000L,
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private var state: State = State.CLOSED
    private var consecutiveFailures: Int = 0
    private var openedAtMs: Long = 0L

    @Synchronized
    fun currentState(): State = state

    @Synchronized
    fun shouldBypassOnline(nowMs: Long = System.currentTimeMillis()): Boolean {
        return when (state) {
            State.CLOSED -> false
            State.HALF_OPEN -> false
            State.OPEN -> {
                if (nowMs - openedAtMs < cooldownMs) {
                    true
                } else {
                    state = State.HALF_OPEN
                    false
                }
            }
        }
    }

    @Synchronized
    fun recordSuccess() {
        state = State.CLOSED
        consecutiveFailures = 0
        openedAtMs = 0L
    }

    @Synchronized
    fun recordFailure(nowMs: Long = System.currentTimeMillis()) {
        if (state == State.HALF_OPEN) {
            state = State.OPEN
            openedAtMs = nowMs
            return
        }
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold.coerceAtLeast(1)) {
            state = State.OPEN
            openedAtMs = nowMs
        }
    }

    @Synchronized
    fun reset() {
        state = State.CLOSED
        consecutiveFailures = 0
        openedAtMs = 0L
    }
}

/**
 * 系统 TTS `onAudioAvailable` 回调音频格式归一器（纯 JVM 字节处理）：
 * 将 PCM 8-bit / PCM 16-bit / PCM Float（单/多声道）统一转为单声道 PCM16 LE。
 */
object SystemTtsAudioConverter {

    /** 与 Android `AudioFormat.ENCODING_PCM_*` 常量保持一致 */
    const val ENCODING_PCM_16BIT = 2
    const val ENCODING_PCM_8BIT = 3
    const val ENCODING_PCM_FLOAT = 4

    fun toMonoPcm16(raw: ByteArray, encoding: Int, channels: Int): ByteArray {
        if (raw.isEmpty()) return ByteArray(0)
        val ch = channels.coerceAtLeast(1)
        return when (encoding) {
            ENCODING_PCM_8BIT -> {
                val frameBytes = ch
                val frames = raw.size / frameBytes
                if (frames <= 0) return ByteArray(0)
                val sampleCount = frames * ch
                val pcm16 = ByteArray(sampleCount * 2)
                for (i in 0 until sampleCount) {
                    val unsigned = raw[i].toInt() and 0xFF
                    val v = ((unsigned - 128) shl 8).coerceIn(-32768, 32767)
                    pcm16[i * 2] = (v and 0xFF).toByte()
                    pcm16[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                PcmResampler.downmixToMonoPcm16(pcm16, ch)
            }
            ENCODING_PCM_FLOAT -> {
                val frameBytes = ch * 4
                val frames = raw.size / frameBytes
                if (frames <= 0) return ByteArray(0)
                val sampleCount = frames * ch
                val floats = FloatArray(sampleCount)
                for (i in 0 until sampleCount) {
                    val off = i * 4
                    val bits = (raw[off].toInt() and 0xFF) or
                        ((raw[off + 1].toInt() and 0xFF) shl 8) or
                        ((raw[off + 2].toInt() and 0xFF) shl 16) or
                        ((raw[off + 3].toInt() and 0xFF) shl 24)
                    val f = Float.fromBits(bits)
                    floats[i] = if (f.isFinite()) f.coerceIn(-1f, 1f) else 0f
                }
                val pcm16 = PcmChunker.floatToPcm16(floats)
                PcmResampler.downmixToMonoPcm16(pcm16, ch)
            }
            else -> {
                val frameBytes = ch * 2
                val alignedLen = raw.size - (raw.size % frameBytes)
                if (alignedLen <= 0) return ByteArray(0)
                val aligned = if (alignedLen == raw.size) raw else raw.copyOf(alignedLen)
                PcmResampler.downmixToMonoPcm16(aligned, ch)
            }
        }
    }
}
