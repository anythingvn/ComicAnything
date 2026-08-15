package com.comicanything.reader.data.repository

import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveRepositoryTest {

    private val repo = GoogleDriveRepository()

    private fun folder(id: String, name: String) = DriveEntry.Folder(id, name)

    private fun file(id: String, title: String) = DriveEntry.ComicFile(
        ComicItem(id = id, title = title, pathOrUrl = "https://example.com/$id", source = ComicSource.GOOGLE_DRIVE, format = ComicFormat.PDF)
    )

    /** Builds a fetchFolder fake from a fixed id->children map, tracking how many calls were made. */
    private class FakeTree(private val tree: Map<String, List<DriveEntry>>) {
        var callCount = 0
            private set
        val fetch: suspend (String, String) -> List<DriveEntry> = { folderId, _ ->
            callCount++
            tree[folderId] ?: emptyList()
        }
    }

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
    fun `buildFolderQuery scopes to the parent folder and excludes trashed files`() {
        // Format filtering is no longer done server-side (Drive's `name contains 'X'` operator
        // does prefix-word matching, not substring matching, so a clause like
        // `name contains '.epub'` doesn't reliably match a real filename like "MyBook.epub") --
        // parseDriveEntries now does extension-based filtering client-side instead, so the query
        // itself no longer contains any format-specific substrings.
        val query = repo.buildFolderQuery("root")
        assertTrue(query.contains("'root' in parents"))
        assertTrue(query.contains("trashed = false"))
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

    @Test
    fun `parseDriveEntries excludes a non-folder entry with an unrecognized extension instead of defaulting it to PDF`() {
        val json = """
            {
              "files": [
                { "id": "f1", "name": "notes.txt", "mimeType": "text/plain" },
                { "id": "f2", "name": "comic.cbz", "mimeType": "application/zip" }
              ]
            }
        """.trimIndent()

        val entries = repo.parseDriveEntries(json).filterIsInstance<DriveEntry.ComicFile>()

        assertEquals(1, entries.size)
        assertEquals("comic.cbz", entries.single().comic.title)
    }

    @Test
    fun `buildFolderListRequest appends a pageToken param only when one is supplied`() {
        val withoutToken = repo.buildFolderListRequest("root", "test-token-123")
        assertTrue(!withoutToken.url.toString().contains("pageToken"))
        assertTrue(withoutToken.url.toString().contains("pageSize=1000"))
        assertTrue(withoutToken.url.toString().contains("nextPageToken"))

        val withToken = repo.buildFolderListRequest("root", "test-token-123", pageToken = "abc123")
        assertTrue(withToken.url.toString().contains("pageToken=abc123"))
    }

    @Test
    fun `extractNextPageToken returns the token when present and null otherwise`() {
        assertEquals("token-xyz", repo.extractNextPageToken("""{"nextPageToken":"token-xyz","files":[]}"""))
        assertEquals(null, repo.extractNextPageToken("""{"files":[]}"""))
        assertEquals(null, repo.extractNextPageToken("""{"nextPageToken":"","files":[]}"""))
    }

    @Test
    fun `searchTree matches a file several folders below the search root and records its path`() = runTest {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(folder("f-truyen", "Truyen tranh")),
                "f-truyen" to listOf(folder("f-001", "001 - Doi Bong")),
                "f-001" to listOf(file("c-vol01", "vol01.pdf"))
            )
        )

        val results = repo.searchTree("root", "vol01", "token", fetchFolder = tree.fetch)

        val hit = results.single()
        assertEquals("vol01.pdf", (hit.entry as DriveEntry.ComicFile).comic.title)
        assertEquals(listOf(folder("f-truyen", "Truyen tranh"), folder("f-001", "001 - Doi Bong")), hit.parentPath)
    }

    @Test
    fun `searchTree matches a folder by name without requiring its contents to match`() = runTest {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(folder("f-truyen", "Truyen tranh")),
                "f-truyen" to listOf(folder("f-001", "001 - Doi Bong Thanh Dong"))
            )
        )

        val results = repo.searchTree("root", "thanh dong", "token", fetchFolder = tree.fetch)

        val hit = results.single()
        assertEquals("001 - Doi Bong Thanh Dong", (hit.entry as DriveEntry.Folder).name)
        assertEquals(listOf(folder("f-truyen", "Truyen tranh")), hit.parentPath)
    }

    @Test
    fun `searchTree is case-insensitive and matches by substring`() = runTest {
        val tree = FakeTree(mapOf("root" to listOf(file("c1", "Solo_Leveling.pdf"))))

        val results = repo.searchTree("root", "leveling", "token", fetchFolder = tree.fetch)

        assertEquals(1, results.size)
    }

    @Test
    fun `searchTree does not descend past maxDepth`() = runTest {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(folder("f-1", "Level1")),
                "f-1" to listOf(folder("f-2", "Level2")),
                "f-2" to listOf(file("c1", "buried.pdf"))
            )
        )

        val results = repo.searchTree("root", "buried", "token", maxDepth = 1, fetchFolder = tree.fetch)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `searchTree skips a folder that fails to load instead of aborting the whole search`() = runTest {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(folder("f-broken", "Broken"), folder("f-ok", "Ok")),
                "f-ok" to listOf(file("c1", "target.pdf"))
            )
        )
        val fetchWithFailure: suspend (String, String) -> List<DriveEntry> = { id, token ->
            if (id == "f-broken") throw DriveApiException("nope") else tree.fetch(id, token)
        }

        val results = repo.searchTree("root", "target", "token", fetchFolder = fetchWithFailure)

        assertEquals(1, results.size)
    }

    @Test
    fun `searchTree with a blank query returns no results and makes no calls`() = runTest {
        val tree = FakeTree(mapOf("root" to listOf(file("c1", "book.pdf"))))

        val results = repo.searchTree("root", "   ", "token", fetchFolder = tree.fetch)

        assertTrue(results.isEmpty())
        assertEquals(0, tree.callCount)
    }

    @Test
    fun `searchTree propagates a failure loading the search root itself, unlike a failure deeper in the tree`() = runTest {
        var thrown: DriveApiException? = null
        try {
            repo.searchTree(
                "root",
                "anything",
                "token",
                fetchFolder = { _, _ -> throw DriveApiException("token expired") }
            )
        } catch (e: DriveApiException) {
            thrown = e
        }

        // The root is the one call every search makes unconditionally -- its failure means the
        // search never really ran, so it must surface as an error rather than as "found nothing"
        // (the behavior a failure deeper in the tree still gets, per the test above).
        assertEquals("token expired", thrown?.message)
    }

    @Test
    fun `searchTree walks sibling folders concurrently but still finds every match across branches`() = runTest {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(folder("f-a", "Branch A"), folder("f-b", "Branch B"), folder("f-c", "Branch C")),
                "f-a" to listOf(file("c-a", "match_a.pdf")),
                "f-b" to listOf(file("c-b", "match_b.pdf")),
                "f-c" to listOf(file("c-c", "nomatch.pdf"))
            )
        )

        val results = repo.searchTree("root", "match_", "token", fetchFolder = tree.fetch)

        assertEquals(setOf("match_a.pdf", "match_b.pdf"), results.map { (it.entry as DriveEntry.ComicFile).comic.title }.toSet())
    }
}
