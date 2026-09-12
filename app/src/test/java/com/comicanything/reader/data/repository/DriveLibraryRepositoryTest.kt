package com.comicanything.reader.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.comicanything.reader.data.model.ComicFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DriveLibraryRepositoryTest {

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
    fun `getAll returns empty map when nothing has been saved`() = runTest {
        val repo = DriveLibraryRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)

        val result = repo.getAll()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `save then getAll round-trips a single comic's identity`() = runTest {
        val repo = DriveLibraryRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)
        val entry = DriveLibraryEntry(
            title = "Kotaro V21",
            pathOrUrl = "https://www.googleapis.com/drive/v3/files/abc?alt=media",
            format = ComicFormat.PDF,
            coverUrl = "https://drive.google.com/thumbnail?id=abc"
        )

        repo.save("comic-1", entry)
        val result = repo.getAll()

        assertEquals(entry, result["comic-1"])
    }

    @Test
    fun `saving a second comic does not clobber the first`() = runTest {
        val repo = DriveLibraryRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)
        val entryA = DriveLibraryEntry("A", "url-a", ComicFormat.PDF, null)
        val entryB = DriveLibraryEntry("B", "url-b", ComicFormat.CBZ, "cover-b")

        repo.save("comic-a", entryA)
        repo.save("comic-b", entryB)
        val result = repo.getAll()

        assertEquals(2, result.size)
        assertEquals(entryA, result["comic-a"])
        assertEquals(entryB, result["comic-b"])
    }

    @Test
    fun `saving the same comic id again overwrites its previous entry`() = runTest {
        val repo = DriveLibraryRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)
        repo.save("comic-1", DriveLibraryEntry("Old Title", "old-url", ComicFormat.PDF, null))
        repo.save("comic-1", DriveLibraryEntry("New Title", "new-url", ComicFormat.CBZ, "cover"))

        val result = repo.getAll()

        assertEquals(1, result.size)
        assertEquals(DriveLibraryEntry("New Title", "new-url", ComicFormat.CBZ, "cover"), result["comic-1"])
    }

    @Test
    fun `getAll degrades to an empty map instead of throwing when the store file is corrupted`() = runTest {
        val corruptFile = File(tempFolder.root, "corrupt-${System.nanoTime()}.preferences_pb")
        corruptFile.writeBytes(byteArrayOf(-1, 0, 18, 52, 86, 120, 9, 9, 9))
        val corruptDataStore = PreferenceDataStoreFactory.create(
            produceFile = { corruptFile }
        )
        val repo = DriveLibraryRepository(corruptDataStore, ioDispatcher = Dispatchers.Unconfined)

        val result = repo.getAll()

        assertTrue(result.isEmpty())
    }
}
