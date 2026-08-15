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
    val isFavorite: Boolean,
    val lastUsedTimestamp: Long
)

private val Context.savedDriveLinksDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "saved_drive_links",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * Persists the folders a user has jumped to from the Jump to Folder tab: every successful jump is
 * recorded as a "recent" entry (capped at [MAX_RECENTS], oldest dropped first), and any entry can
 * be starred as a favorite -- which exempts it from that cap, since favoriting is a deliberate
 * "keep this" action distinct from just having used it once.
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

    /**
     * Records that [folderId] was just jumped to: bumps its timestamp if already saved, otherwise
     * adds it as a new, non-favorite entry. Never touches an existing entry's favorite flag or
     * custom name.
     */
    suspend fun recordUsed(folderId: String) = withContext(ioDispatcher) {
        writeUpdated { current ->
            val now = System.currentTimeMillis()
            if (current.any { it.folderId == folderId }) {
                current.map { if (it.folderId == folderId) it.copy(lastUsedTimestamp = now) else it }
            } else {
                current + SavedDriveLink(folderId, customName = null, isFavorite = false, lastUsedTimestamp = now)
            }
        }
    }

    /** Sets [folderId]'s favorite flag, creating the entry if it doesn't exist yet (e.g. favoriting a folder that was just jumped to in the same call chain). A non-null [customName] replaces any existing name; null leaves it as-is. */
    suspend fun setFavorite(folderId: String, isFavorite: Boolean, customName: String?) = withContext(ioDispatcher) {
        writeUpdated { current ->
            if (current.any { it.folderId == folderId }) {
                current.map {
                    if (it.folderId == folderId) it.copy(isFavorite = isFavorite, customName = customName ?: it.customName) else it
                }
            } else {
                current + SavedDriveLink(folderId, customName, isFavorite, System.currentTimeMillis())
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
                val updated = transform(current)
                val favorites = updated.filter { it.isFavorite }
                val recents = updated.filter { !it.isFavorite }
                    .sortedByDescending { it.lastUsedTimestamp }
                    .take(MAX_RECENTS)
                prefs[LINKS_KEY] = Gson().toJson(favorites + recents)
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
        private const val MAX_RECENTS = 20
    }
}
