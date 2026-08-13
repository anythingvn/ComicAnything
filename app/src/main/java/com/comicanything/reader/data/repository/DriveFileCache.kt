package com.comicanything.reader.data.repository

import java.io.File

class DriveFileCache(
    private val cacheDir: File,
    private val maxTotalBytes: Long = DEFAULT_MAX_BYTES
) {

    fun cachedFile(comicId: String): File? {
        val file = File(cacheDir, comicId)
        if (!file.exists()) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    suspend fun download(comicId: String, download: suspend (File) -> Unit): File {
        cacheDir.mkdirs()
        val partFile = File(cacheDir, "$comicId.part")
        val finalFile = File(cacheDir, comicId)
        try {
            download(partFile)
            if (!partFile.renameTo(finalFile)) {
                throw IllegalStateException("Couldn't finalize the downloaded file")
            }
        } catch (e: Exception) {
            partFile.delete()
            throw e
        }
        evictIfNeeded()
        return finalFile
    }

    private fun evictIfNeeded() {
        val files = cacheDir.listFiles { f -> !f.name.endsWith(".part") } ?: return
        var totalSize = files.sumOf { it.length() }
        if (totalSize <= maxTotalBytes) return
        val oldestFirst = files.sortedBy { it.lastModified() }
        for (file in oldestFirst) {
            if (totalSize <= maxTotalBytes) break
            totalSize -= file.length()
            file.delete()
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 750L * 1024 * 1024
    }
}
