package com.comicanything.reader.service

import com.comicanything.reader.MainDispatcherRule
import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import com.comicanything.reader.data.repository.DriveApiException
import com.comicanything.reader.data.repository.DriveEntry
import com.comicanything.reader.data.repository.DriveFileCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DriveDownloadCoordinatorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun coordinator(
        scope: CoroutineScope,
        driveFileCache: () -> DriveFileCache = { DriveFileCache(tempFolder.newFolder("cache-${System.nanoTime()}")) },
        driveAccessToken: () -> String? = { "token" },
        comicFileResolver: suspend (ComicItem, () -> DriveFileCache, () -> String?) -> File,
        fetchFolderContents: suspend (String, String) -> List<DriveEntry> = { _, _ -> emptyList() }
    ) = DefaultDriveDownloadCoordinator(scope, driveFileCache, driveAccessToken, comicFileResolver, fetchFolderContents)

    private fun comic(id: String, title: String = "Book") =
        ComicItem(id = id, title = title, pathOrUrl = "url", source = ComicSource.GOOGLE_DRIVE, format = ComicFormat.CBZ)

    // A coordinator built directly on `this` (the TestScope) would make its internal downloadJob a
    // genuine child of the test's own root job -- and since a bare SupervisorJob never completes on
    // its own even once every child finishes, runTest's end-of-test "did everything complete" check
    // flags it as leaked (UncompletedCoroutinesError) regardless of whether the test's assertions
    // passed. `backgroundScope` (built for exactly this "fire and forget, don't leak-check it"
    // case) turned out not to work either: forking a NEW SupervisorJob from backgroundScope's own
    // context and building a plain CoroutineScope around it produces a scope whose coroutines
    // silently never got driven by advanceUntilIdle() in practice. The fix mirrors how production
    // actually works: ReaderViewModel's default coordinator runs on viewModelScope, which is a
    // wholly INDEPENDENT CoroutineScope (its own root Job) that merely happens to share
    // Dispatchers.Main with whatever test dispatcher MainDispatcherRule installed -- runTest never
    // sees it as a child at all, so there's nothing to leak-check, and it's still driven because
    // advanceUntilIdle() operates on the shared TestCoroutineScheduler, not on scope identity. This
    // helper reproduces exactly that: a fresh, independent scope driven by the same testScheduler.
    private fun TestScope.testCoordinatorScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler))

    @Test
    fun `enqueueComic marks the comic downloading then cached`() = runTest {
        var resolverCalls = 0
        val c = coordinator(testCoordinatorScope(), comicFileResolver = { _, _, _ -> resolverCalls++; File(tempFolder.root, "fake") })

        c.enqueueComic(comic("d1"))
        advanceUntilIdle()

        assertTrue(c.state.value.cachedIds.contains("d1"))
        assertTrue(c.state.value.downloadingIds.isEmpty())
        assertEquals(1, resolverCalls)
    }

    @Test
    fun `enqueueComic is a no-op when already cached`() = runTest {
        var resolverCalls = 0
        val c = coordinator(testCoordinatorScope(), comicFileResolver = { _, _, _ -> resolverCalls++; File(tempFolder.root, "fake") })
        val d2 = comic("d2")

        c.enqueueComic(d2)
        advanceUntilIdle()
        c.enqueueComic(d2)
        advanceUntilIdle()

        assertEquals(1, resolverCalls)
    }

    @Test
    fun `enqueueComic leaves the comic uncached when resolution fails`() = runTest {
        val c = coordinator(testCoordinatorScope(), comicFileResolver = { _, _, _ -> throw DriveApiException("network error") })

        c.enqueueComic(comic("d3"))
        advanceUntilIdle()

        assertFalse(c.state.value.cachedIds.contains("d3"))
        assertTrue(c.state.value.downloadingIds.isEmpty())
    }

    @Test
    fun `deleteCache removes the cached file and its id from state`() = runTest {
        val cache = DriveFileCache(tempFolder.newFolder("drive-cache"))
        val c = coordinator(
            testCoordinatorScope(),
            driveFileCache = { cache },
            comicFileResolver = { item, driveCache, _ -> driveCache().download(item.id) { it.writeText("data") } }
        )

        c.enqueueComic(comic("d4"))
        advanceUntilIdle()
        assertTrue(cache.cachedFile("d4") != null)

        c.deleteCache("d4")

        assertNull(cache.cachedFile("d4"))
        assertFalse(c.state.value.cachedIds.contains("d4"))
    }

    @Test
    fun `enqueueFolder downloads only the comic files directly in that folder, skipping already-cached ones`() = runTest {
        val resolverCalls = mutableListOf<String>()
        val folderEntries = listOf(
            DriveEntry.Folder(id = "sub", name = "Subfolder"),
            DriveEntry.ComicFile(comic("f1", "One")),
            DriveEntry.ComicFile(comic("f2", "Two"))
        )
        val c = coordinator(
            testCoordinatorScope(),
            comicFileResolver = { item, _, _ -> resolverCalls.add(item.id); File(tempFolder.root, "fake") },
            fetchFolderContents = { _, _ -> folderEntries }
        )

        c.enqueueFolder("root")
        advanceUntilIdle()

        assertEquals(listOf("f1", "f2"), resolverCalls)
        assertEquals(setOf("f1", "f2"), c.state.value.cachedIds)
        assertTrue(c.state.value.downloadingFolderIds.isEmpty())
        assertEquals(0, c.state.value.batchTotal)
    }

    @Test
    fun `clearCache empties every cached file and clears cachedIds`() = runTest {
        val cache = DriveFileCache(tempFolder.newFolder("drive-cache-2"))
        val c = coordinator(
            testCoordinatorScope(),
            driveFileCache = { cache },
            comicFileResolver = { item, driveCache, _ -> driveCache().download(item.id) { it.writeText("data") } }
        )
        c.enqueueComic(comic("c1"))
        advanceUntilIdle()
        c.enqueueComic(comic("c2"))
        advanceUntilIdle()
        assertEquals(2, listOf(cache.cachedFile("c1"), cache.cachedFile("c2")).count { it != null })

        c.clearCache()

        assertNull(cache.cachedFile("c1"))
        assertNull(cache.cachedFile("c2"))
        assertTrue(c.state.value.cachedIds.isEmpty())
    }

    @Test
    fun `cancelAll stops in-flight downloads without touching unrelated coroutines on the shared scope`() = runTest {
        var unrelatedRan = false
        val sharedScope = testCoordinatorScope()
        val c = coordinator(
            sharedScope,
            comicFileResolver = { _, _, _ ->
                delay(1000)
                File(tempFolder.root, "fake")
            }
        )
        // Simulate other work sharing the same scope (e.g. viewModelScope's page loading) --
        // launched on the SAME scope instance passed to the coordinator above, so this is a true
        // sibling of downloadJob rather than unrelated-by-coincidence.
        sharedScope.launch { delay(2000); unrelatedRan = true }

        c.enqueueComic(comic("d5"))
        runCurrent()
        assertTrue(c.state.value.downloadingIds.contains("d5"))

        c.cancelAll()
        advanceUntilIdle()

        assertTrue(c.state.value.downloadingIds.isEmpty())
        assertFalse(c.state.value.cachedIds.contains("d5"))
        assertTrue("cancelAll must not cancel unrelated coroutines sharing the same scope", unrelatedRan)
    }
}
