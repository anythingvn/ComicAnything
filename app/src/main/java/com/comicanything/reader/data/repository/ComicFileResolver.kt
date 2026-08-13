package com.comicanything.reader.data.repository

import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import java.io.File

suspend fun resolveComicFile(
    comic: ComicItem,
    driveCache: DriveFileCache,
    downloadDriveFile: suspend (fileId: String, destination: File, accessToken: String) -> Unit,
    accessToken: () -> String?
): File {
    if (comic.source == ComicSource.LOCAL) {
        return File(comic.pathOrUrl)
    }
    driveCache.cachedFile(comic.id)?.let { return it }
    val token = accessToken() ?: throw DriveApiException("Not connected to Google Drive")
    return driveCache.download(comic.id) { destination -> downloadDriveFile(comic.id, destination, token) }
}
