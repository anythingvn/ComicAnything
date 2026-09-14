package com.comicanything.reader.data.repository

import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import java.io.File

suspend fun resolveComicFile(
    comic: ComicItem,
    driveStore: DriveDownloadStore,
    downloadDriveFile: suspend (fileId: String, destination: File, accessToken: String) -> Unit,
    accessToken: () -> String?
): File {
    if (comic.source == ComicSource.LOCAL) {
        return File(comic.pathOrUrl)
    }
    driveStore.cachedFile(comic.folderName, comic.title)?.let { return it }
    val token = accessToken() ?: throw DriveApiException("Not connected to Google Drive")
    return driveStore.download(comic.folderName, comic.title) { destination -> downloadDriveFile(comic.id, destination, token) }
}
