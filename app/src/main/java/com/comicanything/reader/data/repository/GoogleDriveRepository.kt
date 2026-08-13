package com.comicanything.reader.data.repository

import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder

sealed interface DriveEntry {
    data class Folder(val id: String, val name: String) : DriveEntry
    data class ComicFile(val comic: ComicItem) : DriveEntry
}

class DriveApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

class GoogleDriveRepository {

    private val client = OkHttpClient()

    fun extractFolderId(input: String): String {
        return if (input.contains("/folders/")) {
            val parts = input.split("/folders/")
            if (parts.size > 1) {
                parts[1].split("?")[0].split("/")[0]
            } else input.trim()
        } else {
            input.trim()
        }
    }

    internal fun buildFolderQuery(folderId: String): String {
        return "'$folderId' in parents and (" +
            "mimeType = 'application/vnd.google-apps.folder' or " +
            "mimeType = 'application/pdf' or mimeType = 'application/zip' or " +
            "name contains '.pdf' or name contains '.cbz' or " +
            "name contains '.epub' or name contains '.cbr')"
    }

    internal fun buildFolderListRequest(folderId: String, accessToken: String): Request {
        val encodedQuery = URLEncoder.encode(buildFolderQuery(folderId), "UTF-8")
        val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id,name,mimeType,thumbnailLink)"
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
    }

    internal fun parseDriveEntries(responseBody: String): List<DriveEntry> {
        val json = JSONObject(responseBody)
        val filesArray = json.optJSONArray("files") ?: return emptyList()
        val folders = mutableListOf<DriveEntry.Folder>()
        val files = mutableListOf<DriveEntry.ComicFile>()
        for (i in 0 until filesArray.length()) {
            val f = filesArray.getJSONObject(i)
            val name = f.optString("name", "Untitled")
            val id = f.optString("id")
            val mimeType = f.optString("mimeType")
            if (mimeType == "application/vnd.google-apps.folder") {
                folders.add(DriveEntry.Folder(id = id, name = name))
            } else {
                val format = when {
                    name.endsWith(".cbz", ignoreCase = true) -> ComicFormat.CBZ
                    name.endsWith(".epub", ignoreCase = true) -> ComicFormat.EPUB
                    name.endsWith(".cbr", ignoreCase = true) -> ComicFormat.CBR
                    else -> ComicFormat.PDF
                }
                val thumbnailLink = f.optString("thumbnailLink").ifEmpty { null }
                files.add(
                    DriveEntry.ComicFile(
                        ComicItem(
                            id = id,
                            title = name,
                            pathOrUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media",
                            source = ComicSource.GOOGLE_DRIVE,
                            format = format,
                            coverUrl = thumbnailLink
                        )
                    )
                )
            }
        }
        val sortedFolders = folders.sortedBy { it.name.lowercase() }
        val sortedFiles = files.sortedBy { it.comic.title.lowercase() }
        return sortedFolders + sortedFiles
    }

    suspend fun fetchFolderContents(folderId: String, accessToken: String): List<DriveEntry> = withContext(Dispatchers.IO) {
        val request = buildFolderListRequest(folderId, accessToken)
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw DriveApiException("Couldn't reach Google Drive -- check your connection", e)
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw DriveApiException("Couldn't load this folder (HTTP ${resp.code})")
            }
            val body = resp.body?.string() ?: "{}"
            parseDriveEntries(body)
        }
    }

    internal fun buildDownloadRequest(fileId: String, accessToken: String): Request {
        return Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $accessToken")
            .build()
    }

    suspend fun downloadFile(fileId: String, destination: File, accessToken: String) = withContext(Dispatchers.IO) {
        val request = buildDownloadRequest(fileId, accessToken)
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw DriveApiException("Couldn't reach Google Drive -- check your connection", e)
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw DriveApiException("Couldn't download this file (HTTP ${resp.code})")
            }
            val body = resp.body ?: throw DriveApiException("Empty response from Google Drive")
            destination.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }
}
