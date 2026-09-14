package com.mimo.ebook2tts.engine

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mimo.ebook2tts.core.ModelCatalog
import com.mimo.ebook2tts.core.VoiceCatalog
import com.tencent.mmkv.MMKV

/**
 * 配置存储：MMKV 多进程（ADR-002）。
 * 主进程写 → 广播 ENGINE_RELOAD → 引擎热读。
 */
object ConfigStore {
    const val ACTION_ENGINE_RELOAD = "com.mimo.ebook2tts.local.ENGINE_RELOAD"
    const val ACTION_ENGINE_STATUS = "com.mimo.ebook2tts.local.ENGINE_STATUS_CHANGED"

    private const val MMKV_ID = "ebook2tts_cfg"
    private const val TAG = "ConfigStore"

    private fun kv(): MMKV = MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE)

    fun modelId(): String = kv().decodeString("model.id", ModelCatalog.DEFAULT_ID)!!
    fun setModelId(id: String) {
        kv().encode("model.id", id)
    }

    fun narratorVoice(): String =
        kv().decodeString("voice.narrator", VoiceCatalog.DEFAULT_NARRATOR_ID)!!

    fun setNarratorVoice(id: String) {
        kv().encode("voice.narrator", id)
    }

    fun roleMode(): String = kv().decodeString("role.mode", "smart")!!
    fun setRoleMode(mode: String) {
        kv().encode("role.mode", mode)
    }

    fun speedMode(): String = kv().decodeString("speed.mode", "readerFirst")!!
    fun setSpeedMode(mode: String) {
        kv().encode("speed.mode", mode)
    }

    fun threads(): Int = kv().decodeInt("engine.threads", 2)
    fun setThreads(n: Int) {
        kv().encode("engine.threads", n.coerceIn(1, 4))
    }

    fun statusState(): String = kv().decodeString("status.state", "NO_MODEL")!!
    fun setStatusState(s: String) {
        kv().encode("status.state", s)
    }

    fun statusModelId(): String = kv().decodeString("status.modelId", "")!!
    fun setStatusModelId(id: String) {
        kv().encode("status.modelId", id)
    }

    fun statusLastError(): String = kv().decodeString("status.lastError", "")!!
    fun setStatusLastError(msg: String) {
        kv().encode("status.lastError", msg)
    }

    fun dropUnitCount(): Int = kv().decodeInt("status.dropUnit", 0)
    fun bumpDropUnit() {
        kv().encode("status.dropUnit", dropUnitCount() + 1)
    }

    fun migrationDone(): Boolean = kv().decodeBool("migration.v2.done", false)
    fun setMigrationDone(v: Boolean) {
        kv().encode("migration.v2.done", v)
    }

    /** 自定义镜像基地址（IM-201）；空 = 用官方源 */
    fun mirrorBase(): String = kv().decodeString("mirror.base", "")!!
    fun setMirrorBase(url: String) {
        kv().encode("mirror.base", url.trim())
    }

    /** 清单上次远程拉取成功时间（毫秒；0 = 从未成功） */
    fun manifestFetchedAt(): Long = kv().decodeLong("manifest.fetchedAt", 0L)
    fun setManifestFetchedAt(at: Long) {
        kv().encode("manifest.fetchedAt", at)
    }

    fun notifyReload(context: Context, reason: String = "config") {
        val i = Intent(ACTION_ENGINE_RELOAD).setPackage(context.packageName)
        i.putExtra("reason", reason)
        context.sendBroadcast(i)
        Log.i(TAG, "broadcast reload reason=$reason")
    }

    fun notifyStatus(context: Context, code: String = "") {
        val i = Intent(ACTION_ENGINE_STATUS).setPackage(context.packageName)
        i.putExtra("code", code)
        context.sendBroadcast(i)
    }
}
