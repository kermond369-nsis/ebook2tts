package com.mimo.ebook2tts.engine

import android.util.Log
import com.mimo.ebook2tts.core.PathSafety
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 安全解压（AR-§6.5 / IM-203）：Zip Slip、体积/条目上限、隔离区。
 */
object SafeExtractor {

    private const val TAG = "SafeExtractor"
    private const val MAX_ENTRIES = 20_000
    private const val VOLUME_TOLERANCE = 1.10

    class SecurityError(message: String) : IOException(message)

    fun extract(
        input: InputStream,
        stagingDir: File,
        archiveName: String,
        maxExtractedBytes: Long,
        whitelist: Set<String>? = null,
    ) {
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) {
            throw IOException("cannot create staging: ${stagingDir.absolutePath}")
        }
        val limit = (maxExtractedBytes * VOLUME_TOLERANCE).toLong().coerceAtLeast(1L)
        try {
            if (archiveName.endsWith(".zip")) {
                extractZip(input, stagingDir, limit)
            } else {
                extractTarBz2(input, stagingDir, limit)
            }
        } catch (t: Throwable) {
            stagingDir.deleteRecursively()
            throw t
        }
        if (whitelist != null) {
            val actual = collectRelativeFiles(stagingDir)
            if (!actual.containsAll(whitelist)) {
                stagingDir.deleteRecursively()
                throw SecurityError("archive missing declared files")
            }
        }
        Log.i(TAG, "extracted to ${stagingDir.absolutePath}")
    }

    private fun extractTarBz2(input: InputStream, stagingDir: File, limit: Long) {
        var written = 0L
        var entries = 0
        BZip2CompressorInputStream(BufferedInputStream(input)).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    entries++
                    if (entries > MAX_ENTRIES) throw SecurityError("too many entries")
                    val name = entry.name
                    if (PathSafety.hasUnsafeZipEntryName(name)) {
                        throw SecurityError("unsafe entry: $name")
                    }
                    if (entry.isSymbolicLink || entry.isLink) {
                        throw SecurityError("link entry rejected: $name")
                    }
                    val outFile = canonicalChild(stagingDir, name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        written += writeStream(tar, outFile, limit - written)
                        if (written > limit) throw SecurityError("extract size exceeded")
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }

    private fun extractZip(input: InputStream, stagingDir: File, limit: Long) {
        var written = 0L
        var entries = 0
        ZipInputStream(BufferedInputStream(input)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries++
                if (entries > MAX_ENTRIES) throw SecurityError("too many entries")
                val name = entry.name
                if (PathSafety.hasUnsafeZipEntryName(name)) {
                    throw SecurityError("unsafe entry: $name")
                }
                val outFile = canonicalChild(stagingDir, name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    written += writeStream(zis, outFile, limit - written)
                    if (written > limit) throw SecurityError("extract size exceeded")
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun canonicalChild(parent: File, name: String): File {
        val child = File(parent, name)
        val parentCanon = parent.canonicalPath
        val childCanon = child.canonicalPath
        if (!PathSafety.isSafeChild(parentCanon, childCanon)) {
            throw SecurityError("path traversal: $name")
        }
        return child
    }

    private fun writeStream(input: InputStream, out: File, remaining: Long): Long {
        var read = 0L
        out.outputStream().use { os ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                read += n
                if (read > remaining) throw SecurityError("entry exceeds remaining budget")
                os.write(buf, 0, n)
            }
        }
        return read
    }

    private fun collectRelativeFiles(root: File): Set<String> {
        val out = mutableSetOf<String>()
        root.walkTopDown().filter { it.isFile }.forEach {
            out += it.relativeTo(root).path.replace('\\', '/')
        }
        return out
    }

    fun promote(stagingDir: File, finalDir: File, markerContent: String = "ok") {
        if (finalDir.exists()) finalDir.deleteRecursively()
        finalDir.parentFile?.mkdirs()
        if (!stagingDir.renameTo(finalDir)) {
            stagingDir.copyRecursively(finalDir, overwrite = true)
            stagingDir.deleteRecursively()
        }
        File(finalDir, ".completed").writeText(markerContent)
        Log.i(TAG, "promoted ${finalDir.absolutePath}")
    }
}
