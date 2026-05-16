package mihon.core.superresolution

enum class SRIndicatorPosition(val key: String) {
    TOP_LEFT("top_left"),
    TOP_CENTER("top_center"),
    TOP_RIGHT("top_right"),
    BOTTOM_LEFT("bottom_left"),
    BOTTOM_CENTER("bottom_center"),
    BOTTOM_RIGHT("bottom_right");

    fun toAlignment() = when (this) {
        TOP_LEFT -> androidx.compose.ui.Alignment.TopStart
        TOP_CENTER -> androidx.compose.ui.Alignment.TopCenter
        TOP_RIGHT -> androidx.compose.ui.Alignment.TopEnd
        BOTTOM_LEFT -> androidx.compose.ui.Alignment.BottomStart
        BOTTOM_CENTER -> androidx.compose.ui.Alignment.BottomCenter
        BOTTOM_RIGHT -> androidx.compose.ui.Alignment.BottomEnd
    }

    companion object {
        fun fromKey(key: String): SRIndicatorPosition =
            entries.firstOrNull { it.key == key } ?: TOP_LEFT
    }
}
