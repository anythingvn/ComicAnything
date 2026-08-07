package com.comicanything.reader.data.repository

import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalFileRepository {

    suspend fun scanStorageDirectories(directoryPath: String?): List<ComicItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<ComicItem>()
        val rootDir = if (directoryPath != null) File(directoryPath) else File("/storage/emulated/0/Download")

        if (rootDir.exists() && rootDir.isDirectory) {
            rootDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val name = file.name
                    val format = when {
                        name.endsWith(".pdf", ignoreCase = true) -> ComicFormat.PDF
                        name.endsWith(".cbz", ignoreCase = true) -> ComicFormat.CBZ
                        name.endsWith(".cbr", ignoreCase = true) -> ComicFormat.CBR
                        name.endsWith(".epub", ignoreCase = true) -> ComicFormat.EPUB
                        name.endsWith(".mobi", ignoreCase = true) -> ComicFormat.MOBI
                        else -> null
                    }

                    if (format != null) {
                        items.add(
                            ComicItem(
                                id = file.absolutePath.hashCode().toString(),
                                title = name,
                                pathOrUrl = file.absolutePath,
                                source = ComicSource.LOCAL,
                                format = format,
                                totalPages = 1
                            )
                        )
                    }
                }
            }
        }

        // Add sample demo items for preview
        if (items.isEmpty()) {
            items.add(
                ComicItem(
                    id = "local_demo_1",
                    title = "Batman: Year One (Issue #1)",
                    pathOrUrl = "/storage/emulated/0/Download/Batman_Year_One.pdf",
                    source = ComicSource.LOCAL,
                    format = ComicFormat.PDF,
                    currentPage = 14,
                    totalPages = 48,
                    progressPercentage = 0.29f,
                    isFavorite = true
                )
            )
            items.add(
                ComicItem(
                    id = "local_demo_2",
                    title = "Solo Leveling Vol 1",
                    pathOrUrl = "/storage/emulated/0/Comics/Solo_Leveling_v1.cbz",
                    source = ComicSource.LOCAL,
                    format = ComicFormat.CBZ,
                    currentPage = 1,
                    totalPages = 120,
                    progressPercentage = 0.01f
                )
            )
        }

        items
    }
}
