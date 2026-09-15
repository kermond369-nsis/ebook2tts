package com.kermond.ebook2tts.engine

import android.content.Context
import android.util.Log
import com.kermond.ebook2tts.core.OnlineRolePolicy
import com.kermond.ebook2tts.core.RoleVoiceDesign
import com.kermond.ebook2tts.core.TextClean
import org.json.JSONArray
import java.util.concurrent.Executors
import com.kermond.ebook2tts.core.TextAnalyzer

/**
 * 角色库（RQ-507 / ADR-013）：MMKV 单键整体 JSON，主进程写、`:tts_service` 读。
 *
 * 三个关注点分开：
 * 1. [profiles] 角色档案（精标产物）；
 * 2. [candidates] 待精标候选（朗读中出现的说话人名，由 `:tts_service` 收集）；
 * 3. [excerpt] 精标输入文本缓存（滚动窗口）。
 *
 * 纪律：
 * - 合成侧**只调 [snapshot] 一次**（请求开始），段循环内零 MMKV 触达（红线 7：映射预构建）；
 * - 合成侧写入（候选/缓存）一律走**单线程后台执行器 + 限频**，绝不占用合成线程；
 * - 所有读写都不抛异常（配置坏了不能把朗读搞停）。
 */
object RoleRegistry {

    private const val TAG = "RoleRegistry"

    /** 后台落盘执行器（守护线程，仅在有朗读时使用） */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "role-store").apply { isDaemon = true }
    }

    private val lastFlushAt = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 角色档案快照（请求级；解析失败 = 空表）。
     *
     * 载入期迁移（2026-09-15）：历史版本用贪婪正则抽说话人，曾落库 `苏岑笑`、`吴伯提醒` 这类被动词污染的脏键。
     * 这里按 [TextAnalyzer.cleanSpeakerHint] 规范化：脏键归一到干净名字（同名则后者覆盖），
     * 使旧库无需人工清理即可自愈。
     */
    fun snapshot(): Map<String, RoleVoiceDesign> {
        val raw = runCatching { RoleRegistryCodec.decodeAll(ConfigStore.roleProfilesJson()) }
            .getOrElse { emptyMap() }
        if (raw.isEmpty()) return emptyMap()
        var dirty = false
        val out = LinkedHashMap<String, RoleVoiceDesign>(raw.size)
        for ((name, profile) in raw) {
            val clean = TextAnalyzer.cleanSpeakerHint(name)
            if (clean == null) {
                dirty = true
                continue
            }
            if (clean != name) dirty = true
            out[clean] = profile.copy(name = clean)
        }
        if (dirty) {
            runCatching { ConfigStore.setRoleProfilesJson(RoleRegistryCodec.encodeAll(out)) }
                .onSuccess { Log.i(TAG, "role registry migrated (dirty names normalized): ${raw.size} -> ${out.size}") }
        }
        return out
    }

    fun refinedNames(): Set<String> = snapshot().keys.toSet()

    fun size(): Int = snapshot().size

    /** 写入/覆盖一个角色档案（**主进程**调用；满库时忽略新角色） */
    fun put(profile: RoleVoiceDesign): Boolean {
        val current = snapshot()
        if (profile.name.isBlank()) return false
        if (!current.containsKey(profile.name) && OnlineRolePolicy.isFull(current.size)) {
            Log.w(TAG, "role library full (${current.size}), skip ${profile.name}")
            return false
        }
        val next = LinkedHashMap(current)
        next[profile.name] = profile
        ConfigStore.setRoleProfilesJson(RoleRegistryCodec.encodeAll(next))
        return true
    }

    fun clearAll() {
        ConfigStore.setRoleProfilesJson("")
    }

    // ---------------- 候选与文本缓存 ----------------

    /**
     * 候选角色名（清洗 + 去重）。
     *
     * 修复（2026-09-15 真机第二轮）：此处若不清洗，旧库里的脏候选（`吴伯提醒`）会继续喂给精标，
     * 精标又会把同名脏档案写回库 —— 只在档案侧做迁移会被"打回原形"。
     */
    fun candidates(): List<String> = runCatching {
        val json = ConfigStore.roleCandidatesJson()
        if (json.isBlank()) return@runCatching emptyList<String>()
        val arr = JSONArray(json)
        (0 until arr.length())
            .mapNotNull { i -> TextAnalyzer.cleanSpeakerHint(arr.optString(i)) }
            .distinct()
    }.getOrElse { emptyList() }

    /** 候选合并（去重、保序、限量 200）；由后台线程调用 */
    fun mergeCandidates(names: Collection<String>) {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return
        val merged = LinkedHashSet(candidates())
        for (n in clean) {
            if (merged.size >= OnlineRolePolicy.MAX_ROLES) break
            merged.add(n)
        }
        ConfigStore.setRoleCandidatesJson(JSONArray(merged.toList()).toString())
    }

    /**
     * 提交一次朗读的可见文本（滚动窗口）。
     * 限频 [OnlineRolePolicy.BUFFER_FLUSH_INTERVAL_MS] 且**在后台线程**执行：
     * 合成线程只调本方法入队，不做 mmap 写。
     */
    fun offerExcerpt(requestText: String, candidates: Collection<String>) {
        val text = TextClean.normalize(requestText).trim()
        if (text.isEmpty() && candidates.isEmpty()) return
        val now = System.currentTimeMillis()
        val last = lastFlushAt.get()
        if (now - last < OnlineRolePolicy.BUFFER_FLUSH_INTERVAL_MS && candidates.isEmpty()) return
        lastFlushAt.set(now)
        writer.execute {
            runCatching {
                if (candidates.isNotEmpty()) {
                    // 入口即清洗：脏名（`吴伯提醒`）不入候选库，避免精标按脏名建档后被迁移再打回
                    val clean = candidates.mapNotNull { TextAnalyzer.cleanSpeakerHint(it) }
                    if (clean.isNotEmpty()) mergeCandidates(clean)
                }
                if (text.isNotEmpty()) {
                    val merged = (ConfigStore.roleExcerpt() + "\n" + text).trim()
                    val keep = merged.takeLast(OnlineRolePolicy.EXCERPT_CHARS)
                    ConfigStore.setRoleExcerpt(keep)
                }
            }.onFailure { Log.w(TAG, "flush failed: ${it.javaClass.simpleName}") }
        }
    }

    /** 供精标使用的输入片段（最近窗口） */
    fun excerpt(): String = runCatching { ConfigStore.roleExcerpt() }.getOrElse { "" }

    // ---------------- 进程守卫 ----------------

    /**
     * 精标（文本 LLM）**只允许主进程**（红线 4：`:tts_service` 零 LLM/零额外推理）。
     * 进程判定复用 `EngineApp.requireEngineProcess(context)`（与其它入口同一口径，避免各处各读 /proc）。
     * @return true = 本进程允许做精标
     */
    fun mayRefineInThisProcess(context: Context): Boolean = !EngineApp.requireEngineProcess(context)
}
