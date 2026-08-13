package com.comicanything.reader.data.repository

import com.comicanything.reader.data.model.ComicFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveRepositoryTest {

    private val repo = GoogleDriveRepository()

    @Test
    fun `extractFolderId pulls the id out of a folder URL`() {
        val id = repo.extractFolderId("https://drive.google.com/drive/folders/1AbC-XyZ?usp=sharing")
        assertEquals("1AbC-XyZ", id)
    }

    @Test
    fun `extractFolderId returns a bare id unchanged`() {
        val id = repo.extractFolderId("1AbC-XyZ")
        assertEquals("1AbC-XyZ", id)
    }

    @Test
    fun `buildFolderQuery includes folders and all four comic formats`() {
        val query = repo.buildFolderQuery("root")
        assertTrue(query.contains("'root' in parents"))
        assertTrue(query.contains("application/vnd.google-apps.folder"))
        assertTrue(query.contains(".pdf"))
        assertTrue(query.contains(".cbz"))
        assertTrue(query.contains(".epub"))
        assertTrue(query.contains(".cbr"))
    }

    @Test
    fun `buildFolderListRequest sends the token as a Bearer authorization header, not a query param`() {
        val request = repo.buildFolderListRequest("root", "test-token-123")

        assertEquals("Bearer test-token-123", request.header("Authorization"))
        assertTrue(!request.url.toString().contains("key="))
        assertTrue(!request.url.toString().contains("test-token-123"))
    }

    @Test
    fun `buildDownloadRequest targets the alt=media endpoint with a Bearer header`() {
        val request = repo.buildDownloadRequest("file-abc", "test-token-123")

        assertEquals("Bearer test-token-123", request.header("Authorization"))
        assertEquals("https://www.googleapis.com/drive/v3/files/file-abc?alt=media", request.url.toString())
    }

    @Test
    fun `parseDriveEntries sorts folders before files, each group alphabetical`() {
        val json = """
            {
              "files": [
                { "id": "f2", "name": "zebra.pdf", "mimeType": "application/pdf" },
                { "id": "folder2", "name": "Zeta Folder", "mimeType": "application/vnd.google-apps.folder" },
                { "id": "f1", "name": "apple.cbz", "mimeType": "application/zip" },
                { "id": "folder1", "name": "Alpha Folder", "mimeType": "application/vnd.google-apps.folder" }
              ]
            }
        """.trimIndent()

        val entries = repo.parseDriveEntries(json)

        assertEquals(4, entries.size)
        assertEquals("Alpha Folder", (entries[0] as DriveEntry.Folder).name)
        assertEquals("Zeta Folder", (entries[1] as DriveEntry.Folder).name)
        assertEquals("apple.cbz", (entries[2] as DriveEntry.ComicFile).comic.title)
        assertEquals("zebra.pdf", (entries[3] as DriveEntry.ComicFile).comic.title)
    }

    @Test
    fun `parseDriveEntries detects format from file extension`() {
        val json = """
            {
              "files": [
                { "id": "f1", "name": "book.epub", "mimeType": "application/epub+zip" },
                { "id": "f2", "name": "comic.cbr", "mimeType": "application/x-rar-compressed" },
                { "id": "f3", "name": "comic.cbz", "mimeType": "application/zip" },
                { "id": "f4", "name": "doc.pdf", "mimeType": "application/pdf" }
              ]
            }
        """.trimIndent()

        val entries = repo.parseDriveEntries(json).filterIsInstance<DriveEntry.ComicFile>()

        assertEquals(ComicFormat.EPUB, entries.first { it.comic.title == "book.epub" }.comic.format)
        assertEquals(ComicFormat.CBR, entries.first { it.comic.title == "comic.cbr" }.comic.format)
        assertEquals(ComicFormat.CBZ, entries.first { it.comic.title == "comic.cbz" }.comic.format)
        assertEquals(ComicFormat.PDF, entries.first { it.comic.title == "doc.pdf" }.comic.format)
    }

    @Test
    fun `parseDriveEntries builds an authenticated alt=media pathOrUrl, not the legacy webContentLink`() {
        val json = """{"files":[{ "id": "file-xyz", "name": "book.pdf", "mimeType": "application/pdf" }]}"""

        val entries = repo.parseDriveEntries(json).filterIsInstance<DriveEntry.ComicFile>()

        assertEquals("https://www.googleapis.com/drive/v3/files/file-xyz?alt=media", entries.single().comic.pathOrUrl)
    }

    @Test
    fun `parseDriveEntries returns an empty list when the files array is absent`() {
        val entries = repo.parseDriveEntries("{}")
        assertTrue(entries.isEmpty())
    }
}
