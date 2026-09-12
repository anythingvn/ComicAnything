package com.comicanything.reader.data.model

import java.io.Serializable

enum class ComicSource {
    LOCAL,
    GOOGLE_DRIVE
}

enum class ComicFormat {
    PDF,
    CBZ,
    CBR,
    EPUB
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
    var totalPages: Int = 1,
    var currentPage: Int = 1,
    var progressPercentage: Float = 0f,
    var lastReadTimestamp: Long = System.currentTimeMillis(),
    var isFavorite: Boolean = false,
    val folderName: String? = null
) : Serializable
