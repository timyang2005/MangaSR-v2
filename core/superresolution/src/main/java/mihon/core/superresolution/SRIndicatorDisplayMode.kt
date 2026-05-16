package mihon.core.superresolution

enum class SRIndicatorDisplayMode(val key: String) {
    HIDDEN("hidden"),
    ICON_ONLY("icon_only"),
    ICON_AND_TEXT("icon_and_text");

    companion object {
        fun fromKey(key: String): SRIndicatorDisplayMode =
            entries.firstOrNull { it.key == key } ?: ICON_AND_TEXT
    }
}
