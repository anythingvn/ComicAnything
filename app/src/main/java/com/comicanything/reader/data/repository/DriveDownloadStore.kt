package com.comicanything.reader.data.repository

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where downloaded Drive comics live on disk: a folder inside the device's public Downloads
 * directory (passed in as [downloadsRoot], e.g. .../Download/ComicAnything), organized by the
 * Drive folder each comic came from -- downloadsRoot/<folder>/<title> -- so same-named files from
 * different Drive folders never collide, and the user can browse their downloads with a normal
 * file manager exactly the way they're organized in Drive.
 *
 * Unlike the old app-private cache this replaces, these are the user's own deliberate, permanent
 * downloads sitting in a folder they browse directly -- so there's no size cap or automatic
 * eviction here. Only an explicit [delete] or [clearAll] removes anything.
 */
class DriveDownloadStore(private val downloadsRoot: File) {

    fun destinationFor(folderName: String?, title: String): File =
        File(File(downloadsRoot, sanitize(folderName)), sanitize(title))

    fun cachedFile(folderName: String?, title: String): File? {
        val file = destinationFor(folderName, title)
        return if (file.exists()) file else null
    }

    suspend fun download(folderName: String?, title: String, download: suspend (File) -> Unit): File {
        val finalFile = destinationFor(folderName, title)
        val parent = finalFile.parentFile!!
        parent.mkdirs()
        // The temp filename includes a random UUID per attempt (not just "$title.part") so that
        // two concurrent downloads of the same title -- e.g. a user backs out of a large
        // in-progress Drive download and re-taps the same comic -- never write to the same path.
        // See DriveFileCache's original version of this comment for why that matters: a shared
        // fixed temp path could let two writers interleave into a permanently corrupted file.
        val partFile = File(parent, "${finalFile.name}.${java.util.UUID.randomUUID()}.part")
        try {
            download(partFile)
            Files.move(partFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            partFile.delete()
            throw e
        }
        return finalFile
    }

    fun delete(folderName: String?, title: String) {
        destinationFor(folderName, title).delete()
    }

    /** One file actually sitting on disk under this store's root, as found by [listAll]. */
    data class DownloadedFile(val folderName: String, val title: String, val file: File)

    /**
     * Lists every downloaded file actually present on disk, grouped by the subfolder (Drive
     * folder name) it lives under -- the ground truth for a "manage all downloads" view, since it
     * reflects files sitting here from ANY source (including a "download all files in this
     * folder" batch whose comics were never individually opened, so they'd never show up in
     * DriveLibraryRepository -- see its own doc comment). Skips ".part" files, which are still
     * mid-download and not yet real entries.
     */
    fun listAll(): List<DownloadedFile> {
        val folderDirs = downloadsRoot.listFiles { f -> f.isDirectory } ?: return emptyList()
        return folderDirs.flatMap { folderDir ->
            val files = folderDir.listFiles { f -> f.isFile && !f.name.endsWith(".part") } ?: emptyArray()
            files.map { file -> DownloadedFile(folderName = folderDir.name, title = file.name, file = file) }
        }
    }

    /** Deletes every downloaded comic (and any leftover ".part" temp file) under this store's root. */
    fun clearAll() {
        downloadsRoot.deleteRecursively()
    }

    companion object {
        private val INVALID_CHARS = Regex("[/\\\\:*?\"<>|]")

        /** Strips characters that aren't safe as a single path segment on common filesystems. */
        private fun sanitize(segment: String?): String {
            val cleaned = segment?.replace(INVALID_CHARS, "_")?.trim().orEmpty()
            return cleaned.ifEmpty { "Unsorted" }
        }
    }
}
