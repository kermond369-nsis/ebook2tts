package com.kermond.ebook2tts.engine

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.kermond.ebook2tts.core.SystemTtsAudioConverter
import com.kermond.ebook2tts.core.SystemTtsSelector
import com.kermond.ebook2tts.core.TtsEngineCandidate
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 系统 TTS 引擎发现器（Android 11+ 需在清单中声明 `<queries>` 才能看到其他 TTS 服务）。
 */
object SystemTtsDiscovery {

    fun listInstalledEngines(
        context: Context,
        preferredPackage: String = ConfigStore.fallbackTtsEngine(),
    ): List<TtsEngineCandidate> {
        val pm = context.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val infos = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }
        }.getOrDefault(emptyList())

        val raw = infos.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            val ai = si.applicationInfo
            val pkg = si.packageName?.trim().orEmpty()
            if (pkg.isEmpty()) return@mapNotNull null
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull()
                ?.trim()?.ifEmpty { null }
                ?: ai?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
                    ?.trim()?.ifEmpty { null }
                ?: pkg
            val isSystem = ai != null && (
                (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                )
            TtsEngineCandidate(packageName = pkg, label = label, isSystemApp = isSystem)
        }
        return SystemTtsSelector.rankCandidates(
            installed = raw,
            selfPackage = context.packageName,
            preferredPackage = preferredPackage,
        )
    }

    fun hasFallbackEngine(context: Context): Boolean =
        listInstalledEngines(context).isNotEmpty()

    /** 返回（生效候选包名，可读名称）；无可用系统 TTS 时返回 `""` 与提示文案 */
    fun resolvePreferredLabel(
        context: Context,
        preferredPackage: String = ConfigStore.fallbackTtsEngine(),
    ): Pair<String, String> {
        val first = listInstalledEngines(context, preferredPackage).firstOrNull()
            ?: return "" to "未检测到其他系统 TTS"
        return first.packageName to first.label
    }
}

sealed class SystemTtsOutcome {
    data class Ok(
        val enginePackage: String,
        val engineLabel: String,
        val bytes: Int,
        val sampleRate: Int,
    ) : SystemTtsOutcome()

    data class Failed(val reason: String, val summary: String) : SystemTtsOutcome()
    object Cancelled : SystemTtsOutcome()
}

/**
 * 系统自带 TTS 兜底合成后端（运行在 `:tts_service` 进程）。
 *
 * 关键纪律：
 * 1. **严禁调用双参 `TextToSpeech(context, listener)`**，强制三参显式指定非自身包名，彻底杜绝自递归死锁；
 * 2. 优先走 `UtteranceProgressListener.onAudioAvailable` 实时接收 PCM 流（自动转单声道 PCM16），
 *    若目标引擎未触发流式回调，则在 `onDone` 后从临时 WAV 文件补读；
 * 3. 若首选系统引擎初始化或无音失败，自动尝试候选列表中的下一个已安装引擎。
 */
class SystemTtsBackend(private val context: Context) {

    private sealed class TtsEvent {
        data class Begin(val sampleRate: Int, val encoding: Int, val channels: Int) : TtsEvent()
        data class Audio(val bytes: ByteArray) : TtsEvent()
        object Done : TtsEvent()
        data class Error(val code: Int) : TtsEvent()
        object Stopped : TtsEvent()
    }

    private val appContext = context.applicationContext
    private val lock = ReentrantLock()
    private val uttSeq = AtomicInteger(0)

    private var activeEngine: TextToSpeech? = null
    private var activePackage: String? = null
    private var activeLabel: String = ""
    private var activeLocaleReady = false
    private var configuredPreferred: String = ConfigStore.fallbackTtsEngine()

    @Volatile
    private var currentUtteranceId: String? = null

    @Volatile
    private var currentQueue: LinkedBlockingQueue<TtsEvent>? = null

    private val sharedListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onBeginSynthesis(
            utteranceId: String?,
            sampleRateInHz: Int,
            audioFormat: Int,
            channelCount: Int,
        ) {
            if (utteranceId != currentUtteranceId) return
            currentQueue?.offer(TtsEvent.Begin(sampleRateInHz, audioFormat, channelCount))
        }

