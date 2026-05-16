package mihon.core.superresolution

enum class Quality(val key: String) {
    FAST("fast"),
    BALANCED("balanced"),
    HIGH("high");

    companion object {
        fun fromKey(key: String): Quality =
            entries.firstOrNull { it.key == key } ?: BALANCED
    }
}
