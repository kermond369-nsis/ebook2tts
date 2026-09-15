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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kermond.ebook2tts.core.ModelCatalog
import com.kermond.ebook2tts.core.NetPolicy
import java.util.concurrent.Executors

/**
 * 模型下载前台服务（ADR-004 / RQ-201）：dataSync 类型。
 */
class DownloadService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private var downloader: ModelDownloader? = null

    /** 与 [ModelDownloader] 同步的取消视图（供通知收尾判断） */
    private val cancelled: Boolean get() = downloader?.isCancelled() == true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 取消动作（P6 / IM-520）：必须能**立即**执行，且不依赖工作线程退出。
        // 此前只有 stopService() 一条路 ⇒ 工作线程卡在阻塞读时服务迟迟不退（Stop FGS timeout）。
        if (intent?.action == ACTION_CANCEL) {
            Log.i(TAG, "cancel action received: abort in-flight io")
            downloader?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID) ?: ModelCatalog.DEFAULT_ID
        // 通知标题先用通用文案：清单解析含网络（远程 manifest），严禁在主线程做
        startAsForeground("模型下载")
        val dl = ModelDownloader(this)
        downloader = dl
        executor.execute {
            // 网络策略（core.NetPolicy）：蜂窝 + 未允许数据流量 → 不拉清单、不下载
            if (!NetPolicy.allowsNetwork(ConfigStore.netAllowMobileData(), NetState.onCellular(this@DownloadService))) {
                Log.w(TAG, "DOWNLOAD_BLOCKED|$modelId|reason=${NetPolicy.REASON_CELLULAR_DISALLOWED}")
                updateNotification("模型下载", "使用移动数据，已暂停")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@execute
            }
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
                    if (cancelled) return // 已走取消路径，勿覆盖提示
                    updateNotification(spec.label, message)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }

                override fun onCancelled(bytes: Long, total: Long) {
                    Log.i(TAG, "CANCELLED|bytes=$bytes|total=$total 进度保留")
                    updateNotification(spec.label, "已取消（进度已保留）")
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
        const val ACTION_CANCEL = "com.kermond.ebook2tts.action.DOWNLOAD_CANCEL"
        private const val CHANNEL = "download"
        private const val FOREGROUND_ID = 42
        private const val TAG = "DownloadService"

        fun start(context: Context, modelId: String) {
            val i = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_MODEL_ID, modelId)
            context.startForegroundService(i)
        }

        /**
         * 取消下载（P6 / IM-520）：先把取消动作投递给引擎进程内的服务（立即断流 + 退前台），
         * 再 `stopService` 兜底。二者顺序不可颠倒。
         */
        fun cancel(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL)
                )
            }.onFailure { Log.w(TAG, "cancel intent failed: ${it.javaClass.simpleName}") }
            runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
        }
    }
}