        override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
            if (utteranceId != currentUtteranceId || audio == null || audio.isEmpty()) return
            // AOSP Binder 缓冲区可能复用，必须立即拷贝
            currentQueue?.offer(TtsEvent.Audio(audio.copyOf()))
        }

        override fun onDone(utteranceId: String?) {
            if (utteranceId != currentUtteranceId) return
            currentQueue?.offer(TtsEvent.Done)
        }

        @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, -1)"))
        override fun onError(utteranceId: String?) {
            if (utteranceId != currentUtteranceId) return
            currentQueue?.offer(TtsEvent.Error(-1))
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (utteranceId != currentUtteranceId) return
            currentQueue?.offer(TtsEvent.Error(errorCode))
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            if (utteranceId != currentUtteranceId) return
            currentQueue?.offer(TtsEvent.Stopped)
        }
    }

    fun invalidateIfEngineChanged(preferredPackage: String) {
        val norm = preferredPackage.trim()
        lock.withLock {
            if (configuredPreferred != norm) {
                configuredPreferred = norm
                if (activePackage != null && norm.isNotEmpty() && activePackage != norm) {
                    releaseEngineLocked()
                } else if (norm.isEmpty() && activePackage != null) {
                    val top = SystemTtsDiscovery.listInstalledEngines(appContext, "").firstOrNull()?.packageName
                    if (top != null && top != activePackage) {
                        releaseEngineLocked()
                    }
                }
            }
        }
    }

    fun shutdown() {
        lock.withLock {
            releaseEngineLocked()
        }
    }

    fun synthesize(
        text: String,
        speed: Float,
        onChunk: (TtsAudioChunk) -> Boolean,
        isCancelled: () -> Boolean,
    ): SystemTtsOutcome = lock.withLock {
        val clean = text.trim()
        if (clean.isEmpty()) {
            return SystemTtsOutcome.Failed("empty_text", "待朗读文本为空")
        }
        val preferred = ConfigStore.fallbackTtsEngine()
        configuredPreferred = preferred
        val candidates = SystemTtsDiscovery.listInstalledEngines(appContext, preferred)
        if (candidates.isEmpty()) {
            return SystemTtsOutcome.Failed("no_system_tts", "设备未安装可用的其他系统 TTS 引擎")
        }

        val ordered = ArrayList<TtsEngineCandidate>(candidates.size)
        val curPkg = activePackage
        val curCandidate = if (curPkg != null && (preferred.isEmpty() || preferred == curPkg)) {
            candidates.firstOrNull { it.packageName == curPkg }
        } else {
            null
        }
        if (curCandidate != null) {
            ordered += curCandidate
            for (c in candidates) {
                if (c.packageName != curCandidate.packageName) ordered += c
            }
        } else {
            ordered.addAll(candidates)
        }

        var lastError = "系统 TTS 合成失败"
        for (candidate in ordered) {
            if (isCancelled()) return SystemTtsOutcome.Cancelled
            if (candidate.packageName == appContext.packageName) continue
            val tts = ensureEngineLocked(candidate, isCancelled)
            if (isCancelled()) return SystemTtsOutcome.Cancelled
            if (tts == null) {
                lastError = "系统引擎 ${candidate.label} 初始化失败或超时"
                continue
            }
            var emittedAny = false
            val outcome = synthesizeWithEngineLocked(
                tts = tts,
                candidate = candidate,
                text = clean,
                speed = speed,
                onChunk = { chunk ->
                    if (chunk.pcm16Mono.isNotEmpty()) emittedAny = true
                    onChunk(chunk)
                },
                isCancelled = isCancelled,
            )
            when (outcome) {
                is SystemTtsOutcome.Ok, SystemTtsOutcome.Cancelled -> return outcome
                is SystemTtsOutcome.Failed -> {
                    lastError = outcome.summary
                    Log.w(TAG, "SYSTEM_TTS|attempt_failed|pkg=${candidate.packageName}|reason=${outcome.reason}")
                    // 仅在尚未吐出任何音频时尝试下一个系统 TTS 引擎
                    if (emittedAny) return outcome
                    releaseEngineLocked()
                }
            }
        }
        return SystemTtsOutcome.Failed("system_tts_failed", lastError)
    }

    private fun ensureEngineLocked(
        candidate: TtsEngineCandidate,
        isCancelled: () -> Boolean,
    ): TextToSpeech? {
        require(candidate.packageName != appContext.packageName) {
            "Refusing recursive self-binding to ${appContext.packageName}"
        }
        if (activeEngine != null && activePackage == candidate.packageName) {
            return activeEngine
        }
        releaseEngineLocked()
        val initLatch = CountDownLatch(1)
        val initStatus = AtomicInteger(TextToSpeech.ERROR)
        val created = runCatching {
            TextToSpeech(
                appContext,
                { status ->
                    initStatus.set(status)
                    initLatch.countDown()
                },
                candidate.packageName,
            )
        }.getOrElse { t ->
            Log.w(TAG, "SYSTEM_TTS|create_failed|pkg=${candidate.packageName}|${t.javaClass.simpleName}")
            return null
        }

        val deadline = SystemClock.uptimeMillis() + INIT_TIMEOUT_MS
        while (!isCancelled() && SystemClock.uptimeMillis() < deadline) {
            if (initLatch.await(50L, TimeUnit.MILLISECONDS)) break
        }
        if (isCancelled() || initLatch.count > 0 || initStatus.get() != TextToSpeech.SUCCESS) {
            runCatching { created.shutdown() }
            return null
        }
        created.setOnUtteranceProgressListener(sharedListener)
        configureChineseOrDefaultLocale(created)
        activeEngine = created
        activePackage = candidate.packageName
        activeLabel = candidate.label
        activeLocaleReady = true
        Log.i(TAG, "SYSTEM_TTS|ready|pkg=${candidate.packageName}|label=${candidate.label}")
        return created
    }

    private fun configureChineseOrDefaultLocale(tts: TextToSpeech) {
        val locales = listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE, Locale.getDefault())
        for (loc in locales) {
            val rc = runCatching { tts.isLanguageAvailable(loc) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (rc >= TextToSpeech.LANG_AVAILABLE) {
                runCatching { tts.language = loc }
                return
            }
        }
    }

    private fun synthesizeWithEngineLocked(
        tts: TextToSpeech,
        candidate: TtsEngineCandidate,
        text: String,
        speed: Float,
        onChunk: (TtsAudioChunk) -> Boolean,
        isCancelled: () -> Boolean,
    ): SystemTtsOutcome {
        val uid = "sys_${uttSeq.incrementAndGet()}_${UUID.randomUUID().toString().take(8)}"
        val queue = LinkedBlockingQueue<TtsEvent>()
        val tempWav = runCatching {
            File.createTempFile("sys_tts_", ".wav", appContext.cacheDir)
        }.getOrElse { t ->
            return SystemTtsOutcome.Failed("temp_file", "无法创建临时音频文件：${t.message}")
        }
        currentQueue = queue
        currentUtteranceId = uid
        try {
            runCatching { tts.setSpeechRate(speed.coerceIn(0.5f, 2.0f)) }
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid)
            }
            val rc = runCatching {
                tts.synthesizeToFile(text, params, tempWav, uid)
            }.getOrDefault(TextToSpeech.ERROR)
            if (rc != TextToSpeech.SUCCESS) {
                return SystemTtsOutcome.Failed("enqueue_error", "系统引擎拒绝合成（code=$rc）")
            }

            var curSr = DEFAULT_SYSTEM_SR
            var curEnc = AudioFormat.ENCODING_PCM_16BIT
            var curCh = 1
            var streamedBytes = 0
            val deadline = SystemClock.uptimeMillis() + SYNTH_TIMEOUT_MS

            while (SystemClock.uptimeMillis() < deadline) {
                if (isCancelled()) {
                    runCatching { tts.stop() }
                    return SystemTtsOutcome.Cancelled
                }
                val ev = try {
                    queue.poll(50L, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    runCatching { tts.stop() }
                    return SystemTtsOutcome.Cancelled
                } ?: continue

                when (ev) {
                    is TtsEvent.Begin -> {
                        if (ev.sampleRate in 8000..192000) curSr = ev.sampleRate
                        curEnc = ev.encoding
                        curCh = ev.channels.coerceIn(1, 8)
                    }
                    is TtsEvent.Audio -> {
                        val mono = SystemTtsAudioConverter.toMonoPcm16(ev.bytes, curEnc, curCh)
                        if (mono.isNotEmpty()) {
                            streamedBytes += mono.size
                            if (!onChunk(TtsAudioChunk(mono, curSr))) {
                                runCatching { tts.stop() }
                                return SystemTtsOutcome.Cancelled
                            }
                        }
                    }
                    is TtsEvent.Done -> {
                        if (streamedBytes > 0) {
                            return SystemTtsOutcome.Ok(
                                enginePackage = candidate.packageName,
                                engineLabel = candidate.label,
                                bytes = streamedBytes,
                                sampleRate = curSr,
                            )
                        }
                        // 部分系统 TTS 引擎执行 synthesizeToFile 时不回调 onAudioAvailable，直接写文件
                        return readWavFallback(tempWav, candidate, curSr, onChunk, isCancelled)
                    }
                    is TtsEvent.Error -> {
                        return SystemTtsOutcome.Failed(
                            "tts_error_${ev.code}",
                            "系统引擎 ${candidate.label} 返回错误（code=${ev.code}）",
                        )
                    }
                    is TtsEvent.Stopped -> {
                        return if (isCancelled()) {
                            SystemTtsOutcome.Cancelled
                        } else {
                            SystemTtsOutcome.Failed("tts_stopped", "系统引擎意外中止合成")
                        }
                    }
                }
            }
            runCatching { tts.stop() }
            return SystemTtsOutcome.Failed("sys_timeout", "系统引擎 ${candidate.label} 合成超时")
        } finally {
            currentUtteranceId = null
            currentQueue = null
            runCatching { tempWav.delete() }
        }
    }

    private fun readWavFallback(
        wavFile: File,
        candidate: TtsEngineCandidate,
        defaultSr: Int,
        onChunk: (TtsAudioChunk) -> Boolean,
        isCancelled: () -> Boolean,
    ): SystemTtsOutcome {
        if (!wavFile.exists() || wavFile.length() <= 44L) {
            return SystemTtsOutcome.Failed("empty_wav", "系统引擎 ${candidate.label} 未生成有效音频")
        }
        var detectedSr = defaultSr
        val res = FileInputStream(wavFile).use { stream ->
            AudioStreamDecoder.decodeStream(
                input = stream,
                formatHint = "wav",
                contentType = "audio/wav",
                defaultSampleRate = defaultSr,
                onChunk = { chunk ->
                    if (chunk.sampleRate > 0) detectedSr = chunk.sampleRate
                    onChunk(chunk)
                },
                isCancelled = isCancelled,
            )
        }
        return when {
            res.cancelled || isCancelled() -> SystemTtsOutcome.Cancelled
            res.bytes > 0 -> SystemTtsOutcome.Ok(
                enginePackage = candidate.packageName,
                engineLabel = candidate.label,
                bytes = res.bytes,
                sampleRate = detectedSr,
            )
            else -> SystemTtsOutcome.Failed(
                "wav_decode_failed",
                res.error ?: "系统 TTS 音频文件解码失败",
            )
        }
    }

    private fun releaseEngineLocked() {
        currentUtteranceId = null
        currentQueue = null
        activeLocaleReady = false
        val old = activeEngine
        activeEngine = null
        activePackage = null
        activeLabel = ""
        if (old != null) {
            runCatching { old.stop() }
            runCatching { old.shutdown() }
        }
    }

    private companion object {
        private const val TAG = "SystemTtsBackend"
        private const val INIT_TIMEOUT_MS = 5_000L
        private const val SYNTH_TIMEOUT_MS = 45_000L
        private const val DEFAULT_SYSTEM_SR = 24_000
    }
}

object SystemTtsBackendHolder {
    @Volatile
    private var instance: SystemTtsBackend? = null

    fun get(context: Context): SystemTtsBackend {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val created = SystemTtsBackend(context.applicationContext)
            instance = created
            return created
        }
    }
}
