package com.comicanything.reader.data.repository

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        // The temp filename includes a random UUID per attempt (not just "$comicId.part") so that
        // two concurrent downloads of the same comicId -- e.g. a user backs out of a large
        // in-progress Drive download and re-taps the same comic -- never write to the same path.
        // `body.byteStream().copyTo(out)` is a blocking, non-suspending call that ordinary
        // coroutine cancellation cannot interrupt mid-write, so two writers sharing one fixed path
        // could otherwise interleave and produce a permanently corrupted cache entry (this cache
        // has no invalidation -- a cache hit is trusted forever). With unique temp paths, each
        // attempt writes a complete, non-interleaved file; whichever finishes first wins the
        // atomic rename, and the other either overwrites it with its own equally-valid copy or
        // fails harmlessly.
        val partFile = File(cacheDir, "$comicId.${java.util.UUID.randomUUID()}.part")
        val finalFile = File(cacheDir, comicId)
        try {
            download(partFile)
            // java.io.File.renameTo is explicitly documented as platform-dependent and "might not
            // succeed if a file with the destination abstract pathname already exists" -- which is
            // exactly the case whenever this comicId already has a cached entry (a re-download, or
            // two concurrent attempts racing for the same final name, as this method now allows).
            // Files.move(..., REPLACE_EXISTING) gives a well-defined cross-platform overwrite
            // instead of relying on unspecified per-OS rename-over-existing-file behavior.
            Files.move(partFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            partFile.delete()
            throw e
        }
        evictIfNeeded(excluding = finalFile)
        return finalFile
    }

    /** Deletes every cached file (and any leftover ".part" temp file) from disk. */
    fun clearAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun evictIfNeeded(excluding: File) {
        val files = cacheDir.listFiles { f -> !f.name.endsWith(".part") } ?: return
        var totalSize = files.sumOf { it.length() }
        if (totalSize <= maxTotalBytes) return
        val oldestFirst = files.filter { it != excluding }.sortedBy { it.lastModified() }
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
