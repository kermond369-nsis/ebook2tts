package com.mimo.ebook2tts.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.mimo.ebook2tts.core.ModelCatalog
import com.mimo.ebook2tts.core.ModelSpec
import com.mimo.ebook2tts.core.VoiceCatalog
import com.tencent.mmkv.MMKV
import java.io.File

/**
 * 0.0.2 → 0.2.0 迁移（AR-§11.1 / RQ-205）。
 * 双进程幂等：旗标 + MMKV 跨进程锁。
 */
object MigrationHelper {
    private const val TAG = "Migration"
    private const val LOCK_ID = "migration_lock"

    fun checkAndMigrate(context: Context) {
        if (ConfigStore.migrationDone()) return
        val lock = MMKV.mmkvWithID(LOCK_ID, MMKV.MULTI_PROCESS_MODE)
        lock.lock()
        try {
            if (ConfigStore.migrationDone()) return
            migratePrefs(context)
            backfillCompleted(context)
            ConfigStore.setMigrationDone(true)
            Log.i(TAG, "migration done")
        } catch (t: Throwable) {
            Log.e(TAG, "migration failed", t)
        } finally {
            lock.unlock()
        }
    }

    private fun migratePrefs(context: Context) {
        val sp: SharedPreferences =
            context.getSharedPreferences("local_tts", Context.MODE_PRIVATE)
        val oldVoice = sp.getString("narrator_voice", null)
        if (!oldVoice.isNullOrBlank()) {
            val mapped = when (oldVoice) {
                "zm_058" -> VoiceCatalog.DEFAULT_NARRATOR_ID
                else -> oldVoice
            }
            ConfigStore.setNarratorVoice(mapped)
        }
        val oldModel = sp.getString("model_id", null)
        if (!oldModel.isNullOrBlank()) {
            val mapped = when {
                oldModel.contains("kokoro-int8") -> "kokoro-int8"
                oldModel.contains("zh-ll") -> "vits-zh-ll"
                oldModel.contains("kokoro-multi") -> "kokoro-fp32"
                else -> oldModel
            }
            ConfigStore.setModelId(mapped)
        }
        val oldBackend = sp.getString("backend", null)
        if (oldBackend == "system") {
            ConfigStore.setModelId(ModelCatalog.DEFAULT_ID)
        }
        // 旧模型目录兼容识别
        val modelsDir = File(context.filesDir, "models")
        if (modelsDir.isDirectory) {
            val legacy = modelsDir.listFiles()?.firstOrNull { it.isDirectory }
            if (legacy != null && ConfigStore.statusModelId().isEmpty()) {
                ConfigStore.setStatusModelId(legacy.name)
            }
        }
    }

    /** 存量模型**清单校验通过后**才回填 .completed（红线 8：防坏包被标记为可用） */
    private fun backfillCompleted(context: Context) {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.isDirectory) return
        modelsDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val sentinel = File(dir, ".completed")
            if (sentinel.exists()) return@forEach
            val id = dir.name
            val spec = ModelCatalog.ALL.firstOrNull {
                it.id == id || it.archiveName.removeSuffix(".tar.bz2") == id
            } ?: return@forEach
            if (validateModelDir(dir, spec)) {
                runCatching { sentinel.writeText("migrated") }
                Log.i(TAG, "backfilled .completed for ${dir.name}")
            } else {
                Log.w(TAG, "WARN|SKIP_BACKFILL|incomplete|${dir.name}")
            }
        }
    }

    /**
     * 模型目录清单校验（红线 8）：模型/词表/音色池/词典/规则 FST 全部存在且非空，
     * 缺一即判不可用——不写哨兵，交由正常下载/导入流程重建。
     */
    private fun validateModelDir(dir: File, spec: ModelSpec): Boolean {
        fun ok(name: String): Boolean {
            if (name.isBlank()) return true
            val f = File(dir, name)
            return f.isFile && f.length() > 0L
        }

        if (!ok(spec.files.modelName)) return false
        if (!ok(spec.files.tokens)) return false
        if (!ok(spec.files.voices)) return false
        for (name in spec.files.lexicon.split(",")) {
            if (!ok(name.trim())) return false
        }
        for (name in spec.files.ruleFsts.split(",")) {
            if (!ok(name.trim())) return false
        }
        if (spec.files.dataDir.isNotBlank() && !File(dir, spec.files.dataDir).isDirectory) return false
        return true
    }
}
