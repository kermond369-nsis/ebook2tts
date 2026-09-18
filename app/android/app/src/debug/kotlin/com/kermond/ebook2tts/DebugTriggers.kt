package com.kermond.ebook2tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kermond.ebook2tts.engine.CoordinatorHolder

/**
 * **仅 debug 构建**存在的取证钩子（P7/A3）。
 *
 * 背景：`Service.onTrimMemory` 与 `onDestroy` 是本产品在真实内存压力下的释放触发点，
 * 但部分 ROM 上 `am send-trim-memory` 不送达、被绑定的服务也无法 `stopservice` 取证。
 * 本钩子用 adb 广播**直接调用同一代码路径**（[CoordinatorHolder] 的 `releaseIfIdle`），
 * 从而在真机上得到确定性的证据；release 构建不含此类，无生产风险。
 *
 * 用法：`adb shell am broadcast -a com.kermond.ebook2tts.DEBUG_RELEASE_IDLE`
 */
class DebugTriggers : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val coord = CoordinatorHolder.getOrCreate(context.applicationContext)
        val released = coord.releaseIfIdle("debug_broadcast")
        Log.i(TAG, "RELEASE|debug_broadcast|released=$released")
    }

    private companion object {
        const val TAG = "DebugTriggers"
        const val ACTION = "com.kermond.ebook2tts.DEBUG_RELEASE_IDLE"
    }
}
