package com.kermond.ebook2tts.core

/** 进程名判定（AR-§5.1.1） */
object ProcessNames {
    const val ENGINE_SUFFIX = ":tts_service"

    fun isEngineProcess(processName: String?): Boolean {
        if (processName.isNullOrEmpty()) return false
        return processName.endsWith(ENGINE_SUFFIX)
    }

    fun parseCmdline(cmdlineBytes: ByteArray?): String {
        if (cmdlineBytes == null) return ""
        var end = cmdlineBytes.size
        for (i in cmdlineBytes.indices) {
            if (cmdlineBytes[i].toInt() == 0) {
                end = i
                break
            }
        }
        if (end <= 0) return ""
        return String(cmdlineBytes, 0, end, Charsets.UTF_8)
    }
}

/** 路径穿越防御（AR-§6.5 Zip Slip） */
object PathSafety {
    fun isSafeChild(parentCanonical: String, childCanonical: String): Boolean {
        val parent = parentCanonical.trimEnd('/', '\\')
        val child = childCanonical
        return child == parent ||
            child.startsWith(parent + "/") ||
            child.startsWith(parent + "\\")
    }

    fun hasUnsafeZipEntryName(name: String): Boolean {
        if (name.isEmpty()) return true
        if (name.startsWith("/") || name.startsWith("\\")) return true
        if (name.contains("..")) return true
        if (name.contains("\u0000")) return true
        if (name.length >= 2 && name[1] == ':') return true
        return false
    }
}
