package mihon.core.superresolution

enum class DenoiseLevel(val key: String) {
    OFF("off"),
    LIGHT("light"),
    STRONG("strong");

    companion object {
        fun fromKey(key: String): DenoiseLevel =
            entries.firstOrNull { it.key == key } ?: LIGHT
    }
}
