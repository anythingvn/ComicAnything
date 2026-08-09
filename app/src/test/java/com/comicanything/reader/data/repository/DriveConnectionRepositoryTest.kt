package com.comicanything.reader.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DriveConnectionRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.Unconfined),
            produceFile = { File(tempFolder.root, "drive-connection-${System.nanoTime()}.preferences_pb") }
        )
    }

    @Test
    fun `get returns a disconnected hint when nothing has been saved`() = runTest {
        val repo = DriveConnectionRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)

        val hint = repo.get()

        assertFalse(hint.isConnected)
        assertNull(hint.accountEmail)
    }

    @Test
    fun `save then get round-trips a connected hint`() = runTest {
        val repo = DriveConnectionRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)
        val saved = DriveConnectionHint(isConnected = true, accountEmail = "reader@example.com")

        repo.save(saved)
        val loaded = repo.get()

        assertEquals(saved, loaded)
    }

    @Test
    fun `save then get round-trips a disconnected hint with no email`() = runTest {
        val repo = DriveConnectionRepository(dataStore, ioDispatcher = Dispatchers.Unconfined)

        repo.save(DriveConnectionHint(isConnected = true, accountEmail = "reader@example.com"))
        repo.save(DriveConnectionHint(isConnected = false, accountEmail = null))
        val loaded = repo.get()

        assertFalse(loaded.isConnected)
        assertNull(loaded.accountEmail)
    }
}
