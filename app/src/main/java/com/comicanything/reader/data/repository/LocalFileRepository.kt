package com.comicanything.reader.data.repository

import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface LocalEntry {
    data class Folder(val path: String, val name: String) : LocalEntry
    data class ComicFile(val comic: ComicItem) : LocalEntry
}

class LocalFileRepository(
    private val rootPath: String = DEFAULT_ROOT,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        const val DEFAULT_ROOT = "/storage/emulated/0"
        private const val MAX_SCAN_DEPTH = 8
    }

    suspend fun scanStorageDirectories(): List<ComicItem> = withContext(ioDispatcher) {
        val rootDir = File(rootPath)
        if (!rootDir.exists() || !rootDir.isDirectory) return@withContext emptyList()

        rootDir.walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .filter { it.isFile }
            .mapNotNull { file -> formatFor(file.name)?.let { format -> file to format } }
            .distinctBy { (file, _) -> file.absolutePath }
            .map { (file, format) -> comicItemFor(file, format) }
            .toList()
    }

    /**
     * Lists the immediate (non-recursive) contents of [path]: subfolders first, then comic files
     * directly in this directory, both sorted case-insensitively by name. Hidden directories
     * (dotfiles) are skipped, matching standard file-manager behavior. Unlike
     * [scanStorageDirectories], this never descends into subfolders -- the caller drives descent
     * by calling this again with a subfolder's path, so browsing a huge storage tree stays fast
     * regardless of how deep files are nested.
     */
    suspend fun listDirectory(path: String): List<LocalEntry> = withContext(ioDispatcher) {
        val dir = File(path)
        val children = dir.listFiles() ?: return@withContext emptyList()

        val folders = children
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
            .map { LocalEntry.Folder(it.absolutePath, it.name) }

        val files = children
            .filter { it.isFile }
            .mapNotNull { file -> formatFor(file.name)?.let { format -> comicItemFor(file, format) } }
            .sortedBy { it.title.lowercase() }
            .map { LocalEntry.ComicFile(it) }

        folders + files
    }

    /**
     * Recursively searches [rootPath] and every folder beneath it (up to [MAX_SCAN_DEPTH]) for
     * folders and comic files whose name contains [query]. A folder is matched by its own name
     * regardless of whether anything inside it matches -- callers still descend into it via
     * [listDirectory] the normal way, so a matched folder is a valid result on its own, not just
     * a container. The search root itself is never included, matching the intuition that you're
     * already "in" it.
     */
    suspend fun searchTree(rootPath: String, query: String): List<LocalEntry> = withContext(ioDispatcher) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return@withContext emptyList()
        val rootDir = File(rootPath)
        if (!rootDir.exists() || !rootDir.isDirectory) return@withContext emptyList()

        rootDir.walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .filter { it != rootDir }
            .mapNotNull { file ->
                when {
                    file.isDirectory && file.name.contains(trimmedQuery, ignoreCase = true) ->
                        LocalEntry.Folder(file.absolutePath, file.name)
                    file.isFile -> formatFor(file.name)?.let { format ->
                        comicItemFor(file, format)
                            .takeIf { it.title.contains(trimmedQuery, ignoreCase = true) }
                            ?.let { LocalEntry.ComicFile(it) }
                    }
                    else -> null
                }
            }
            .toList()
    }

    private fun comicItemFor(file: File, format: ComicFormat) = ComicItem(
        id = file.absolutePath.hashCode().toString(),
        title = file.name,
        pathOrUrl = file.absolutePath,
        source = ComicSource.LOCAL,
        format = format,
        totalPages = 1
    )

    private fun formatFor(name: String): ComicFormat? = when {
        name.endsWith(".pdf", ignoreCase = true) -> ComicFormat.PDF
        name.endsWith(".cbz", ignoreCase = true) -> ComicFormat.CBZ
        name.endsWith(".cbr", ignoreCase = true) -> ComicFormat.CBR
        name.endsWith(".epub", ignoreCase = true) -> ComicFormat.EPUB
        else -> null
    }
}
