package com.kermond.ebook2tts.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * 试听入口（IM-114 / IM-406b）。
 *
 * 为什么必须放在这里：推理只允许发生在 `:tts_service` 进程（架构书 AR-§1.7，主进程零推理）。
 * 界面进程（Flutter App）不能直接持有 [PreviewPlayer]，否则会被 [ProcessGuard] 拒绝
 * （实测返回 `-12 inference_outside_engine_process`）。故界面侧只发意图，本服务在引擎进程内代跑。
 *
 * 语义保证：试听**只播放样音**，绝不写 `ConfigStore.narratorVoice()`（旁白与试听彻底分离）。
 */
class PreviewService : Service() {

    private var player: PreviewPlayer? = null

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
                val p = player ?: PreviewPlayer(this).also { player = it }
                val speed = ConfigStore.speedValue()
                Log.i(TAG, "PREVIEW|start|voice=${voice ?: "跟随旁白"}|chars=${text.length}|speed=$speed")
                p.preview(text, voice, speed, object : PreviewPlayer.Listener {
                    override fun onStart(sampleRate: Int) {
                        Log.i(TAG, "PREVIEW|playing|sr=$sampleRate")
                    }

                    override fun onProgress(percent: Int) {
                        // 逐次打印太吵，仅在整十进度留痕
                        if (percent % 25 == 0) Log.i(TAG, "PREVIEW|progress=$percent")
                    }

                    override fun onDone() {
                        Log.i(TAG, "PREVIEW|done")
                        stopSelf()
                    }

                    override fun onError(code: Int, message: String) {
                        Log.w(TAG, "PREVIEW|error|code=$code|msg=$message")
                        stopSelf()
                    }
                })
            }

            ACTION_STOP -> {
                player?.stop()
                Log.i(TAG, "PREVIEW|stopped_by_user")
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

        /** 试听一段文本（可在 voiceId 指定音色；null = 跟随当前旁白）。不写任何全局配置。 */
        fun preview(context: Context, text: String, voiceId: String?) {
            val intent = Intent(context, PreviewService::class.java)
                .setAction(ACTION_PREVIEW)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_VOICE, voiceId)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "PREVIEW|start_failed|${it.javaClass.simpleName}") }
        }

        /** 停止试听。 */
        fun stop(context: Context) {
            val intent = Intent(context, PreviewService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "PREVIEW|stop_failed|${it.javaClass.simpleName}") }
        }
    }
}
