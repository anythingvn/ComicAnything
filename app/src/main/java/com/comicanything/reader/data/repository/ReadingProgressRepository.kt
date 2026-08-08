package com.comicanything.reader.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class ReadingProgress(
    val currentPage: Int,
    val totalPages: Int,
    val progressPercentage: Float,
    val lastReadTimestamp: Long,
    val isFavorite: Boolean
)

private val Context.readingProgressDataStore: DataStore<Preferences> by preferencesDataStore(name = "reading_progress")

class ReadingProgressRepository(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    // Production callers (ReaderViewModel) construct against a real Context; tests construct
    // directly against a temp-file-backed DataStore (see ReadingProgressRepositoryTest) without
    // needing a real Android Context in a plain JVM test. Context and DataStore<Preferences> are
    // distinct erased types, so this doesn't clash with the primary constructor above.
    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(context.readingProgressDataStore, ioDispatcher)

    suspend fun getAll(): Map<String, ReadingProgress> = withContext(ioDispatcher) {
        val json = dataStore.data.first()[PROGRESS_KEY] ?: return@withContext emptyMap()
        decode(json)
    }

    suspend fun save(comicId: String, progress: ReadingProgress) = withContext(ioDispatcher) {
        dataStore.edit { prefs ->
            val current = prefs[PROGRESS_KEY]?.let { decode(it) } ?: emptyMap()
            val updated = current + (comicId to progress)
            prefs[PROGRESS_KEY] = Gson().toJson(updated)
        }
    }

    private fun decode(json: String): Map<String, ReadingProgress> {
        val type = object : TypeToken<Map<String, ReadingProgress>>() {}.type
        return try {
            Gson().fromJson<Map<String, ReadingProgress>>(json, type) ?: emptyMap()
        } catch (e: JsonSyntaxException) {
            emptyMap()
        }
    }

    companion object {
        private val PROGRESS_KEY = stringPreferencesKey("reading_progress_json")
    }
}
