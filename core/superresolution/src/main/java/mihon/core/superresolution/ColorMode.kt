package mihon.core.superresolution

enum class ColorMode(val key: String) {
    AUTO("auto"),
    COLOR("color"),
    GRAYSCALE("grayscale");

    companion object {
        fun fromKey(key: String): ColorMode =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}
