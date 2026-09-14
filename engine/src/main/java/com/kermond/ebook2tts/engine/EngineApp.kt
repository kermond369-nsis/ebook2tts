package com.kermond.ebook2tts.engine

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.kermond.ebook2tts.core.ProcessNames
import com.tencent.mmkv.MMKV

/**
 * Application 进程守卫（AR-§5.1.1 / ISSUE-03）。
 * :tts_service 进程严禁初始化 Flutter / UI 组件。
 */
class EngineApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val process = currentProcessName()
        val isEngine = ProcessNames.isEngineProcess(process)
        MMKV.initialize(this)
        MigrationHelper.checkAndMigrate(this)
        Log.i(TAG, "onCreate process=$process engine=$isEngine")
        // 主进程 UI/下载组件由 :app 模块自行初始化；此处仅做引擎侧共享初始化
        ProcessGuard.assertEngineProcessIfNeeded(isEngine)
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= 28) {
            return Application.getProcessName()
        }
        return try {
            ProcessNames.parseCmdline(
                java.io.File("/proc/self/cmdline").readBytes()
            )
        } catch (_: Throwable) {
            packageName
        }
    }

    companion object {
        private const val TAG = "EngineApp"

        fun requireEngineProcess(context: Context): Boolean =
            ProcessNames.isEngineProcess(
                if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
                else runCatching {
                    ProcessNames.parseCmdline(java.io.File("/proc/self/cmdline").readBytes())
                }.getOrDefault(context.packageName)
            )
    }
}

object ProcessGuard {
    fun assertEngineProcessIfNeeded(isEngine: Boolean) {
        // 仅记录；推理入口处再硬断言
        if (!isEngine) {
            Log.d(TAG, "non-engine process init")
        }
    }

    fun assertEngineProcess(context: Context) {
        check(EngineApp.requireEngineProcess(context)) {
            "Inference must run in :tts_service process only (AR-§1.7)"
        }
    }

    private const val TAG = "ProcessGuard"
}
