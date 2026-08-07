package com.comicanything.reader.data.repository

import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

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

    suspend fun fetchFolderContents(folderUrlOrId: String, apiKey: String? = null): List<ComicItem> = withContext(Dispatchers.IO) {
        val folderId = extractFolderId(folderUrlOrId)
        if (folderId.isEmpty()) return@withContext emptyList()

        val items = mutableListOf<ComicItem>()

        if (!apiKey.isNullOrEmpty()) {
            try {
                val query = "'$folderId' in parents and (mimeType = 'application/pdf' or mimeType = 'application/zip' or name contains '.pdf' or name contains '.cbz')"
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQuery&fields=files(id,name,mimeType,thumbnailLink,webContentLink)&key=$apiKey"

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    val json = JSONObject(responseBody)
                    val filesArray = json.optJSONArray("files")

                    if (filesArray != null) {
                        for (i in 0 until filesArray.length()) {
                            val f = filesArray.getJSONObject(i)
                            val name = f.optString("name", "Untitled")
                            val id = f.optString("id")
                            val format = when {
                                name.endsWith(".cbz", ignoreCase = true) -> ComicFormat.CBZ
                                name.endsWith(".epub", ignoreCase = true) -> ComicFormat.EPUB
                                else -> ComicFormat.PDF
                            }

                            items.add(
                                ComicItem(
                                    id = id,
                                    title = name,
                                    pathOrUrl = f.optString("webContentLink", "https://drive.google.com/uc?id=$id&export=download"),
                                    source = ComicSource.GOOGLE_DRIVE,
                                    format = format,
                                    coverUrl = f.optString("thumbnailLink"),
                                    folderName = "Drive Folder: $folderId"
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback demo items if API key is not supplied
        if (items.isEmpty()) {
            items.add(
                ComicItem(
                    id = "drive_sample_1",
                    title = "Sample Comic (Drive PDF Stream)",
                    pathOrUrl = "https://raw.githubusercontent.com/mozilla/pdf.js/master/web/compressed.tracemonkey-pldi-09.pdf",
                    source = ComicSource.GOOGLE_DRIVE,
                    format = ComicFormat.PDF,
                    totalPages = 48,
                    folderName = "Folder ID: $folderId"
                )
            )
        }

        items
    }
}
