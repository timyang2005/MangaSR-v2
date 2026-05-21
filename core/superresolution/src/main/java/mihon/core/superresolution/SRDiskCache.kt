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
        file.parentFile?.mkdirs()
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
        val files = cacheDir.listFiles()?.filter { !it.name.startsWith("_ch_") && !it.name.startsWith("batch") }
        val count = files?.size ?: 0
        val bytes = files?.sumOf { it.length() } ?: 0L
        val batchDir = File(cacheDir, "batch")
        if (batchDir.exists()) {
            var batchCount = 0
            var batchBytes = 0L
            batchDir.walkTopDown().forEach { file ->
                if (file.isFile && !file.name.startsWith("_ch_")) {
                    batchCount++
                    batchBytes += file.length()
                }
            }
            return (count + batchCount) to (bytes + batchBytes)
        }
        return count to bytes
    }

    fun clear() {
        var clearedCount = 0
        var clearedBytes = 0L
        cacheDir.listFiles()?.forEach { file ->
            if (!file.name.startsWith("_ch_") && file.name != "batch") {
                clearedCount++
                clearedBytes += file.length()
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            }
        }
        logcat(LogPriority.INFO) {
            "SR: Cleared transient cache ($clearedCount files, ${clearedBytes / 1024}KB, batch and metadata kept)"
        }
    }

    internal fun getFile(key: String): File {
        val safeKey = key.replace("[^a-zA-Z0-9_/.-]".toRegex(), "_")
        if (safeKey.contains('/')) {
            return File(cacheDir, safeKey)
        }
        val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "jpg"
        return File(cacheDir, "$safeKey.$ext")
    }

    // ── Batch SR structured storage ──

    private val batchDir: File get() = File(cacheDir, "batch").also { it.mkdirs() }

    private fun batchChapterDir(sourceName: String, mangaTitle: String, chapterId: Long): File {
        val safeSource = DiskUtil.buildValidFilename(sourceName)
        val safeManga = DiskUtil.buildValidFilename(mangaTitle)
        return File(batchDir, "$safeSource/$safeManga/$chapterId").also { it.mkdirs() }
    }

    fun putBatchPage(sourceName: String, mangaTitle: String, chapterId: Long, pageIndex: Int, bitmap: Bitmap) {
        val dir = batchChapterDir(sourceName, mangaTitle, chapterId)
        val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "jpg"
        val file = File(dir, "${pageIndex.toString().padStart(3, '0')}.$ext")
        try {
            FileOutputStream(file).use { out ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "SR: Failed to write batch page $pageIndex ch$chapterId\n${e.asLog()}" }
        }
        evictIfNeeded()
    }

    fun getBatchPage(sourceName: String, mangaTitle: String, chapterId: Long, pageIndex: Int): Bitmap? {
        val dir = batchChapterDir(sourceName, mangaTitle, chapterId)
        return readBatchPageFile(dir, pageIndex)
    }

    fun getBatchPageAnySource(chapterId: Long, pageIndex: Int): Bitmap? {
        if (!batchDir.exists()) return null
        batchDir.walkTopDown().filter { it.isDirectory && it.name == chapterId.toString() }.forEach { dir ->
            val bitmap = readBatchPageFile(dir, pageIndex)
            if (bitmap != null) return bitmap
        }
        return null
    }

    private fun readBatchPageFile(dir: File, pageIndex: Int): Bitmap? {
        val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "jpg"
        val file = File(dir, "${pageIndex.toString().padStart(3, '0')}.$ext")
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "SR: Failed to read batch page $pageIndex from $dir\n${e.asLog()}" }
            null
        }
    }

    fun putBatchMetadata(
        sourceName: String,
        mangaTitle: String,
        chapterId: Long,
        meta: ChapterMetadata,
    ) {
        val dir = batchChapterDir(sourceName, mangaTitle, chapterId)
        val json = org.json.JSONObject().apply {
            put("mangaId", meta.mangaId)
            put("mangaTitle", meta.mangaTitle)
            put("chapterName", meta.chapterName)
            put("pageCount", meta.pageCount)
            put("sourceName", sourceName)
        }.toString()
        try {
            File(dir, "metadata.json").writeText(json)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "SR: Failed to write batch metadata $chapterId\n${e.asLog()}" }
        }
    }

    fun getCompletedBatchChapters(): List<Triple<Long, ChapterMetadata, Pair<String, String>>> {
        val result = mutableListOf<Triple<Long, ChapterMetadata, Pair<String, String>>>()
        if (!batchDir.exists()) return result
        batchDir.walkTopDown().filter { it.isFile && it.name == "metadata.json" }.forEach { file ->
            try {
                val json = org.json.JSONObject(file.readText())
                val chapterId = file.parentFile!!.name.toLongOrNull() ?: return@forEach
                val sourceName = json.optString("sourceName", "")
                result.add(
                    Triple(
                        chapterId,
                        ChapterMetadata(
                            mangaId = json.getLong("mangaId"),
                            mangaTitle = json.getString("mangaTitle"),
                            chapterName = json.getString("chapterName"),
                            pageCount = json.getInt("pageCount"),
                        ),
                        sourceName to chapterDirOfMetadata(file.parentFile!!),
                    ),
                )
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "SR: Failed to parse batch metadata ${file.path}\n${e.asLog()}" }
            }
        }
        return result
    }

    private fun chapterDirOfMetadata(chapterDir: File): String {
        return chapterDir.parentFile?.name ?: ""
    }

    fun removeBatchChapter(sourceName: String, mangaTitle: String, chapterId: Long) {
        val dir = batchChapterDir(sourceName, mangaTitle, chapterId)
        if (dir.exists()) dir.deleteRecursively()
        val mangaDir = dir.parentFile
        if (mangaDir != null && mangaDir.exists() && mangaDir.list()?.isEmpty() == true) {
            mangaDir.delete()
            val sourceDir = mangaDir.parentFile
            if (sourceDir != null && sourceDir.exists() && sourceDir.list()?.isEmpty() == true) {
                sourceDir.delete()
            }
        }
    }

    private fun evictIfNeeded() {
        evictScope.launch {
            val files = cacheDir.listFiles()?.filter {
                !it.name.startsWith("_ch_") && it.name != "batch"
            }?.toMutableList() ?: return@launch
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

private object DiskUtil {
    fun buildValidFilename(name: String): String {
        return name.trim()
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(120)
    }
}
