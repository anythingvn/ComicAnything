package com.comicanything.reader.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

data class DriveConnectionHint(
    val isConnected: Boolean,
    val accountEmail: String?
)

private val Context.driveConnectionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "drive_connection",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class DriveConnectionRepository(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(context.driveConnectionDataStore, ioDispatcher)

    suspend fun get(): DriveConnectionHint = withContext(ioDispatcher) {
        try {
            val prefs = dataStore.data.first()
            DriveConnectionHint(
                isConnected = prefs[IS_CONNECTED_KEY] ?: false,
                accountEmail = prefs[ACCOUNT_EMAIL_KEY]
            )
        } catch (e: IOException) {
            DriveConnectionHint(isConnected = false, accountEmail = null)
        }
    }

    suspend fun save(hint: DriveConnectionHint) = withContext(ioDispatcher) {
        try {
            dataStore.edit { prefs ->
                prefs[IS_CONNECTED_KEY] = hint.isConnected
                if (hint.accountEmail != null) {
                    prefs[ACCOUNT_EMAIL_KEY] = hint.accountEmail
                } else {
                    prefs.remove(ACCOUNT_EMAIL_KEY)
                }
            }
        } catch (e: IOException) {
            // Matches ReadingProgressRepository's posture: a failed write just means this
            // hint didn't persist, not a crash. The UI already reflects the live in-memory
            // state regardless of whether the disk write succeeds.
        }
    }

    companion object {
        private val IS_CONNECTED_KEY = booleanPreferencesKey("is_connected")
        private val ACCOUNT_EMAIL_KEY = stringPreferencesKey("account_email")
    }
}
