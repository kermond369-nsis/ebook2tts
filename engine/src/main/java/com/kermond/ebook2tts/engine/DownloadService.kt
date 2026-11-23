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
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 模型下载前台服务（ADR-004 / RQ-201）：dataSync 类型。
 *
 * 与 UI 主进程同属一进程，通过伴生静态句柄同步中断 `ModelDownloader` 与工作任务，
 * 并主动广播 `DL_PROGRESS` / `DL_DONE` / `DL_CANCELLED` / `DL_ERROR` 确保界面实时感知阶段与镜像通道。
 */
class DownloadService : Service() {

    data class Snapshot(
        val modelId: String,
        val phase: String,
        val received: Long,
        val total: Long,
        val sourceLabel: String = "",
    )

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "model-download").apply { isDaemon = true }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Log.i(TAG, "cancel action received: abort in-flight io")
            abortActive()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID) ?: ModelCatalog.DEFAULT_ID
        abortActive()
        cancelRequested.set(false)

        val initSpec = ModelCatalog.byId(modelId)
        val initTotal = initSpec.downloadBytes + initSpec.files.vocoderBytes
        snapshotRef.set(Snapshot(modelId, "probe", 0L, initTotal, ""))
        startAsForeground("模型下载：${initSpec.label}")

        val dl = ModelDownloader(this)
        activeDownloader.set(dl)

        val task = executor.submit {
            var currentSource = ""
            var lastNotifyAt = 0L
            var lastStage = ""
            try {
                if (cancelRequested.get()) {
                    onFinishedCancelled(dl, modelId, 0L, initTotal, startId)
                    return@submit
                }
                if (!NetPolicy.allowsNetwork(ConfigStore.netAllowMobileData(), NetState.onCellular(this@DownloadService))) {
                    Log.w(TAG, "DOWNLOAD_BLOCKED|$modelId|reason=${NetPolicy.REASON_CELLULAR_DISALLOWED}")
                    val msg = "当前使用移动数据，已暂停下载。请在 Wi-Fi 下重试，或在设置中开启「允许使用移动数据流量」"
                    snapshotRef.set(null)
                    updateNotification(initSpec.label, msg)
                    ConfigStore.notifyStatus(this@DownloadService, "DL_ERROR|$modelId|$msg")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return@submit
                }
                val spec = runCatching {
                    ManifestRepository(this@DownloadService, dl.http).specs().firstOrNull { it.id == modelId }
                }.getOrNull() ?: initSpec
                if (cancelRequested.get()) {
                    onFinishedCancelled(dl, modelId, 0L, initTotal, startId)
                    return@submit
                }
                dl.downloadAndInstall(spec, object : ModelDownloader.Progress {
                    override fun onSourceChanged(sourceLabel: String) {
                        currentSource = sourceLabel
                        val cur = snapshotRef.get()
                        if (cur != null && cur.modelId == modelId) {
                            snapshotRef.set(cur.copy(sourceLabel = sourceLabel))
                        }
                        ConfigStore.notifyStatus(
                            this@DownloadService,
                            "DL_PROGRESS|$modelId|${cur?.received ?: 0L}|${cur?.total ?: initTotal}|${cur?.phase ?: "download"}|$sourceLabel",
                        )
                    }

                    override fun onProgress(bytes: Long, total: Long, stage: String) {
                        if (cancelRequested.get() || dl.isCancelled()) return
                        snapshotRef.set(Snapshot(modelId, stage, bytes, total, currentSource))
                        val now = android.os.SystemClock.uptimeMillis()
                        if (stage != lastStage || now - lastNotifyAt >= 450L) {
                            lastStage = stage
                            lastNotifyAt = now
                            val p = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
                            updateNotification(spec.label, "${stageText(stage)} $p%")
                            ConfigStore.notifyStatus(
                                this@DownloadService,
                                "DL_PROGRESS|$modelId|$bytes|$total|$stage|$currentSource",
                            )
                        }
                    }

                    override fun onSuccess(modelId: String) {
                        snapshotRef.set(null)
                        updateNotification(spec.label, "完成")
                        ConfigStore.notifyStatus(this@DownloadService, "DL_DONE|$modelId")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }

                    override fun onError(message: String) {
                        if (cancelRequested.get() || dl.isCancelled()) return
                        snapshotRef.set(null)
                        val safe = ErrorSanitizer.sanitize(message, 140)
                        updateNotification(spec.label, safe)
                        ConfigStore.notifyStatus(this@DownloadService, "DL_ERROR|$modelId|$safe")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }

                    override fun onCancelled(bytes: Long, total: Long) {
                        onFinishedCancelled(dl, modelId, bytes, total, startId)
                    }
                })
            } finally {
                activeDownloader.compareAndSet(dl, null)
            }
        }
        activeTask.set(task)
        return START_NOT_STICKY
    }

    private fun onFinishedCancelled(
        dl: ModelDownloader,
        modelId: String,
        bytes: Long,
        total: Long,
        startId: Int,
    ) {
        Log.i(TAG, "CANCELLED|model=$modelId|bytes=$bytes|total=$total")
        snapshotRef.set(null)
        activeDownloader.compareAndSet(dl, null)
        ConfigStore.notifyStatus(this@DownloadService, "DL_CANCELLED|$modelId")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun abortActive() {
        activeDownloader.getAndSet(null)?.cancel()
        activeTask.getAndSet(null)?.cancel(true)
        snapshotRef.set(null)
    }

    private fun stageText(stage: String): String = when (stage) {
        "probe" -> "正在测速选源"
        "download" -> "下载中"
        "download_vocoder" -> "下载声码器"
        "verify" -> "校验完整性"
        "extract" -> "解压中"
        "promote" -> "安装收尾"
        else -> stage
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
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(FOREGROUND_ID, buildNotification(title, content))
        }
    }

    override fun onDestroy() {
        abortActive()
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODEL_ID = "model_id"
        const val ACTION_CANCEL = "com.kermond.ebook2tts.action.DOWNLOAD_CANCEL"
        private const val CHANNEL = "download"
        private const val FOREGROUND_ID = 42
        private const val TAG = "DownloadService"

        private val activeDownloader = AtomicReference<ModelDownloader?>(null)
        private val activeTask = AtomicReference<Future<*>?>(null)
        private val snapshotRef = AtomicReference<Snapshot?>(null)
        private val cancelRequested = AtomicBoolean(false)

        fun currentSnapshot(): Snapshot? = snapshotRef.get()

        fun start(context: Context, modelId: String) {
            cancelRequested.set(false)
            val i = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_MODEL_ID, modelId)
            context.startForegroundService(i)
        }

        /**
         * 同步秒级取消（无需等待 `startService` 排队或 readTimeout）：
         * 直接置取消标记、中断在途 HTTP socket、终止后台任务、清空快照并广播取消状态。
         */
        fun cancel(context: Context) {
            cancelRequested.set(true)
            val prev = snapshotRef.getAndSet(null)
            activeDownloader.getAndSet(null)?.cancel()
            activeTask.getAndSet(null)?.cancel(true)
            ConfigStore.notifyStatus(context, "DL_CANCELLED|${prev?.modelId.orEmpty()}")
            runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
        }
    }
}
