package com.kermond.ebook2tts.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * 当前网络传输与连通性探测（引擎侧薄适配；策略规则在 `core.NetPolicy`）。
 */
object NetState {
    private const val TAG = "NetState"

    /** 当前活动网络是否为蜂窝；无网络或无法判定返回 false */
    fun onCellular(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false
    } catch (t: Throwable) {
        Log.w(TAG, "net probe failed: ${t.javaClass.simpleName}")
        false
    }

    /**
     * 当前是否具备可用互联网连接能力：
     * 故意不校验 `NET_CAPABILITY_VALIDATED`，避免国内环境因默认连通性探针被墙而误判离线。
     */
    fun isConnected(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val hasTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        hasInternet && hasTransport
    } catch (t: Throwable) {
        Log.w(TAG, "connectivity probe failed: ${t.javaClass.simpleName}")
        true
    }
}
