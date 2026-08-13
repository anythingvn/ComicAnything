# Google Drive File Access & Browsing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Google Drive comics actually readable — real folder browsing from Drive root down through subfolders, and real file access (download once to a persistent offline cache, then open through the exact same decoders every local comic already uses).

**Architecture:** A shared `resolveComicFile` step runs before both existing open paths (`createPageSource` for PDF/CBZ/CBR, `epubExtractor` for EPUB) — for a `LOCAL` comic it's an instant passthrough; for a `GOOGLE_DRIVE` comic it's a cache-first lookup that downloads via a real OAuth-authenticated Drive API call only on a cache miss. `GoogleDriveRepository` switches from an unused API-key model to real Bearer-token auth and returns a mixed folder+file list (`DriveEntry`) instead of files-only, so the UI can render a genuine breadcrumb-navigable folder tree.

**Tech Stack:** Kotlin, OkHttp (already a dependency), Drive API v3 REST endpoints (`files.list`, `files.get?alt=media`), Jetpack Compose.

**Spec:** [docs/superpowers/specs/2026-08-13-drive-file-access-design.md](../specs/2026-08-13-drive-file-access-design.md)

## Global Constraints

- Replace the unused `apiKey: String?` query-param auth in `GoogleDriveRepository` with real OAuth `Authorization: Bearer <token>` header auth. The hardcoded public-PDF demo-item fallback is removed entirely.
- The Drive query widens to include subfolders (`mimeType = 'application/vnd.google-apps.folder'`) and EPUB/CBR files (`name contains '.epub' or name contains '.cbr'`) — the current query predates both.
- Every download is a full fetch to a local cache before the comic opens (no byte-range/progressive streaming) — mirrors the "extract once, reuse existing decoders" pattern already used for EPUB and CBR.
- The downloaded-file cache is **persistent** (`Application.filesDir`, not `cacheDir` — Android can wipe `cacheDir` under storage pressure without warning, which would defeat the offline-read purpose of this cache), keyed by `comic.id` (the Drive file ID), with `.part`-file-then-atomic-rename writes so a failed/killed download never leaves a corrupt file mistaken for a valid cache hit. LRU-by-total-size eviction capped at **750MB**.
- Cache invalidation on a remote file being edited/replaced (same ID, new content) is explicitly out of scope — a cache hit is authoritative in v1.
- No new download-progress UI (percentage/bytes) — reuse the existing brief-spinner pattern already used for CBR/EPUB's extraction wait.
- **Found during plan-writing, not in the spec:** Drive cover thumbnails remain out of scope for this plan. `DriveEntry.ComicFile.comic.coverUrl` is populated from the Drive API's `thumbnailLink` field (Task 1), but nothing in this plan renders it — `DriveContent`'s entry rows (Task 4) use the same static book icon the app already shows for every non-thumbnail-decoded format, and `ComicCoverThumbnail` (used elsewhere in the library UI) only calls `loadCoverThumbnail`/`decodeThumbnail`, which handles `LOCAL` PDF/CBZ exclusively — it has no Coil/`coverUrl` path at all. Epic 7's own notes flagged Drive thumbnails as blocked on "real Drive folder data (Epic 4 sub-project 2)"; this plan delivers that data but wiring up the actual Coil-based rendering is a separate, cleanly-follow-up-able task, not required to make Drive comics browsable or readable.
- No mocking library is used in this project — tests use real objects, real temp directories, and injected fake function references for anything that would otherwise require real network I/O (matching the existing `epubExtractor`/`thumbnailDecoder`/`cbrCacheRoot` precedent).
- Existing package root: `com.comicanything.reader`. Repository-layer files live in `com.comicanything.reader.data.repository`.
- **Two corrections to the spec, found while writing this plan (both already applied below, not left as open questions):**
  1. The spec's `resolveComicFile: suspend (ComicItem, DriveFileCache, () -> String?) -> File` constructor property on `ReaderViewModel` would shadow the top-level `resolveComicFile` function it's meant to default to (a lambda referencing `resolveComicFile(...)` inside its own default-value initializer is ambiguous/self-referential). The constructor property is renamed to `comicFileResolver` below; the top-level function keeps the name `resolveComicFile`.
  2. The spec's `fetchFolderContents(folderId: String, accessToken: String?)` takes a nullable token, implying the repository itself decides what to do with `null`. To keep the "null token → distinct error, no network attempt" check in exactly one place (matching how `resolveComicFile` already owns this check for downloads), `GoogleDriveRepository.fetchFolderContents`/`downloadFile` both take a **non-null** `accessToken: String` instead — the caller (`ReaderViewModel`) is responsible for checking `driveAccessToken()` for `null` before ever calling into the repository.

---

### Task 1: `GoogleDriveRepository` — Bearer auth, `DriveEntry`, widened query, `downloadFile`

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/data/repository/GoogleDriveRepository.kt`
- Create: `app/src/test/java/com/comicanything/reader/data/repository/GoogleDriveRepositoryTest.kt`

**Interfaces:**
- Produces: `sealed interface DriveEntry { data class Folder(val id: String, val name: String); data class ComicFile(val comic: ComicItem) }` — consumed by Task 3's `ReaderViewModel`/`ReaderUiState` and Task 4's `DriveContent`.
- Produces: `class DriveApiException(message: String, cause: Throwable? = null) : Exception(message, cause)` — consumed by Task 2's `resolveComicFile` and Task 3's `ReaderViewModel`.
- Produces: `suspend fun GoogleDriveRepository.fetchFolderContents(folderId: String, accessToken: String): List<DriveEntry>` — consumed by Task 3.
- Produces: `suspend fun GoogleDriveRepository.downloadFile(fileId: String, destination: File, accessToken: String): Unit` — consumed by Task 3 (bound as `driveRepo::downloadFile`).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/comicanything/reader/data/repository/GoogleDriveRepositoryTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.repository.GoogleDriveRepositoryTest" --no-daemon
```

