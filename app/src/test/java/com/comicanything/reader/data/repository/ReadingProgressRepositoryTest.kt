package com.comicanything.reader.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReadingProgressRepositoryTest {

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
        val repo = ReadingProgressRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)

        val result = repo.getAll()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `save then getAll round-trips a single comic's progress`() = runTest {
        val repo = ReadingProgressRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)
        val progress = ReadingProgress(
            currentPage = 5,
            totalPages = 20,
            progressPercentage = 0.25f,
            lastReadTimestamp = 1700000000000L,
            isFavorite = true
        )

        repo.save("comic-1", progress)
        val result = repo.getAll()

        assertEquals(progress, result["comic-1"])
    }

    @Test
    fun `saving a second comic does not clobber the first`() = runTest {
        val repo = ReadingProgressRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)
        val progressA = ReadingProgress(1, 10, 0.1f, 1000L, false)
        val progressB = ReadingProgress(2, 20, 0.1f, 2000L, true)

        repo.save("comic-a", progressA)
        repo.save("comic-b", progressB)
        val result = repo.getAll()

        assertEquals(2, result.size)
        assertEquals(progressA, result["comic-a"])
        assertEquals(progressB, result["comic-b"])
    }

    @Test
    fun `saving the same comic id again overwrites its previous progress`() = runTest {
        val repo = ReadingProgressRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)
        repo.save("comic-1", ReadingProgress(1, 10, 0.1f, 1000L, false))
        repo.save("comic-1", ReadingProgress(5, 10, 0.5f, 2000L, true))

        val result = repo.getAll()

        assertEquals(1, result.size)
        assertEquals(ReadingProgress(5, 10, 0.5f, 2000L, true), result["comic-1"])
    }
}
