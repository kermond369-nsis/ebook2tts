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
        startRoleRefineLoop(isEngine)
    }

    /**
     * 角色精标调度（RQ-507 / ADR-013）：**仅主进程**，后台守护线程按周期尝试。
     *
     * 纪律：
     * - 精标＝文本 LLM 调用，红线 4 禁止出现在 `:tts_service`（此处显式跳过引擎进程；
     *   精标器内部还有第二道进程守卫）；
     * - 每 tick 只做**两次 MMKV 布尔读**即短路（在线关或角色关 → 直接返回，不发请求）；
     * - 精标器自带限频（≥10s）与"无待精标候选即退出"，失败静默。
     */
    private fun startRoleRefineLoop(isEngine: Boolean) {
        if (isEngine) return
        Thread({
            while (true) {
                try {
                    Thread.sleep(ROLE_TICK_MS)
                    if (!ConfigStore.onlineEnabled() || !ConfigStore.roleVoiceEnabled()) continue
                    val r = OnlineRoleRefiner(this).refinePending()
                    if (r.refined > 0) {
                        Log.i(TAG, "role refine tick: refined=${r.refined}")
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                } catch (t: Throwable) {
                    // 精标失败不得影响任何功能
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
            ProcessNames.parseCmdline(
                java.io.File("/proc/self/cmdline").readBytes()
            )
        } catch (_: Throwable) {
            packageName
        }
    }

    companion object {
        private const val TAG = "EngineApp"

        /** 精标轮询周期（主进程后台线程；引擎侧另有 ≥10s 限频） */
        private const val ROLE_TICK_MS = 15_000L

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