Expected: FAIL — `buildFolderQuery`, `buildFolderListRequest`, `buildDownloadRequest`, `parseDriveEntries`, and `DriveEntry` don't exist yet.

- [ ] **Step 3: Rewrite `GoogleDriveRepository.kt`**

Replace the full contents of `app/src/main/java/com/comicanything/reader/data/repository/GoogleDriveRepository.kt`:

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.repository.GoogleDriveRepositoryTest" --no-daemon
```

Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/data/repository/GoogleDriveRepository.kt app/src/test/java/com/comicanything/reader/data/repository/GoogleDriveRepositoryTest.kt
git commit -m "feat: replace Drive apiKey auth with OAuth Bearer tokens and folder+file listing"
```

---

### Task 2: `DriveFileCache` + `resolveComicFile`

**Files:**
- Create: `app/src/main/java/com/comicanything/reader/data/repository/DriveFileCache.kt`
- Create: `app/src/main/java/com/comicanything/reader/data/repository/ComicFileResolver.kt`
- Create: `app/src/test/java/com/comicanything/reader/data/repository/DriveFileCacheTest.kt`
- Create: `app/src/test/java/com/comicanything/reader/data/repository/ComicFileResolverTest.kt`

**Interfaces:**
- Consumes: `class DriveApiException(message: String, cause: Throwable?) : Exception` (Task 1).
- Produces: `class DriveFileCache(cacheDir: File, maxTotalBytes: Long = DriveFileCache.DEFAULT_MAX_BYTES)` with `fun cachedFile(comicId: String): File?` and `suspend fun download(comicId: String, download: suspend (File) -> Unit): File` — consumed by Task 3's `ReaderViewModel`.
- Produces: `suspend fun resolveComicFile(comic: ComicItem, driveCache: DriveFileCache, downloadDriveFile: suspend (fileId: String, destination: File, accessToken: String) -> Unit, accessToken: () -> String?): File` — consumed by Task 3's `ReaderViewModel`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/comicanything/reader/data/repository/DriveFileCacheTest.kt`:

```kotlin
package com.comicanything.reader.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DriveFileCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `cachedFile returns null when nothing is cached`() {
        val cache = DriveFileCache(tempFolder.newFolder("cache"))
        assertNull(cache.cachedFile("missing-id"))
    }

    @Test
    fun `download writes the file and cachedFile finds it afterward`() = runTest {
        val cache = DriveFileCache(tempFolder.newFolder("cache"))

        val result = cache.download("comic-1") { dest -> dest.writeText("hello") }

        assertEquals("hello", result.readText())
        assertEquals("hello", cache.cachedFile("comic-1")?.readText())
    }

    @Test
    fun `a failed download leaves no part file and no final file behind`() = runTest {
        val cacheDir = tempFolder.newFolder("cache")
        val cache = DriveFileCache(cacheDir)

        try {
            cache.download("comic-1") { dest ->
                dest.writeText("partial")
                throw IOException("network dropped")
            }
        } catch (e: IOException) {
            // expected
        }

        assertNull(cache.cachedFile("comic-1"))
        assertTrue(cacheDir.listFiles()?.isEmpty() != false)
    }

    @Test
    fun `eviction deletes the least-recently-opened file first once the cap is exceeded`() = runTest {
        val cacheDir = tempFolder.newFolder("cache")
        // A tiny cap (10 bytes) so two 6-byte files force eviction without needing real large files.
        val cache = DriveFileCache(cacheDir, maxTotalBytes = 10)

        cache.download("old") { dest -> dest.writeText("aaaaaa") } // 6 bytes
        File(cacheDir, "old").setLastModified(System.currentTimeMillis() - 60_000)

        cache.download("new") { dest -> dest.writeText("bbbbbb") } // 6 bytes; total 12 > cap of 10

        assertNull(cache.cachedFile("old"))
        assertEquals("bbbbbb", cache.cachedFile("new")?.readText())
    }
}
```

Create `app/src/test/java/com/comicanything/reader/data/repository/ComicFileResolverTest.kt`:

