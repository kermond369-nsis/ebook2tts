package com.kermond.ebook2tts

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.kermond.ebook2tts.core.ProcessNames
import com.kermond.ebook2tts.engine.ConfigStore
import com.kermond.ebook2tts.engine.MigrationHelper
import com.tencent.mmkv.MMKV

/**
 * Application 进程守卫（AR-§5.1.1）。
 * :tts_service 进程仅 MMKV/日志/迁移；主进程再做 UI 初始化。
 */
class LocalApp : Application() {

    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        MigrationHelper.checkAndMigrate(this)
        val process = currentProcessName()
        val isEngine = ProcessNames.isEngineProcess(process)
        Log.i(TAG, "onCreate process=$process engine=$isEngine")
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName()
        return try {
            ProcessNames.parseCmdline(java.io.File("/proc/self/cmdline").readBytes())
        } catch (_: Throwable) {
            packageName
        }
    }

    companion object {
        private const val TAG = "LocalApp"

        fun isEngineProcess(context: Context): Boolean {
            val p = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
            else runCatching {
                ProcessNames.parseCmdline(java.io.File("/proc/self/cmdline").readBytes())
            }.getOrDefault(context.packageName)
            return ProcessNames.isEngineProcess(p)
        }
    }
}
