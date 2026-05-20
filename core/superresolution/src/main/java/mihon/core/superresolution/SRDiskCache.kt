package mihon.core.superresolution

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.io.File
import java.io.FileOutputStream

class SRDiskCache(
    private val cacheDir: File,
) {
    private val maxCacheSizeBytes = 100L * 1024 * 1024
    private val evictScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "SR: Failed to write disk cache for $key\n${e.asLog()}" }
        }
        evictIfNeeded()
    }

    fun remove(key: String) {
        getFile(key).delete()
    }

    fun contains(key: String): Boolean = getFile(key).exists()

    fun putChapterMetadata(chapterId: Long, meta: ChapterMetadata) {
        val json = org.json.JSONObject().apply {
            put("mangaId", meta.mangaId)
            put("mangaTitle", meta.mangaTitle)
            put("chapterName", meta.chapterName)
            put("pageCount", meta.pageCount)
        }.toString()
        val file = File(cacheDir, "_ch_${chapterId}.json")
        try {
            file.writeText(json)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "SR: Failed to write chapter metadata for $chapterId\n${e.asLog()}" }
        }
    }

    fun getCompletedChapters(): List<Pair<Long, ChapterMetadata>> {
        return cacheDir.listFiles()
            ?.filter { it.name.startsWith("_ch_") && it.extension == "json" }
            ?.mapNotNull { file ->
                val chapterId = file.nameWithoutExtension.removePrefix("_ch_").toLongOrNull()
                if (chapterId == null) {
                    logcat(LogPriority.WARN) { "SR: Invalid metadata filename ${file.name}" }
                    null
                } else {
                    try {
                        val json = org.json.JSONObject(file.readText())
                        chapterId to ChapterMetadata(
                            mangaId = json.getLong("mangaId"),
                            mangaTitle = json.getString("mangaTitle"),
                            chapterName = json.getString("chapterName"),
                            pageCount = json.getInt("pageCount"),
                        )
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR) { "SR: Failed to parse metadata ${file.name}\n${e.asLog()}" }
                        null
                    }
                }
            } ?: emptyList()
    }

    fun removeChapter(chapterId: Long) {
        cacheDir.listFiles { _, name ->
            name.startsWith("page_${chapterId}_") || name == "_ch_${chapterId}.json"
        }?.forEach { it.delete() }
    }

    fun getUsage(): Pair<Int, Long> {
        val files = cacheDir.listFiles()
        return (files?.size ?: 0) to (files?.sumOf { it.length() } ?: 0L)
    }

    fun clear() {
        val (count, bytes) = getUsage()
        cacheDir.listFiles()?.forEach { it.delete() }
        logcat(LogPriority.INFO) { "SR: Cleared disk cache ($count files, ${bytes / 1024}KB)" }
    }

    internal fun getFile(key: String): File {
        val safeKey = key.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "jpg"
        return File(cacheDir, "$safeKey.$ext")
    }

    private fun evictIfNeeded() {
        evictScope.launch {
            val files = cacheDir.listFiles()?.filter { !it.name.startsWith("_ch_") }?.toMutableList() ?: return@launch
            var totalSize = files.sumOf { it.length() }
            if (totalSize <= maxCacheSizeBytes) return@launch
            files.sortByDescending { it.lastModified() }
            var index = files.size - 1
            while (totalSize > maxCacheSizeBytes && index >= 0) {
                totalSize -= files[index].length()
                files[index].delete()
                index--
            }
            logcat(LogPriority.DEBUG) { "SR: Evicted ${files.size - 1 - index} cache files" }
        }
    }
}
