package com.kermond.ebook2tts.engine

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.kermond.ebook2tts.core.ProcessNames
import com.kermond.ebook2tts.core.TtsProviderKind
import com.tencent.mmkv.MMKV
import java.io.File

/**
 * Application 进程守卫。
 * `:tts_service` 进程严禁初始化 Flutter / UI 组件。
 */
class EngineApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val process = currentProcessName()
        val isEngine = ProcessNames.isEngineProcess(process)
        MMKV.initialize(this)
        Log.i(TAG, "onCreate process=$process engine=$isEngine")
        ProcessGuard.assertEngineProcessIfNeeded(isEngine)
        if (!isEngine) {
            LegacyModelCleanup.cleanupIfNeeded(this)
        }
        startRoleRefineLoop(isEngine)
    }

    /**
     * 角色精标调度（仅主进程后台守护线程）。
     */
    private fun startRoleRefineLoop(isEngine: Boolean) {
        if (isEngine) return
        Thread({
            while (true) {
                try {
                    Thread.sleep(ROLE_TICK_MS)
                    if (!ConfigStore.onlineEnabled() ||
                        ConfigStore.onlineProviderKind() != TtsProviderKind.MIMO ||
                        !ConfigStore.roleVoiceEnabled()
                    ) continue
                    val r = OnlineRoleRefiner(this).refinePending()
                    if (r.refined > 0) {
                        Log.i(TAG, "role refine tick: refined=${r.refined}")
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                } catch (t: Throwable) {
                    Log.w(TAG, "role refine loop: ${t.javaClass.simpleName}")
                }
            }
        }, "role-refine").apply { isDaemon = true }.start()
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= 28) {
            return Application.getProcessName()
        }
        return try {
            ProcessNames.parseCmdline(File("/proc/self/cmdline").readBytes())
        } catch (_: Throwable) {
            packageName
        }
    }

    companion object {
        private const val TAG = "EngineApp"
        private const val ROLE_TICK_MS = 15_000L

        fun requireEngineProcess(context: Context): Boolean =
            ProcessNames.isEngineProcess(
                if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
                else runCatching {
                    ProcessNames.parseCmdline(File("/proc/self/cmdline").readBytes())
                }.getOrDefault(context.packageName),
            )
    }
}

/**
 * 一次性清理旧版（v1.x）残留在内部存储中的纯本地离线模型文件，释放设备空间。
 */
object LegacyModelCleanup {
    private const val TAG = "LegacyCleanup"

    fun cleanupIfNeeded(context: Context) {
        if (ConfigStore.legacyCleanupDone()) return
        Thread({
            runCatching {
                val modelsDir = File(context.filesDir, "models")
                val stagingDir = File(context.filesDir, "models-staging")
                var deleted = false
                if (modelsDir.exists()) {
                    deleted = modelsDir.deleteRecursively() || deleted
                }
                if (stagingDir.exists()) {
                    deleted = stagingDir.deleteRecursively() || deleted
                }
                ConfigStore.setLegacyCleanupDone(true)
                if (deleted) Log.i(TAG, "cleaned legacy offline model directories")
            }.onFailure {
                Log.w(TAG, "cleanup skipped: ${it.javaClass.simpleName}")
            }
        }, "legacy-cleanup").apply { isDaemon = true }.start()
    }
}

object ProcessGuard {
    fun assertEngineProcessIfNeeded(isEngine: Boolean) {
        if (!isEngine) {
            Log.d(TAG, "non-engine process init")
        }
    }

    fun assertEngineProcess(context: Context) {
        check(EngineApp.requireEngineProcess(context)) {
            "Inference must run in :tts_service process only"
        }
    }

    private const val TAG = "ProcessGuard"
}
