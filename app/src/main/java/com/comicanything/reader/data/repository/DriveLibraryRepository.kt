package com.comicanything.reader.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.comicanything.reader.data.model.ComicFormat
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Just enough of a Drive ComicItem to redisplay it later -- title/format/cover for the UI, and
 * pathOrUrl to resolve (stream or re-download) it again. Deliberately excludes progress fields
 * (currentPage, totalPages, etc.); those already live in [ReadingProgressRepository] and are
 * merged back in by ReaderViewModel when rebuilding the library list.
 */
data class DriveLibraryEntry(
    val title: String,
    val pathOrUrl: String,
    val format: ComicFormat,
    val coverUrl: String?
)

private val Context.driveLibraryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "drive_library",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * Remembers the identity of every Google Drive comic that's actually been opened, keyed by comic
 * id. Local comics don't need this -- they're rediscovered every time by scanning device storage
 * -- but a Drive comic's title/format/cover has nowhere else to live once its Drive folder isn't
 * being browsed anymore, which otherwise made it silently vanish from Recent even after being
 * read and having its progress saved.
 */
class DriveLibraryRepository(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    // Production callers (ReaderViewModel) construct against a real Context; tests construct
    // directly against a temp-file-backed DataStore (see DriveLibraryRepositoryTest) without
    // needing a real Android Context in a plain JVM test.
    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(context.driveLibraryDataStore, ioDispatcher)

    suspend fun getAll(): Map<String, DriveLibraryEntry> = withContext(ioDispatcher) {
        try {
            val json = dataStore.data.first()[ENTRIES_KEY] ?: return@withContext emptyMap()
            decode(json)
        } catch (e: IOException) {
            emptyMap()
        }
    }

    suspend fun save(comicId: String, entry: DriveLibraryEntry) = withContext(ioDispatcher) {
        try {
            dataStore.edit { prefs ->
                val current = prefs[ENTRIES_KEY]?.let { decode(it) } ?: emptyMap()
                val updated = current + (comicId to entry)
                prefs[ENTRIES_KEY] = Gson().toJson(updated)
            }
            Unit
        } catch (e: IOException) {
            // A failed write just means that write didn't happen -- never surface a
            // persistence failure to the UI.
        }
    }

    private fun decode(json: String): Map<String, DriveLibraryEntry> {
        val type = object : TypeToken<Map<String, DriveLibraryEntry>>() {}.type
        return try {
            Gson().fromJson<Map<String, DriveLibraryEntry>>(json, type) ?: emptyMap()
        } catch (e: JsonSyntaxException) {
            emptyMap()
        }
    }

    companion object {
        private val ENTRIES_KEY = stringPreferencesKey("drive_library_json")
    }
}
