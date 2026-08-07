package com.comicanything.reader.data.model

enum class ComicSource {
    LOCAL,
    GOOGLE_DRIVE
}

enum class ComicFormat {
    PDF,
    CBZ,
    CBR,
    EPUB,
    MOBI
}

enum class ReadingMode {
    LTR,       // Left to Right (Western Comics)
    RTL,       // Right to Left (Japanese Manga)
    WEBTOON,   // Continuous Vertical Scroll
    DUAL_SPREAD
}

enum class ColorFilterMode {
    ORIGINAL,
    SEPIA,
    NIGHT,
    AMOLED_BLACK,
    HIGH_CONTRAST
}

data class ComicItem(
    val id: String,
    val title: String,
    val pathOrUrl: String,
    val source: ComicSource,
    val format: ComicFormat,
    val coverUrl: String? = null,
    val totalPages: Int = 1,
    var currentPage: Int = 1,
    var progressPercentage: Float = 0f,
    val lastReadTimestamp: Long = System.currentTimeMillis(),
    var isFavorite: Boolean = false,
    val folderName: String? = null
)
