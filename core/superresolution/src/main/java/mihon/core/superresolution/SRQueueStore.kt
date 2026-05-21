package mihon.core.superresolution

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SRQueueItem(
    val chapterId: Long,
    val mangaId: Long,
    val mangaTitle: String,
    val chapterName: String,
    val sourceKey: Long,
    val sourceName: String = "",
    val totalPages: Int,
    val processedPages: Int = 0,
)

class SRQueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("sr_queue", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<SRQueueItem> {
        return prefs.all.mapNotNull { (_, v) ->
            try {
                json.decodeFromString<SRQueueItem>(v as String)
            } catch (_: Exception) { null }
        }
    }

    fun save(items: List<SRQueueItem>) {
        val editor = prefs.edit()
        editor.clear()
        items.forEach { item ->
            editor.putString(item.chapterId.toString(), json.encodeToString(item))
        }
        editor.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
