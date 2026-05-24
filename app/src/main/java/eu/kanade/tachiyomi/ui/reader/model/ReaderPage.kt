package eu.kanade.tachiyomi.ui.reader.model

import android.graphics.Bitmap
import eu.kanade.tachiyomi.source.model.Page
import mihon.core.superresolution.SRModel
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    @Volatile
    var srBitmap: Bitmap? = null

    @Volatile
    var srModel: SRModel? = null
}
