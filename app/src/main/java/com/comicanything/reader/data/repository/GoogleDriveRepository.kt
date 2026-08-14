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

    // Deliberately just 'in parents and trashed = false' with no mimeType/name-contains clauses.
    // Drive API v3's `name contains 'X'` operator does prefix-WORD matching, not substring
    // matching (documented Drive API behavior), so a query built from `name contains '.epub'`
    // does not reliably match a real filename like "MyBook.epub" -- EPUB/CBR previously had no
    // mimeType clause to fall back on and likely never matched in production. Rather than trying
    // to enumerate every mimeType Drive might assign a .cbr/.cbz file (inconsistent -- sometimes
    // application/x-rar-compressed, sometimes application/octet-stream, etc.), format filtering
    // is now done entirely client-side in parseDriveEntriesUnsorted from the filename extension,
    // which parseDriveEntries already computed anyway. `trashed = false` additionally excludes
    // deleted-but-not-purged Drive files from the listing.
    internal fun buildFolderQuery(folderId: String): String {
        return "'$folderId' in parents and trashed = false"
    }

    internal fun buildFolderListRequest(folderId: String, accessToken: String, pageToken: String? = null): Request {
        val encodedQuery = URLEncoder.encode(buildFolderQuery(folderId), "UTF-8")
        val pageTokenParam = pageToken?.let { "&pageToken=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery" +
            "&fields=nextPageToken,files(id,name,mimeType,thumbnailLink)&pageSize=1000$pageTokenParam"
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
    }

    /**
     * Parses one page's worth of `files.list` response body into unsorted [DriveEntry] values.
     * Non-folder entries whose name doesn't end in one of the four supported comic extensions are
     * skipped entirely (previously such entries defaulted to [ComicFormat.PDF], silently
     * mislabeling e.g. a plain `.zip` file). Left unsorted (unlike the public [parseDriveEntries])
     * so [fetchFolderContents] can accumulate entries across multiple pages and sort once at the
     * end via [sortDriveEntries], instead of producing results that are sorted-per-page-then-
     * concatenated.
     */
    private fun parseDriveEntriesUnsorted(responseBody: String): List<DriveEntry> {
        val json = JSONObject(responseBody)
        val filesArray = json.optJSONArray("files") ?: return emptyList()
        val entries = mutableListOf<DriveEntry>()
        for (i in 0 until filesArray.length()) {
            val f = filesArray.getJSONObject(i)
            val name = f.optString("name", "Untitled")
            val id = f.optString("id")
            val mimeType = f.optString("mimeType")
            if (mimeType == "application/vnd.google-apps.folder") {
                entries.add(DriveEntry.Folder(id = id, name = name))
            } else {
                val format = when {
                    name.endsWith(".cbz", ignoreCase = true) -> ComicFormat.CBZ
                    name.endsWith(".epub", ignoreCase = true) -> ComicFormat.EPUB
                    name.endsWith(".cbr", ignoreCase = true) -> ComicFormat.CBR
                    name.endsWith(".pdf", ignoreCase = true) -> ComicFormat.PDF
                    else -> null
                }
                if (format != null) {
                    val thumbnailLink = f.optString("thumbnailLink").ifEmpty { null }
                    entries.add(
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
        }
        return entries
    }

    private fun sortDriveEntries(entries: List<DriveEntry>): List<DriveEntry> {
        val sortedFolders = entries.filterIsInstance<DriveEntry.Folder>().sortedBy { it.name.lowercase() }
        val sortedFiles = entries.filterIsInstance<DriveEntry.ComicFile>().sortedBy { it.comic.title.lowercase() }
        return sortedFolders + sortedFiles
    }

    internal fun parseDriveEntries(responseBody: String): List<DriveEntry> {
        return sortDriveEntries(parseDriveEntriesUnsorted(responseBody))
    }

    /** Reads a single page's `nextPageToken`, or null when this is the last page. */
    internal fun extractNextPageToken(responseBody: String): String? {
        return JSONObject(responseBody).optString("nextPageToken").ifEmpty { null }
    }

    suspend fun fetchFolderContents(folderId: String, accessToken: String): List<DriveEntry> = withContext(Dispatchers.IO) {
        try {
            val accumulated = mutableListOf<DriveEntry>()
            var pageToken: String? = null
            do {
                val request = buildFolderListRequest(folderId, accessToken, pageToken)
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw DriveApiException("Couldn't load this folder (HTTP ${resp.code})")
                    }
                    val body = resp.body?.string() ?: "{}"
                    accumulated.addAll(parseDriveEntriesUnsorted(body))
                    pageToken = extractNextPageToken(body)
                }
            } while (pageToken != null)
            sortDriveEntries(accumulated)
        } catch (e: DriveApiException) {
            throw e
        } catch (e: Exception) {
            // Covers both a dropped connection during execute()/body read (raw IOException) and a
            // non-JSON response body, e.g. a captive-portal HTML page returned with a 200 status
            // (org.json.JSONException from parseDriveEntriesUnsorted/extractNextPageToken).
            throw DriveApiException("Couldn't reach Google Drive -- check your connection", e)
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
        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw DriveApiException("Couldn't download this file (HTTP ${resp.code})")
                }
                val body = resp.body ?: throw DriveApiException("Empty response from Google Drive")
                destination.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
        } catch (e: DriveApiException) {
            throw e
        } catch (e: Exception) {
            // Covers a dropped connection mid-download (byteStream().copyTo(out) is a raw,
            // blocking IOException source), in addition to the connection-open failure already
            // covered before this change.
            throw DriveApiException("Couldn't reach Google Drive -- check your connection", e)
        }
    }
}
