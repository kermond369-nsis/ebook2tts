package com.kermond.ebook2tts.core

import java.io.File

/**
 * 归档解压产物的「模型根」解析（IM-202/204）。
 *
 * 现实约束：sherpa-onnx 官方归档**顶层带一个目录**（如 `kokoro-int8-multi-lang-v1_1/`），
 * 而 §6.1 约定的落盘布局是**扁平**的（`files/models/<id>/model.onnx`）。
 * 因此落盘前必须把真正的模型根（直接包含 `modelName` 的那层）解析出来，否则引擎按扁平路径找不到模型。
 *
 * 纯逻辑，便于单测。
 */
object ModelLayout {

    /**
     * 解析直接包含 [modelName] 的目录：
     * 1. [dir] 自身即为模型根（扁平归档）；
     * 2. 否则取**唯一**的子目录中直接包含 [modelName] 的那个（顶层单目录归档）；
     * 3. 都不满足返回 null（视为坏包，调用方必须拒绝落盘）。
     */
    fun resolveRoot(dir: File, modelName: String): File? {
        if (modelName.isBlank() || !dir.isDirectory) return null
        if (File(dir, modelName).isFile) return dir
        val subDirs = dir.listFiles()?.filter { it.isDirectory } ?: return null
        return subDirs.singleOrNull()?.takeIf { File(it, modelName).isFile }
    }

    /** 落盘前清理：[root] 之外的空壳（顶层目录归档被上提后遗留）。 */
    fun cleanupScaffold(stagingDir: File, root: File) {
        if (stagingDir == root) return
        if (root.parentFile == stagingDir && stagingDir.isDirectory) {
            stagingDir.deleteRecursively()
        }
    }
}
