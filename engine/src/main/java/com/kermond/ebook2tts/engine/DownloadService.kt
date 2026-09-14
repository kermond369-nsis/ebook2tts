package com.kermond.ebook2tts.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kermond.ebook2tts.core.ModelCatalog
import java.util.concurrent.Executors

/**
 * 模型下载前台服务（ADR-004 / RQ-201）：dataSync 类型。
 */
class DownloadService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private var downloader: ModelDownloader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID) ?: ModelCatalog.DEFAULT_ID
        // 通知标题先用通用文案：清单解析含网络（远程 manifest），严禁在主线程做
        startAsForeground("模型下载")
        val dl = ModelDownloader(this)
        downloader = dl
        executor.execute {
            // IM-201：清单驱动（远程清单 → 缓存 → 内置兜底）；在工作线程解析，避免 NetworkOnMainThread
            val spec = runCatching { ModelRegistry.byId(this@DownloadService, modelId) }
                .getOrDefault(ModelCatalog.byId(modelId))
            dl.downloadAndInstall(spec, object : ModelDownloader.Progress {
                override fun onProgress(bytes: Long, total: Long, stage: String) {
                    val p = if (total > 0) ((bytes * 100) / total).toInt() else 0
                    updateNotification(spec.label, "$stage $p%")
                    ConfigStore.notifyStatus(this@DownloadService, "DL|$stage|$p")
                }

                override fun onSuccess(modelId: String) {
                    updateNotification(spec.label, "完成")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }

                override fun onError(message: String) {
                    updateNotification(spec.label, message)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            })
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "模型下载",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        ensureChannel()
        val pi = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun startAsForeground(title: String) {
        val n = buildNotification(title, "准备下载…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(FOREGROUND_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FOREGROUND_ID, n)
        }
    }

    private fun updateNotification(title: String, content: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(FOREGROUND_ID, buildNotification(title, content))
    }

    override fun onDestroy() {
        downloader?.cancel()
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODEL_ID = "model_id"
        private const val CHANNEL = "download"
        private const val FOREGROUND_ID = 42

        fun start(context: Context, modelId: String) {
            val i = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_MODEL_ID, modelId)
            context.startForegroundService(i)
        }
    }
}
