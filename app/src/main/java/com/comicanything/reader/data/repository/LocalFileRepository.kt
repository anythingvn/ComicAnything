package com.comicanything.reader.data.repository

import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalFileRepository(
    private val rootPath: String = DEFAULT_ROOT
) {

    companion object {
        const val DEFAULT_ROOT = "/storage/emulated/0"
        private const val MAX_SCAN_DEPTH = 8
    }

    suspend fun scanStorageDirectories(): List<ComicItem> = withContext(Dispatchers.IO) {
        val rootDir = File(rootPath)
        if (!rootDir.exists() || !rootDir.isDirectory) return@withContext emptyList()

        rootDir.walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .filter { it.isFile }
            .mapNotNull { file -> formatFor(file.name)?.let { format -> file to format } }
            .distinctBy { (file, _) -> file.absolutePath }
            .map { (file, format) ->
                ComicItem(
                    id = file.absolutePath.hashCode().toString(),
                    title = file.name,
                    pathOrUrl = file.absolutePath,
                    source = ComicSource.LOCAL,
                    format = format,
                    totalPages = 1
                )
            }
            .toList()
    }

    private fun formatFor(name: String): ComicFormat? = when {
        name.endsWith(".pdf", ignoreCase = true) -> ComicFormat.PDF
        name.endsWith(".cbz", ignoreCase = true) -> ComicFormat.CBZ
        name.endsWith(".cbr", ignoreCase = true) -> ComicFormat.CBR
        name.endsWith(".epub", ignoreCase = true) -> ComicFormat.EPUB
        name.endsWith(".mobi", ignoreCase = true) -> ComicFormat.MOBI
        else -> null
    }
}