```kotlin
package com.comicanything.reader.data.repository

import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComicFileResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `a LOCAL comic resolves to its path directly with no cache or network involved`() = runTest {
        val localFile = tempFolder.newFile("book.pdf")
        val comic = ComicItem(
            id = "1",
            title = "Local Book",
            pathOrUrl = localFile.absolutePath,
            source = ComicSource.LOCAL,
            format = ComicFormat.PDF
        )
        val cache = DriveFileCache(tempFolder.newFolder("drive-cache"))
        var downloadCalls = 0

        val resolved = resolveComicFile(
            comic,
            cache,
            downloadDriveFile = { _, _, _ -> downloadCalls++ },
            accessToken = { "should-not-be-used" }
        )

        assertEquals(localFile.absolutePath, resolved.absolutePath)
        assertEquals(0, downloadCalls)
    }

    @Test
    fun `a cached Drive comic resolves from the cache with no network call`() = runTest {
        val cache = DriveFileCache(tempFolder.newFolder("drive-cache"))
        cache.download("drive-1") { dest -> dest.writeText("cached bytes") }
        val comic = ComicItem(
            id = "drive-1",
            title = "Drive Book",
            pathOrUrl = "https://www.googleapis.com/drive/v3/files/drive-1?alt=media",
            source = ComicSource.GOOGLE_DRIVE,
            format = ComicFormat.PDF
        )
        var downloadCalls = 0

        val resolved = resolveComicFile(
            comic,
            cache,
            downloadDriveFile = { _, _, _ -> downloadCalls++ },
            accessToken = { "irrelevant-since-cached" }
        )

        assertEquals("cached bytes", resolved.readText())
        assertEquals(0, downloadCalls)
    }

    @Test
    fun `a cache-miss Drive comic downloads via the injected function and returns the result`() = runTest {
        val cache = DriveFileCache(tempFolder.newFolder("drive-cache"))
        val comic = ComicItem(
            id = "drive-2",
            title = "Drive Book",
            pathOrUrl = "https://www.googleapis.com/drive/v3/files/drive-2?alt=media",
            source = ComicSource.GOOGLE_DRIVE,
            format = ComicFormat.CBZ
        )
        var capturedFileId: String? = null
        var capturedToken: String? = null

        val resolved = resolveComicFile(
            comic,
            cache,
            downloadDriveFile = { fileId, destination, token ->
                capturedFileId = fileId
                capturedToken = token
                destination.writeText("downloaded bytes")
            },
            accessToken = { "real-token" }
        )

        assertEquals("drive-2", capturedFileId)
        assertEquals("real-token", capturedToken)
        assertEquals("downloaded bytes", resolved.readText())
        assertEquals("downloaded bytes", cache.cachedFile("drive-2")?.readText())
    }

    @Test(expected = DriveApiException::class)
    fun `a cache-miss Drive comic with no access token fails fast without attempting a download`() = runTest {
        val cache = DriveFileCache(tempFolder.newFolder("drive-cache"))
        val comic = ComicItem(
            id = "drive-3",
            title = "Drive Book",
            pathOrUrl = "https://www.googleapis.com/drive/v3/files/drive-3?alt=media",
            source = ComicSource.GOOGLE_DRIVE,
            format = ComicFormat.PDF
        )

        resolveComicFile(
            comic,
            cache,
            downloadDriveFile = { _, _, _ -> throw AssertionError("should not be called") },
            accessToken = { null }
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.repository.DriveFileCacheTest" --tests "com.comicanything.reader.data.repository.ComicFileResolverTest" --no-daemon
```

Expected: FAIL — `DriveFileCache.kt` and `ComicFileResolver.kt` don't exist yet.

- [ ] **Step 3: Implement `DriveFileCache.kt`**

Create `app/src/main/java/com/comicanything/reader/data/repository/DriveFileCache.kt`:

```kotlin
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
```

- [ ] **Step 4: Implement `ComicFileResolver.kt`**

Create `app/src/main/java/com/comicanything/reader/data/repository/ComicFileResolver.kt`:

```kotlin
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
```

- [ ] **Step 5: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.data.repository.DriveFileCacheTest" --tests "com.comicanything.reader.data.repository.ComicFileResolverTest" --no-daemon
```

Expected: PASS (4 + 4 = 8 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/data/repository/DriveFileCache.kt app/src/main/java/com/comicanything/reader/data/repository/ComicFileResolver.kt app/src/test/java/com/comicanything/reader/data/repository/DriveFileCacheTest.kt app/src/test/java/com/comicanything/reader/data/repository/ComicFileResolverTest.kt
git commit -m "feat: add persistent DriveFileCache and resolveComicFile for local/Drive passthrough"
```

---

