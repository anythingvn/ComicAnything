package com.comicanything.reader.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SavedDriveLinkRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "test-${System.nanoTime()}.preferences_pb") }
        )
    }

    @Test
    fun `getAll returns empty list when nothing has been saved`() = runTest {
        val repo = SavedDriveLinkRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)

        assertTrue(repo.getAll().isEmpty())
    }

    @Test
    fun `recordUsed adds a new non-favorite entry`() = runTest {
        val repo = SavedDriveLinkRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)

        repo.recordUsed("folder-1")
        val result = repo.getAll()

        assertEquals(1, result.size)
        assertEquals("folder-1", result[0].folderId)
        assertEquals(false, result[0].isFavorite)
        assertNull(result[0].customName)
    }

    @Test
    fun `recordUsed on an existing entry bumps its timestamp without touching favorite or name`() = runTest {
        val repo = SavedDriveLinkRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)
        repo.setFavorite("folder-1", isFavorite = true, customName = "My Comics")

        repo.recordUsed("folder-1")
        val result = repo.getAll()

        assertEquals(1, result.size)
        assertEquals(true, result[0].isFavorite)
        assertEquals("My Comics", result[0].customName)
    }

    @Test
    fun `setFavorite creates the entry if it doesn't exist yet`() = runTest {
        val repo = SavedDriveLinkRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)

        repo.setFavorite("folder-1", isFavorite = true, customName = "Shared Manga")
        val result = repo.getAll()

        assertEquals(1, result.size)
        assertEquals("Shared Manga", result[0].customName)
        assertEquals(true, result[0].isFavorite)
    }

    @Test
    fun `setFavorite with a null customName leaves an existing name untouched`() = runTest {
        val repo = SavedDriveLinkRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)
        repo.setFavorite("folder-1", isFavorite = true, customName = "Original Name")

        repo.setFavorite("folder-1", isFavorite = false, customName = null)
        val result = repo.getAll()

        assertEquals("Original Name", result[0].customName)
        assertEquals(false, result[0].isFavorite)
    }

    @Test
    fun `remove deletes the entry`() = runTest {
        val repo = SavedDriveLinkRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)
        repo.recordUsed("folder-1")
        repo.recordUsed("folder-2")

        repo.remove("folder-1")
        val result = repo.getAll()

        assertEquals(1, result.size)
        assertEquals("folder-2", result[0].folderId)
    }

    @Test
    fun `non-favorite recents beyond the cap are dropped, oldest first, but favorites never are`() = runTest {
        val repo = SavedDriveLinkRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)
        repo.setFavorite("keep-forever", isFavorite = true, customName = null)
        // MAX_RECENTS is 20 -- add 25 more non-favorite entries so 5 of the oldest must be dropped.
        repeat(25) { i -> repo.recordUsed("recent-$i") }

        val result = repo.getAll()

        assertTrue(result.any { it.folderId == "keep-forever" })
        assertEquals(21, result.size) // 1 favorite + 20 most recent
        assertTrue(result.none { it.folderId == "recent-0" }) // the oldest of the 25 was dropped
        assertTrue(result.any { it.folderId == "recent-24" }) // the newest survived
    }

    @Test
    fun `getAll degrades to an empty list instead of throwing when the store file is corrupted`() = runTest {
        val corruptFile = File(tempFolder.root, "corrupt-${System.nanoTime()}.preferences_pb")
        corruptFile.writeBytes(byteArrayOf(-1, 0, 18, 52, 86, 120, 9, 9, 9))
        val corruptDataStore = PreferenceDataStoreFactory.create(
            produceFile = { corruptFile }
        )
        val repo = SavedDriveLinkRepository(corruptDataStore, ioDispatcher = Dispatchers.Unconfined)

        assertTrue(repo.getAll().isEmpty())
    }
}
