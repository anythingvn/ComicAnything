package com.comicanything.reader.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.comicanything.reader.data.model.ColorFilterMode
import com.comicanything.reader.data.model.ReadingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReaderSettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "reader-settings-${System.nanoTime()}.preferences_pb") }
        )
    }

    @Test
    fun `get returns defaults when nothing has been saved`() = runTest {
        val repo = ReaderSettingsRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)

        val settings = repo.get()

        assertEquals(ReaderSettings(), settings)
    }

    @Test
    fun `save then get round-trips non-default settings`() = runTest {
        val repo = ReaderSettingsRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)
        val saved = ReaderSettings(
            readingMode = ReadingMode.WEBTOON,
            filterMode = ColorFilterMode.SEPIA,
            autoCropMargins = false,
            isGridLayout = false
        )

        repo.save(saved)
        val loaded = repo.get()

        assertEquals(saved, loaded)
    }

    @Test
    fun `save overwrites a previous value`() = runTest {
        val repo = ReaderSettingsRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)

        repo.save(ReaderSettings(readingMode = ReadingMode.RTL, filterMode = ColorFilterMode.NIGHT, autoCropMargins = false, isGridLayout = false))
        repo.save(ReaderSettings(readingMode = ReadingMode.DUAL_SPREAD, filterMode = ColorFilterMode.ORIGINAL, autoCropMargins = true, isGridLayout = true))
        val loaded = repo.get()

        assertEquals(ReadingMode.DUAL_SPREAD, loaded.readingMode)
        assertEquals(ColorFilterMode.ORIGINAL, loaded.filterMode)
        assertEquals(true, loaded.autoCropMargins)
        assertEquals(true, loaded.isGridLayout)
    }
}