### Task 3: Wire into `ComicPageSource`, `ReaderViewModel`, and `MainActivity`

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/data/pagesource/ComicPageSource.kt`
- Modify: `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`
- Modify: `app/src/main/java/com/comicanything/reader/MainActivity.kt`
- Modify: `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `DriveEntry`, `DriveApiException` (Task 1); `DriveFileCache`, `resolveComicFile` (Task 2).
- Produces: `fun createPageSource(comic: ComicItem, cbrCacheRoot: () -> File, file: File): ComicPageSource` (signature change — takes the already-resolved file, no longer gates on `comic.source`).
- Produces on `ReaderViewModel`: `fun navigateDriveFolder(folderId: String, name: String)`, `fun navigateDriveUp(toIndex: Int)`, `fun retryDriveFolder()`, `fun navigateToLinkedFolder(folderUrlOrId: String)` — consumed by Task 4's `DriveContent`. A new constructor property `fetchDriveFolderContents: suspend (String, String) -> List<DriveEntry> = driveRepo::fetchFolderContents` is added alongside `comicFileResolver`, for the same reason: it lets tests substitute a fake instead of making a real network call, matching the `epubExtractor`/`comicFileResolver` precedent.
- Produces on `ReaderUiState`: `driveEntries: List<DriveEntry>`, `driveBreadcrumbs: List<DriveBreadcrumb>`, `driveError: String?` (replacing `driveComics: List<ComicItem>`) — consumed by Task 4.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt`, replace the existing test at line 171-187 (`opening a Google Drive comic sets an error even for a supported format`) with:

```kotlin
    @Test
    fun `opening a Google Drive comic resolves through the injected resolver and opens normally`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val driveFile = File(tempFolder.newFolder("drive-cache"), "cached.cbz")
        java.util.zip.ZipOutputStream(driveFile.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("page1.jpg"))
            zip.write("fake-jpeg-bytes".toByteArray())
            zip.closeEntry()
        }
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            comicFileResolver = { _, _, _ -> driveFile }
        )
        val comic = ComicItem(
            id = "2",
            title = "Drive Book",
            pathOrUrl = "https://www.googleapis.com/drive/v3/files/2?alt=media",
            source = ComicSource.GOOGLE_DRIVE,
            format = ComicFormat.CBZ
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        // The old behavior always set pageLoadError = "This format isn't supported yet" purely
        // because comic.source was GOOGLE_DRIVE. Now that createPageSource no longer gates on
        // source at all (resolution happens first via the injected comicFileResolver), a Drive
        // comic opens through the exact same CbzPageSource path a LOCAL CBZ would -- totalPages
        // comes from real zip-entry listing, not BitmapFactory decode, so this is a genuine
        // assertion, not a decode-dependent one (matching the same honest limitation already
        // accepted for CBR/PDF/CBZ tests elsewhere in this file).
        assertEquals(1, viewModel.uiState.value.totalPages)
        assertNull(viewModel.uiState.value.pageLoadError)
    }

    @Test
    fun `a Drive download failure surfaces through pageLoadError like any other open failure`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            comicFileResolver = { _, _, _ -> throw DriveApiException("Not connected to Google Drive") }
        )
        val comic = ComicItem(
            id = "3",
            title = "Drive Book",
            pathOrUrl = "https://www.googleapis.com/drive/v3/files/3?alt=media",
            source = ComicSource.GOOGLE_DRIVE,
            format = ComicFormat.PDF
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        assertEquals("Not connected to Google Drive", viewModel.uiState.value.pageLoadError)
    }

    @Test
    fun `opening a LOCAL comic never touches driveFileCache or driveAccessToken`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        var driveAccessTokenCalls = 0
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { driveAccessTokenCalls++; "should-not-be-used" }
        )
        val comic = ComicItem(
            id = "4",
            title = "Local Book",
            pathOrUrl = "/fake/path.cbz",
            source = ComicSource.LOCAL,
            format = ComicFormat.CBZ
        )

        viewModel.openComic(comic)
        advanceUntilIdle()

        // Proves comicFileResolver's default (calling resolveComicFile for real) genuinely takes
        // the LOCAL passthrough branch without ever invoking driveAccessToken -- matching the same
        // laziness guarantee cbrCacheRoot already has for non-CBR opens.
        assertEquals(0, driveAccessTokenCalls)
    }

    @Test
    fun `navigateDriveFolder pushes a breadcrumb and populates driveEntries on success`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val fakeEntries = listOf(DriveEntry.Folder(id = "sub1", name = "Comics"))
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> fakeEntries }
        )

        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()

        assertEquals(listOf("My Drive"), viewModel.uiState.value.driveBreadcrumbs.map { it.name })
        assertEquals(fakeEntries, viewModel.uiState.value.driveEntries)
        assertNull(viewModel.uiState.value.driveError)
        assertFalse(viewModel.uiState.value.isLoadingDrive)
    }

    @Test
    fun `navigateDriveFolder surfaces a DriveApiException from the fetch as driveError`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { "token" },
            fetchDriveFolderContents = { _, _ -> throw DriveApiException("Couldn't reach Google Drive -- check your connection") }
        )

        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()

        assertEquals("Couldn't reach Google Drive -- check your connection", viewModel.uiState.value.driveError)
        assertFalse(viewModel.uiState.value.isLoadingDrive)
    }

    @Test
    fun `navigateDriveFolder without a connected token sets a not-connected error and never calls the repository`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { null }
        )

        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()

        assertEquals("Connect your Google Drive to browse it", viewModel.uiState.value.driveError)
        assertTrue(viewModel.uiState.value.driveEntries.isEmpty())
    }

    @Test
    fun `navigateDriveUp truncates breadcrumbs to the tapped level`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { null } // forces a fast, deterministic driveError instead of a real network call
        )
        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()
        viewModel.navigateDriveFolder("sub1", "Comics")
        advanceUntilIdle()
        viewModel.navigateDriveFolder("sub2", "Volume 1")
        advanceUntilIdle()
        assertEquals(listOf("My Drive", "Comics", "Volume 1"), viewModel.uiState.value.driveBreadcrumbs.map { it.name })

        viewModel.navigateDriveUp(1)
        advanceUntilIdle()

        assertEquals(listOf("My Drive", "Comics"), viewModel.uiState.value.driveBreadcrumbs.map { it.name })
    }

    @Test
    fun `disconnectDrive clears driveEntries, breadcrumbs, and driveError`() = runTest {
        val repo = LocalFileRepository(rootPath = tempFolder.root.absolutePath, ioDispatcher = Dispatchers.Unconfined)
        val viewModel = ReaderViewModel(
            application = fakeApplication,
            localRepo = repo,
            ioDispatcher = Dispatchers.Unconfined,
            progressRepo = progressRepo,
            connectionRepo = connectionRepo,
            driveAccessToken = { null }
        )
        viewModel.navigateDriveFolder("root", "My Drive")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.driveBreadcrumbs.isNotEmpty())
        assertTrue(viewModel.uiState.value.driveError != null)

        viewModel.disconnectDrive()

        assertTrue(viewModel.uiState.value.driveBreadcrumbs.isEmpty())
        assertTrue(viewModel.uiState.value.driveEntries.isEmpty())
        assertNull(viewModel.uiState.value.driveError)
    }
