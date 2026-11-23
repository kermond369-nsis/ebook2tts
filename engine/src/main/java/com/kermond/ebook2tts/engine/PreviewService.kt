package com.kermond.ebook2tts.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * 试听入口（运行在 `:tts_service` 进程）。
 */
class PreviewService : Service() {

    private var player: PreviewPlayer? = null
    private val activeGen = AtomicLong(0L)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREVIEW -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                val voice = intent.getStringExtra(EXTRA_VOICE)?.takeIf { it.isNotBlank() }
                if (text.isBlank()) {
                    Log.w(TAG, "PREVIEW|ignored|reason=empty_text")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val gen = activeGen.incrementAndGet()
                val p = player ?: PreviewPlayer(this).also { player = it }
                val speed = ConfigStore.speedValue()
                Log.i(TAG, "PREVIEW|start|gen=$gen|voice=${voice ?: "默认"}|chars=${text.length}|speed=$speed")
                ConfigStore.notifyStatus(this, "PREVIEW_START|${voice.orEmpty()}")
                p.preview(text, voice, speed, object : PreviewPlayer.Listener {
                    override fun onStart(sampleRate: Int) {
                        if (activeGen.get() != gen) return
                        Log.i(TAG, "PREVIEW|playing|sr=$sampleRate")
                        ConfigStore.notifyStatus(this@PreviewService, "PREVIEW_START|${voice.orEmpty()}")
                    }

                    override fun onStage(stage: String) {
                        if (activeGen.get() != gen) return
                        Log.i(TAG, "PREVIEW|stage=$stage|gen=$gen")
                        ConfigStore.notifyStatus(this@PreviewService, "PREVIEW_STAGE|$stage")
                    }

                    override fun onProgress(percent: Int) {
                        if (activeGen.get() != gen) return
                        if (percent % 25 == 0) Log.i(TAG, "PREVIEW|progress=$percent")
                    }

                    override fun onDone() {
                        if (activeGen.get() != gen) return
                        Log.i(TAG, "PREVIEW|done|gen=$gen")
                        ConfigStore.notifyStatus(this@PreviewService, "PREVIEW_DONE")
                        stopSelf(startId)
                    }

                    override fun onError(code: Int, message: String) {
                        if (activeGen.get() != gen) return
                        val safe = ErrorSanitizer.sanitize(message, 120)
                        Log.w(TAG, "PREVIEW|error|code=$code|msg=$safe")
                        ConfigStore.notifyStatus(this@PreviewService, "PREVIEW_ERROR|$safe")
                        stopSelf(startId)
                    }
                })
            }

            ACTION_STOP -> {
                activeGen.incrementAndGet()
                player?.stop()
                Log.i(TAG, "PREVIEW|stopped_by_user")
                ConfigStore.notifyStatus(this, "PREVIEW_DONE")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        player?.stop()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PreviewService"

        const val ACTION_PREVIEW = "com.kermond.ebook2tts.action.PREVIEW"
        const val ACTION_STOP = "com.kermond.ebook2tts.action.PREVIEW_STOP"
        const val EXTRA_TEXT = "text"
        const val EXTRA_VOICE = "voice"

        fun preview(context: Context, text: String, voiceId: String?) {
            val intent = Intent(context, PreviewService::class.java)
                .setAction(ACTION_PREVIEW)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_VOICE, voiceId)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "PREVIEW|start_failed|${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PreviewService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "PREVIEW|stop_failed|${it.javaClass.simpleName}") }
        }
    }
}
