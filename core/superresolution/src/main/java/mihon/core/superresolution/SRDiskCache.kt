package mihon.core.superresolution

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.io.File
import java.io.FileOutputStream

class SRDiskCache(
    private val cacheDir: File,
) {
    private val maxCacheSizeBytes = 100L * 1024 * 1024

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
    }

    fun get(key: String): Bitmap? {
        val file = getFile(key)
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "SR: Failed to read disk cache for $key\n${e.asLog()}" }
            null
        }
    }

    fun put(key: String, bitmap: Bitmap) {
        val file = getFile(key)
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "SR: Failed to write disk cache for $key\n${e.asLog()}" }
        }
        evictIfNeeded()
    }

    fun remove(key: String) {
        getFile(key).delete()
    }

    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
        logcat(LogPriority.INFO) { "SR: Cleared disk cache" }
    }

    private fun getFile(key: String): File {
        val safeKey = key.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        return File(cacheDir, "$safeKey.png")
    }

    private fun evictIfNeeded() {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var totalSize = files.sumOf { it.length() }
        var index = 0
        while (totalSize > maxCacheSizeBytes && index < files.size) {
            totalSize -= files[index].length()
            files[index].delete()
            index++
        }
    }
}
