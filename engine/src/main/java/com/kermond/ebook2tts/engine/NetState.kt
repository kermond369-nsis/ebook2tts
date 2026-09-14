package com.kermond.ebook2tts.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * 当前网络传输探测（引擎侧薄适配；判定策略在 `core.NetPolicy`，纯函数可单测）。
 *
 * 仅在线开关打开时才被调用（在线关闭路径零触达——见 SynthesisCoordinator.selectOnline）。
 */
object NetState {
    private const val TAG = "NetState"

    /** 当前活动网络是否为蜂窝；无网络/无法判定 → false（交由实际请求失败与回落兜底） */
    fun onCellular(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false
    } catch (t: Throwable) {
        Log.w(TAG, "net probe failed: ${t.javaClass.simpleName}")
        false
    }
}
