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
import com.comicanything.reader.data.model.ColorFilterMode
import com.comicanything.reader.data.model.ReadingMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

data class ReaderSettings(
    val readingMode: ReadingMode = ReadingMode.LTR,
    val filterMode: ColorFilterMode = ColorFilterMode.AMOLED_BLACK,
    val autoCropMargins: Boolean = true
)

private val Context.readerSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "reader_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class ReaderSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(context.readerSettingsDataStore, ioDispatcher)

    suspend fun get(): ReaderSettings = withContext(ioDispatcher) {
        try {
            val prefs = dataStore.data.first()
            val defaults = ReaderSettings()
            ReaderSettings(
                readingMode = prefs[READING_MODE_KEY]?.let { runCatching { ReadingMode.valueOf(it) }.getOrNull() }
                    ?: defaults.readingMode,
                filterMode = prefs[FILTER_MODE_KEY]?.let { runCatching { ColorFilterMode.valueOf(it) }.getOrNull() }
                    ?: defaults.filterMode,
                autoCropMargins = prefs[AUTO_CROP_KEY] ?: defaults.autoCropMargins
            )
        } catch (e: IOException) {
            ReaderSettings()
        }
    }

    suspend fun save(settings: ReaderSettings) = withContext(ioDispatcher) {
        try {
            dataStore.edit { prefs ->
                prefs[READING_MODE_KEY] = settings.readingMode.name
                prefs[FILTER_MODE_KEY] = settings.filterMode.name
                prefs[AUTO_CROP_KEY] = settings.autoCropMargins
            }
        } catch (e: IOException) {
            // Matches DriveConnectionRepository/ReadingProgressRepository's posture: a failed
            // write just means this preference didn't persist, not a crash.
        }
    }

    companion object {
        private val READING_MODE_KEY = stringPreferencesKey("reading_mode")
        private val FILTER_MODE_KEY = stringPreferencesKey("filter_mode")
        private val AUTO_CROP_KEY = booleanPreferencesKey("auto_crop_margins")
    }
}