```

Add these imports to the top of `ReaderViewModelTest.kt` alongside the existing ones:

```kotlin
import com.comicanything.reader.data.repository.DriveApiException
import com.comicanything.reader.data.repository.DriveEntry
import org.junit.Assert.assertFalse
```

(`assertFalse` may already be imported — check before adding a duplicate.)

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: FAIL — `comicFileResolver`/`driveFileCache` constructor params, `navigateDriveFolder`/`navigateDriveUp`/`disconnectDrive`'s new state fields don't exist yet.

- [ ] **Step 3: Update `ComicPageSource.kt`**

Replace the full contents of `app/src/main/java/com/comicanything/reader/data/pagesource/ComicPageSource.kt`:

```kotlin
package com.comicanything.reader.data.pagesource

import android.graphics.Bitmap
import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import java.io.File

interface ComicPageSource {
    val pageCount: Int
    suspend fun getPage(page: Int): Bitmap
    fun close()
}

class UnsupportedFormatException(format: ComicFormat) :
    Exception("Rendering not supported for format: $format")

class PageDecodeException(page: Int, cause: Throwable) :
    Exception("Failed to decode page $page", cause)

fun createPageSource(comic: ComicItem, cbrCacheRoot: () -> File, file: File): ComicPageSource {
    return when (comic.format) {
        ComicFormat.PDF -> PdfPageSource(file)
        ComicFormat.CBZ -> CbzPageSource(file)
        ComicFormat.CBR -> CbrPageSource(file, File(cbrCacheRoot(), comic.id))
        else -> throw UnsupportedFormatException(comic.format)
    }
}
```

The `if (comic.source != ComicSource.LOCAL) throw UnsupportedFormatException(...)` guard and the `ComicSource` import are removed — `createPageSource` is purely a format dispatcher now; resolving *which* file to open (local passthrough or downloaded-and-cached) happens one layer up, in `ReaderViewModel`, before this function is ever called.

- [ ] **Step 4: Update `ReaderViewModel.kt`**

In `app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt`:

Add these imports alongside the existing ones:

```kotlin
import com.comicanything.reader.data.repository.DriveApiException
import com.comicanything.reader.data.repository.DriveEntry
import com.comicanything.reader.data.repository.DriveFileCache
import com.comicanything.reader.data.repository.resolveComicFile
```

Add `DriveBreadcrumb` above `ReaderUiState` (alongside where `PageLoadState`/`CoverLoadState` are declared later in the file — put this new type right before `data class ReaderUiState`):

```kotlin
data class DriveBreadcrumb(val folderId: String, val name: String)
```

Change `ReaderUiState` from:

```kotlin
data class ReaderUiState(
    val libraryComics: List<ComicItem> = emptyList(),
    val driveComics: List<ComicItem> = emptyList(),
    val activeComic: ComicItem? = null,
```

to:

```kotlin
data class ReaderUiState(
    val libraryComics: List<ComicItem> = emptyList(),
    val driveEntries: List<DriveEntry> = emptyList(),
    val driveBreadcrumbs: List<DriveBreadcrumb> = emptyList(),
    val driveError: String? = null,
    val activeComic: ComicItem? = null,
```

(the rest of `ReaderUiState`'s fields are unchanged).

Change the constructor from:

```kotlin
class ReaderViewModel @JvmOverloads constructor(
    application: Application,
    private val localRepo: LocalFileRepository = LocalFileRepository(),
    private val driveRepo: GoogleDriveRepository = GoogleDriveRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val progressRepo: ReadingProgressRepository = ReadingProgressRepository(application),
    private val connectionRepo: DriveConnectionRepository = DriveConnectionRepository(application),
    private val thumbnailDecoder: suspend (ComicItem) -> Bitmap? = ::decodeThumbnail,
    private val epubExtractor: suspend (File, File) -> EpubBook? = ::extractEpub,
    private val epubCacheRoot: () -> File = { File(application.cacheDir, "epub_temp") },
    private val cbrCacheRoot: () -> File = { File(application.cacheDir, "cbr_temp") }
) : AndroidViewModel(application) {
```

to:

```kotlin
class ReaderViewModel @JvmOverloads constructor(
    application: Application,
    private val localRepo: LocalFileRepository = LocalFileRepository(),
    private val driveRepo: GoogleDriveRepository = GoogleDriveRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val progressRepo: ReadingProgressRepository = ReadingProgressRepository(application),
    private val connectionRepo: DriveConnectionRepository = DriveConnectionRepository(application),
    private val thumbnailDecoder: suspend (ComicItem) -> Bitmap? = ::decodeThumbnail,
    private val epubExtractor: suspend (File, File) -> EpubBook? = ::extractEpub,
    private val epubCacheRoot: () -> File = { File(application.cacheDir, "epub_temp") },
    private val cbrCacheRoot: () -> File = { File(application.cacheDir, "cbr_temp") },
    private val driveFileCache: DriveFileCache = DriveFileCache(File(application.filesDir, "drive_cache")),
    private val driveAccessToken: () -> String? = { null },
    private val comicFileResolver: suspend (ComicItem, DriveFileCache, () -> String?) -> File =
        { comic, cache, token -> resolveComicFile(comic, cache, driveRepo::downloadFile, token) },
    private val fetchDriveFolderContents: suspend (String, String) -> List<DriveEntry> = driveRepo::fetchFolderContents
) : AndroidViewModel(application) {
```

Replace `fetchDriveFolder` (the whole function) with:

```kotlin
    fun navigateToLinkedFolder(folderUrlOrId: String) {
        val folderId = driveRepo.extractFolderId(folderUrlOrId)
        navigateDriveFolder(folderId, name = folderId)
    }

    fun navigateDriveFolder(folderId: String, name: String) {
        _uiState.value = _uiState.value.copy(
            driveBreadcrumbs = _uiState.value.driveBreadcrumbs + DriveBreadcrumb(folderId, name)
        )
        fetchCurrentDriveFolder()
    }

    fun navigateDriveUp(toIndex: Int) {
        val breadcrumbs = _uiState.value.driveBreadcrumbs
        if (toIndex !in breadcrumbs.indices) return
        _uiState.value = _uiState.value.copy(driveBreadcrumbs = breadcrumbs.take(toIndex + 1))
        fetchCurrentDriveFolder()
    }

    fun retryDriveFolder() {
        fetchCurrentDriveFolder()
    }

    private fun fetchCurrentDriveFolder() {
        val current = _uiState.value.driveBreadcrumbs.lastOrNull() ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingDrive = true, driveError = null)
            val token = driveAccessToken()
            if (token == null) {
                _uiState.value = _uiState.value.copy(
                    isLoadingDrive = false,
                    driveError = "Connect your Google Drive to browse it"
                )
                return@launch
            }
            try {
                val entries = fetchDriveFolderContents(current.folderId, token)
                _uiState.value = _uiState.value.copy(driveEntries = entries, isLoadingDrive = false)
            } catch (e: DriveApiException) {
                _uiState.value = _uiState.value.copy(
                    isLoadingDrive = false,
                    driveError = e.message ?: "Couldn't load this folder"
                )
            }
        }
    }
```

Change `disconnectDrive` from:

```kotlin
    fun disconnectDrive() {
        _uiState.value = _uiState.value.copy(isDriveConnected = false, driveAccountEmail = null)
        viewModelScope.launch {
            connectionRepo.save(DriveConnectionHint(isConnected = false, accountEmail = null))
        }
    }
```

to:

```kotlin
    fun disconnectDrive() {
        _uiState.value = _uiState.value.copy(
            isDriveConnected = false,
            driveAccountEmail = null,
            driveEntries = emptyList(),
            driveBreadcrumbs = emptyList(),
            driveError = null
        )
        viewModelScope.launch {
            connectionRepo.save(DriveConnectionHint(isConnected = false, accountEmail = null))
        }
    }
```

In `openComic`, change:

```kotlin
            val source = try {
                withContext(ioDispatcher) { createPageSource(comic, cbrCacheRoot) }
            } catch (e: UnsupportedFormatException) {
```

to:

```kotlin
            val source = try {
                withContext(ioDispatcher) {
                    val file = comicFileResolver(comic, driveFileCache, driveAccessToken)
                    createPageSource(comic, cbrCacheRoot, file)
                }
            } catch (e: UnsupportedFormatException) {
```

(the rest of `openComic`'s catch chain — `CancellationException` rethrown, generic `Exception` sets `pageLoadError = e.message ?: "Failed to open comic"` — is unchanged and already correctly handles a thrown `DriveApiException`, since it's a plain `Exception` subtype with an already-user-friendly `.message`).

In `openEpubComic`, change:

```kotlin
        activeOpenJob = viewModelScope.launch {
            closePreviousComicResources(previousCache, previousExtractedDir)
            val extractionDir = File(epubCacheRoot(), comic.id)
            val book = withContext(ioDispatcher) { epubExtractor(File(comic.pathOrUrl), extractionDir) }
            if (_uiState.value.activeComic?.id != comic.id) {
```

to:

```kotlin
        activeOpenJob = viewModelScope.launch {
            closePreviousComicResources(previousCache, previousExtractedDir)
            val extractionDir = File(epubCacheRoot(), comic.id)
            val book = try {
                withContext(ioDispatcher) {
                    val file = comicFileResolver(comic, driveFileCache, driveAccessToken)
                    epubExtractor(file, extractionDir)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_uiState.value.activeComic?.id == comic.id) {
                    _uiState.value = _uiState.value.copy(pageLoadError = e.message ?: "Couldn't open this EPUB file")
                }
                return@launch
            }
            if (_uiState.value.activeComic?.id != comic.id) {
```

This new try/catch is necessary (not present before): previously, resolving an EPUB's file was just `File(comic.pathOrUrl)`, which never throws. Now that `comicFileResolver` can throw `DriveApiException` (network failure, no token) for a `GOOGLE_DRIVE` EPUB, an uncaught exception here would otherwise propagate out of `viewModelScope.launch` uncaught.

- [ ] **Step 5: Update `MainActivity.kt`**

In `app/src/main/java/com/comicanything/reader/MainActivity.kt`, add this import alongside the existing ones:

```kotlin
import androidx.lifecycle.ViewModelProvider
```

Change:

```kotlin
    private val viewModel: ReaderViewModel by viewModels()
```

to:

```kotlin
    private val viewModel: ReaderViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return ReaderViewModel(application, driveAccessToken = { lastAccessToken }) as T
            }
        }
    }
```

`lastAccessToken` is declared later in the same class (line 42 in the current file) — this is valid Kotlin: the factory lambda only reads `lastAccessToken` when `create()` actually runs (on first `ViewModelProvider.get()` call, well after the constructor finishes), not at class-body-evaluation time, so declaration order between the two properties doesn't matter.

- [ ] **Step 6: Run tests to verify they pass**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.comicanything.reader.ui.reader.ReaderViewModelTest" --no-daemon
```

Expected: PASS (all existing tests plus the 8 new/changed ones).

- [ ] **Step 7: Run the full test suite as a regression check**

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

Expected: all tests across the whole project pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/data/pagesource/ComicPageSource.kt app/src/main/java/com/comicanything/reader/ui/reader/ReaderViewModel.kt app/src/main/java/com/comicanything/reader/MainActivity.kt app/src/test/java/com/comicanything/reader/ui/reader/ReaderViewModelTest.kt
git commit -m "feat: wire Drive file resolution into ReaderViewModel and add folder navigation"
```

---

### Task 4: `DriveContent` UI — breadcrumb navigation, folder/file rows, error/empty states

**Files:**
- Modify: `app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt`

**Interfaces:**
- Consumes: `DriveEntry` (Task 1); `ReaderViewModel.navigateDriveFolder`, `navigateDriveUp`, `retryDriveFolder`, `navigateToLinkedFolder`, `ReaderUiState.driveEntries/driveBreadcrumbs/driveError` (Task 3).

- [ ] **Step 1: Add new imports**

In `app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt`, add these imports alongside the existing ones:

```kotlin
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import com.comicanything.reader.data.repository.DriveEntry
```

- [ ] **Step 2: Replace the `DriveContent` composable**

Replace the full `DriveContent` function (currently lines 534-628) with:

```kotlin
@Composable
fun DriveContent(
    state: ReaderUiState,
    input: String,
    onInputChange: (String) -> Unit,
    onFetchLink: () -> Unit,
    onNavigateFolder: (String, String) -> Unit,
    onNavigateUp: (Int) -> Unit,
    onRetry: () -> Unit,
    onOpenComic: (ComicItem) -> Unit,
    onConnectDrive: () -> Unit,
    onDisconnectDrive: () -> Unit
) {
    LaunchedEffect(state.isDriveConnected) {
        if (state.isDriveConnected && state.driveBreadcrumbs.isEmpty()) {
            onNavigateFolder("root", "My Drive")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.isDriveConnected) {
                Text(
                    text = "Connected" + (state.driveAccountEmail?.let { " as $it" } ?: ""),
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDisconnectDrive) {
                    Text("Disconnect")
                }
            } else {
                Text(
                    text = "Connect your Google Drive to browse it.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onConnectDrive) {
                    Text("Connect")
                }
            }
        }

        if (state.isDriveConnected && state.driveBreadcrumbs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                state.driveBreadcrumbs.forEachIndexed { index, crumb ->
                    if (index > 0) {
                        Text(" > ", color = Color.Gray, fontSize = 13.sp)
                    }
                    Text(
                        text = crumb.name,
                        color = if (index == state.driveBreadcrumbs.lastIndex) MaterialTheme.colorScheme.primary else Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { onNavigateUp(index) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text("Jump to folder (paste a Drive folder URL/ID)") },
            trailingIcon = {
                IconButton(onClick = onFetchLink) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        when {
            state.isLoadingDrive -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            !state.isDriveConnected -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Connect your Google Drive above to browse your files.",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            state.driveError != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = state.driveError,
                            color = Color.Gray,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onRetry) {
                            Text("Retry")
                        }
                    }
                }
            }
            state.driveEntries.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "This folder is empty.",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.driveEntries) { entry ->
                        when (entry) {
                            is DriveEntry.Folder -> ListItem(
                                headlineContent = { Text(entry.name, color = Color.White, fontWeight = FontWeight.Bold) },
                                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onNavigateFolder(entry.id, entry.name) }
                                    .background(MaterialTheme.colorScheme.surface)
                            )
                            is DriveEntry.ComicFile -> ListItem(
                                headlineContent = { Text(entry.comic.title, color = Color.White, fontWeight = FontWeight.Bold) },
                                supportingContent = { Text(entry.comic.format.name, color = Color.Gray, fontSize = 12.sp) },
                                leadingContent = { Icon(Icons.Default.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onOpenComic(entry.comic) }
                                    .background(MaterialTheme.colorScheme.surface)
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Update `DriveContent`'s call site**

In the same file, change:

```kotlin
                1 -> DriveContent(state, driveUrlInput, onInputChange = { driveUrlInput = it }, onFetch = { viewModel.fetchDriveFolder(driveUrlInput) }, onOpenComic, onConnectDrive, onDisconnectDrive)
```

to:

```kotlin
                1 -> DriveContent(
                    state = state,
                    input = driveUrlInput,
                    onInputChange = { driveUrlInput = it },
                    onFetchLink = { viewModel.navigateToLinkedFolder(driveUrlInput) },
                    onNavigateFolder = { id, name -> viewModel.navigateDriveFolder(id, name) },
                    onNavigateUp = { index -> viewModel.navigateDriveUp(index) },
                    onRetry = { viewModel.retryDriveFolder() },
                    onOpenComic = onOpenComic,
                    onConnectDrive = onConnectDrive,
                    onDisconnectDrive = onDisconnectDrive
                )
```

- [ ] **Step 4: Build to verify it compiles**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
.\gradlew.bat compileDebugKotlin --no-daemon
```

Expected: `BUILD SUCCESSFUL`. This task has no new unit tests (Compose UI at this depth isn't unit-tested elsewhere in this project either — matches how `HomeScreen.kt`'s search/filter/grid-list UI from Epic 7 was verified manually, not via Compose UI tests) — Task 5's on-device pass is what verifies this visually and interactively.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/comicanything/reader/ui/home/HomeScreen.kt
git commit -m "feat: add Drive folder browser UI with breadcrumb navigation"
```

---

### Task 5: End-to-end manual verification on the emulator

**Files:** none (verification only), plus `PROJECT_TASKS.md`

**Interfaces:** none — this task exercises Tasks 1-4 together as a user would.

- [ ] **Step 1: Build and install the app**

```bash
export ANDROID_HOME="/c/Android/sdk"
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.8-hotspot" ./gradlew.bat assembleDebug --no-daemon
ADB="/c/Android/sdk/platform-tools/adb.exe"
"$ADB" install -r "app/build/outputs/apk/debug/app-debug.apk"
"$ADB" shell am force-stop com.comicanything.reader
"$ADB" shell am start -n com.comicanything.reader/.MainActivity
```

(If the emulator isn't running: `C:\Android\sdk\emulator\emulator.exe -avd comicanything_test -dns-server 8.8.8.8,8.8.4.4`, then wait for `adb shell getprop sys.boot_completed` to report `1`.)

- [ ] **Step 2: Attempt real Drive sign-in and document the outcome**

Navigate to the Google Drive tab, tap "Connect." Attempt the real OAuth flow through to completion.

**Expected, per this plan's Global Constraints:** this is likely to hit the same `ApiException: 8 (Unknown error)` blocker sub-project 1 already documented in `PROJECT_TASKS.md` under Epic 4, caused by this emulator's outdated Play Services build (`23.18.18`) relative to `play-services-auth:21.6.0`. If it does: document that this remains blocked on this specific emulator, exactly as sub-project 1's own verification note already does, and proceed to Step 3 to verify what CAN be confirmed without a live connection. If sign-in unexpectedly succeeds (e.g. Play Services was updated since sub-project 1's pass), continue through Steps 3-7 with a real connection and document genuinely-confirmed real-Drive behavior instead.

- [ ] **Step 3: Verify the not-connected and error/empty states without a live connection**

With Drive disconnected (or connection blocked per Step 2), confirm the Drive tab shows "Connect your Google Drive to browse it" in both the header row and the main content area, with no breadcrumb row, no paste-link field content assumed, and no crash. Confirm the format filter chips, search, and other tabs are entirely unaffected by being on this tab.

- [ ] **Step 4: Verify graceful failure when a folder ID doesn't resolve (using the paste-link shortcut)**

Paste an intentionally invalid folder ID (e.g. `not-a-real-folder-id`) into the "Jump to folder" field and tap the search icon. If genuinely connected (Step 2 succeeded): confirm a `driveError` message appears (not a crash, not a silent empty list) and the breadcrumb row shows the attempted folder. If not connected: confirm this correctly short-circuits to the "Connect your Google Drive to browse it" `driveError` message instead of attempting a network call (check `adb logcat` for the absence of any Drive API request attempt in this state).

- [ ] **Step 5 (only if Step 2 achieved a real connection): Verify real folder browsing and file opening**

Confirm the Drive tab auto-navigates to "My Drive" on connect. Tap into a real subfolder (if one exists in the account) — confirm the breadcrumb row grows, the entry list updates to that folder's contents, and folders consistently sort before files. Tap a breadcrumb segment to navigate back up — confirm the entry list reverts to that level and later breadcrumb segments are removed. Tap a real comic file (PDF/CBZ/EPUB/CBR, whichever is available) — confirm it downloads (brief spinner), then opens and renders its first page through the normal reader UI, identical in appearance to opening the same format locally.

- [ ] **Step 6 (only if Step 5's open succeeded): Verify the persistent offline cache**

Back out to the library, confirm the opened Drive comic reappears correctly in "Continue Reading." Force-stop the app (`adb shell am force-stop com.comicanything.reader`), optionally toggle the emulator into airplane mode to simulate offline, relaunch, and reopen the same Drive comic from Continue Reading — confirm it opens instantly with no spinner and no network attempt (check `adb logcat` for the absence of a new Drive API request), proving the cache-hit path works and survives a process restart. Then check the cache directory directly:

```bash
"$ADB" shell run-as com.comicanything.reader find files/drive_cache -type f
```

Confirm the downloaded file is present there (not under `cache/`), and confirm no `.part` file is left behind.

- [ ] **Step 7: Regression check**

Confirm nothing else broke: PDF/CBZ/EPUB/CBR local comics still open and page normally; library search/format-filter chips/grid-list toggle all still work; cover thumbnails still render for local PDF/CBZ; the Local Files tab is unaffected. A full-session `adb logcat` review shows no `FATAL EXCEPTION`/`AndroidRuntime`/`OutOfMemoryError`/ANR entries.

- [ ] **Step 8: Update `PROJECT_TASKS.md`**

In `PROJECT_TASKS.md`, under `## Epic 4 — Google Drive Integration Completion`, check off the two previously-unchecked bullets ("Implement on-demand streaming/caching..." and "Add error/empty states..."). Add a verification note in the same style/density as the existing Epic 4 note and as Epic 5/6/7's notes, honestly documenting exactly what Steps 2-7 above confirmed vs. what remained blocked by the pre-existing `ApiException: 8` environment issue — do not claim confirmation of anything Step 2 didn't actually achieve. If the epic is now fully verifiable end-to-end (Step 2 succeeded), update the epic's status marker from `🟨` to `✅`; if still partially blocked by the emulator issue, leave it `🟨` and update the existing blocked-items note to reflect that folder browsing/file download/offline cache are now code-complete and unit-tested, with only the live on-device connection itself still blocked.

Also add a new `- [ ] **Follow-up:**` bullet (matching the format of the two existing Epic 4 follow-up bullets) noting that Drive cover thumbnails are now unblocked (real `thumbnailLink` data flows through `DriveEntry.ComicFile.comic.coverUrl` as of this plan) but still not wired to any rendering path — `DriveContent`'s rows and `ComicCoverThumbnail` both still show only the static icon for Drive comics — a separate, cleanly scoped follow-up task.

- [ ] **Step 9: Commit**

```bash
git add PROJECT_TASKS.md
git commit -m "docs: mark Drive file access and browsing verification results"
```
