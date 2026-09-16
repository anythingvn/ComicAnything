package com.comicanything.reader.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

data class SavedDriveLink(
    val folderId: String,
    val customName: String?,
    val isFavorite: Boolean
)

private val Context.savedDriveLinksDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "saved_drive_links",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * Persists the user's favorited Drive folders (starred from the Jump to Folder tab or while
 * browsing). Only favorites are ever stored -- un-favoriting a folder drops it from storage
 * entirely rather than demoting it to some other tracked state.
 */
class SavedDriveLinkRepository(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    // Production callers (ReaderViewModel) construct against a real Context; tests construct
    // directly against a temp-file-backed DataStore, matching ReadingProgressRepository's pattern.
    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(context.savedDriveLinksDataStore, ioDispatcher)

    suspend fun getAll(): List<SavedDriveLink> = withContext(ioDispatcher) {
        try {
            val json = dataStore.data.first()[LINKS_KEY] ?: return@withContext emptyList()
            decode(json)
        } catch (e: IOException) {
            emptyList()
        }
    }

    /** Sets [folderId]'s favorite flag, creating the entry if it doesn't exist yet. A non-null [customName] replaces any existing name; null leaves it as-is. Setting [isFavorite] to false drops the entry from storage entirely -- see the class doc comment. */
    suspend fun setFavorite(folderId: String, isFavorite: Boolean, customName: String?) = withContext(ioDispatcher) {
        writeUpdated { current ->
            if (current.any { it.folderId == folderId }) {
                current.map {
                    if (it.folderId == folderId) it.copy(isFavorite = isFavorite, customName = customName ?: it.customName) else it
                }
            } else {
                current + SavedDriveLink(folderId, customName, isFavorite)
            }
        }
    }

    suspend fun remove(folderId: String) = withContext(ioDispatcher) {
        writeUpdated { current -> current.filterNot { it.folderId == folderId } }
    }

    private suspend fun writeUpdated(transform: (List<SavedDriveLink>) -> List<SavedDriveLink>) {
        try {
            dataStore.edit { prefs ->
                val current = prefs[LINKS_KEY]?.let { decode(it) } ?: emptyList()
                val updated = transform(current).filter { it.isFavorite }
                prefs[LINKS_KEY] = Gson().toJson(updated)
            }
        } catch (e: IOException) {
            // A failed write just means that write didn't happen -- never surface a
            // persistence failure to the UI.
        }
    }

    private fun decode(json: String): List<SavedDriveLink> {
        val type = object : TypeToken<List<SavedDriveLink>>() {}.type
        return try {
            Gson().fromJson<List<SavedDriveLink>>(json, type) ?: emptyList()
        } catch (e: JsonSyntaxException) {
            emptyList()
        }
    }

    companion object {
        private val LINKS_KEY = stringPreferencesKey("saved_drive_links_json")
    }
}
