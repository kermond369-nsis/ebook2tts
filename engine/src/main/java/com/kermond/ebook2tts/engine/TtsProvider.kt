package com.kermond.ebook2tts.engine

import com.kermond.ebook2tts.core.OnlineSettings
import com.kermond.ebook2tts.core.TtsProviderKind
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 解码后的单声道 PCM16 音频块 */
data class TtsAudioChunk(
    val pcm16Mono: ByteArray,
    val sampleRate: Int,
)

/**
 * 通用在线 TTS 引擎适配器接口（在 `:tts_service` 合成，或主进程测试连通性时调用）。
 */
interface TtsProvider {
    val kind: TtsProviderKind

    fun synthesize(
        request: OnlineRequest,
        text: String,
        speed: Float,
        onChunk: (TtsAudioChunk) -> Boolean,
        isCancelled: () -> Boolean,
    ): OnlineOutcome

    /** 校验配置/连通性：null = 通过；否则为脱敏后的可读错误摘要 */
    fun validate(request: OnlineRequest): String?
}

/**
 * 在线请求共享执行与弱网首包硬超时看门狗：
 * 统一调度线程池、50ms 取消轮询、首包超时强制断连及异常归类，消除三引擎适配器重复代码。
 */
object OnlineCallRunner {
    private const val CANCEL_TICK_MS = 50L

    fun clientForTimeout(base: OkHttpClient, timeoutMs: Int): OkHttpClient {
        val clamped = OnlineSettings.clampTimeoutMs(timeoutMs).toLong()
        val connectMs = minOf(base.connectTimeoutMillis.toLong().coerceAtLeast(300L), minOf(clamped, 5_000L))
        val writeMs = minOf(base.writeTimeoutMillis.toLong().coerceAtLeast(300L), maxOf(clamped, 3_000L))
        val readMs = minOf(base.readTimeoutMillis.toLong().coerceAtLeast(300L), maxOf(clamped, 8_000L))
        return base.newBuilder()
            .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .writeTimeout(writeMs, TimeUnit.MILLISECONDS)
            .readTimeout(readMs, TimeUnit.MILLISECONDS)
            .build()
    }

    fun executeStream(
        io: ExecutorService,
        timeoutMs: Int,
        firstByteSeen: AtomicBoolean,
        isCancelled: () -> Boolean,
        task: (AtomicReference<Call?>) -> OnlineOutcome,
    ): OnlineOutcome {
        val callRef = AtomicReference<Call?>(null)
        val clampedTimeout = OnlineSettings.clampTimeoutMs(timeoutMs).toLong()
        val job = try {
            io.submit(Callable { task(callRef) })
        } catch (_: RejectedExecutionException) {
            return OnlineOutcome.Failed(OnlineFailureKind.NETWORK, "pool_shutdown", "在线线程池不可用")
        }
        val startedMs = System.currentTimeMillis()
        while (true) {
            if (isCancelled()) {
                callRef.get()?.cancel()
                job.cancel(true)
                return OnlineOutcome.Cancelled
            }
            if (!firstByteSeen.get() && (System.currentTimeMillis() - startedMs) >= clampedTimeout) {
                callRef.get()?.cancel()
                job.cancel(true)
                val sec = clampedTimeout / 1000L
                return OnlineOutcome.Failed(
                    OnlineFailureKind.TIMEOUT,
                    "timeout",
                    "弱网超时（${sec}秒未收到首包），已回退系统 TTS",
                )
            }
            try {
                return job.get(CANCEL_TICK_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // 等待结果的同时保持 ≤50ms 取消与弱网首包超时响应
            } catch (e: ExecutionException) {
                val cause = e.cause
                val summary = ErrorSanitizer.sanitize(cause?.message ?: cause?.javaClass?.simpleName ?: "worker_error")
                return OnlineOutcome.Failed(OnlineFailureKind.NETWORK, "worker", summary)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                callRef.get()?.cancel()
                return OnlineOutcome.Cancelled
            }
        }
    }

    fun executeValidation(
        io: ExecutorService,
        task: () -> String?,
    ): String? {
        val job = try {
            io.submit(Callable { task() })
        } catch (_: RejectedExecutionException) {
            return "在线线程池不可用"
        }
        return try {
            job.get()
        } catch (e: ExecutionException) {
            ErrorSanitizer.sanitize(e.cause?.message ?: "网络错误")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            job.cancel(true)
            "已取消"
        }
    }

    fun httpFailure(code: Int, rawBody: String): OnlineOutcome.Failed {
        val failKind = when (code) {
            401, 403 -> OnlineFailureKind.AUTH
            429 -> OnlineFailureKind.RATE_LIMIT
            else -> OnlineFailureKind.HTTP
        }
        return OnlineOutcome.Failed(
            failKind,
            "http_$code",
            ErrorSanitizer.sanitize("HTTP $code: $rawBody"),
        )
    }

    fun throwableFailure(
        t: Throwable,
        isCancelled: () -> Boolean,
        call: Call?,
    ): OnlineOutcome {
        if (isCancelled() || call?.isCanceled() == true) {
            return OnlineOutcome.Cancelled
        }
        val isTimeout = t is InterruptedIOException
        val failKind = if (isTimeout) OnlineFailureKind.TIMEOUT else OnlineFailureKind.NETWORK
        val reason = if (isTimeout) "timeout" else "network"
        return OnlineOutcome.Failed(
            failKind,
            reason,
            ErrorSanitizer.sanitize(t.message ?: t.javaClass.simpleName),
        )
    }
}

/** 错误文本脱敏与截断（防止错误回显泄露 Authorization / API Key） */
object ErrorSanitizer {
    private val SENSITIVE_BEARER = Regex("""(?i)Bearer\s+[A-Za-z0-9._-]+""")
    private val SENSITIVE_KEY = Regex("""\b(?:sk|tp)-[A-Za-z0-9_-]{4,}""")

    fun sanitize(raw: String?, maxLen: Int = 200): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return text
            .replace(SENSITIVE_BEARER, "Bearer ***")
            .replace(SENSITIVE_KEY, "***")
            .take(maxLen)
    }
}

/** 允许 https:// 远程地址，或本机回环 http://localhost / 127.0.0.1 / [::1]（供自建回环服务及 MockWebServer 单测） */
object EndpointUrlPolicy {
    private val LOOPBACK_HTTP = Regex("""(?i)^http://(localhost|127\.0\.0\.1|\[::1\])(:\d+)?(/.*)?$""")

    fun isAllowed(raw: String): Boolean {
        val s = raw.trim()
        if (s.startsWith("https://", ignoreCase = true) && s.length > "https://".length) return true
        return LOOPBACK_HTTP.matches(s)
    }
}
