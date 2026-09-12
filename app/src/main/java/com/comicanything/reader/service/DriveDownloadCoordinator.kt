package com.comicanything.reader.service

import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.repository.DriveEntry
import com.comicanything.reader.data.repository.DriveFileCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Snapshot of everything in-flight Drive downloads need to report -- both to the UI
 * (downloadingIds/cachedIds/downloadingFolderIds mirror ReaderUiState's driveDownloadingIds/
 * driveCachedIds/driveDownloadingFolderIds field-for-field) and to a hosting foreground
 * notification (currentLabel/batchTotal/batchCompleted).
 */
data class DriveDownloadState(
    val downloadingIds: Set<String> = emptySet(),
    val cachedIds: Set<String> = emptySet(),
    val downloadingFolderIds: Set<String> = emptySet(),
    // Title of whichever comic most recently started downloading -- lets a notification show
    // "Downloading -- <name>" without needing to look anything up by id.
    val currentLabel: String? = null,
    // 0 outside of an enqueueFolder batch. While one is running, batchTotal is that folder's
    // comic-file count and batchCompleted counts finished files (success or failure) so a
    // notification can show "3 of 12" progress.
    val batchTotal: Int = 0,
    val batchCompleted: Int = 0
)

interface DriveDownloadCoordinator {
    val state: StateFlow<DriveDownloadState>
    fun enqueueComic(comic: ComicItem)
    fun deleteCache(comicId: String)
    fun enqueueFolder(folderId: String)
    fun clearCache()
    fun cancelAll()
    /** No-op for [DefaultDriveDownloadCoordinator], which reads its token lazily instead; overridden by ServiceBoundDriveDownloadCoordinator, which has no access to that lazy supplier. */
    fun updateAccessToken(token: String?) {}
    /** No-op for [DefaultDriveDownloadCoordinator]; overridden by ServiceBoundDriveDownloadCoordinator to unbind from the service and stop its own state-mirroring coroutine. */
    fun close() {}
}

/**
 * Runs Drive downloads and tracks their progress in [state]. This is the same queueing/caching
 * logic ReaderViewModel used to run inline in viewModelScope, lifted out here (Android-framework
 * free, so it stays unit-testable exactly like the rest of this codebase) so it can ALSO be
 * hosted by DriveDownloadService -- a foreground service whose own scope outlives the Activity/
 * ViewModel, which is what lets a download survive the app being backgrounded or its process
 * killed.
 *
 * Deliberately launches its downloads through a private SupervisorJob rooted in [scope] rather
 * than [scope] directly: [cancelAll] cancels that job's children, and ReaderViewModel's default
 * instance is built on viewModelScope -- a scope shared with unrelated work (page loading, Drive
 * tree search, etc.) that must never be touched by a download cancellation.
 */
class DefaultDriveDownloadCoordinator(
    scope: CoroutineScope,
    private val driveFileCache: () -> DriveFileCache,
    private val driveAccessToken: () -> String?,
    private val comicFileResolver: suspend (ComicItem, () -> DriveFileCache, () -> String?) -> File,
    private val fetchFolderContents: suspend (String, String) -> List<DriveEntry>
) : DriveDownloadCoordinator {

    private val downloadJob = SupervisorJob(scope.coroutineContext[Job])
    private val downloadScope = CoroutineScope(scope.coroutineContext + downloadJob)

    private val _state = MutableStateFlow(DriveDownloadState())
    override val state: StateFlow<DriveDownloadState> = _state.asStateFlow()

    override fun enqueueComic(comic: ComicItem) {
        if (comic.id in _state.value.downloadingIds || comic.id in _state.value.cachedIds) return
        downloadScope.launch { downloadOne(comic) }
    }

    /** Shared by [enqueueComic] and [enqueueFolder]'s per-file loop. */
    private suspend fun downloadOne(comic: ComicItem) {
        if (comic.id in _state.value.cachedIds) return
        _state.value = _state.value.copy(
            downloadingIds = _state.value.downloadingIds + comic.id,
            currentLabel = comic.title
        )
        try {
            comicFileResolver(comic, driveFileCache, driveAccessToken)
            _state.value = _state.value.copy(cachedIds = _state.value.cachedIds + comic.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Leave it uncached -- the row's download icon reappears so the user can retry.
        } finally {
            _state.value = _state.value.copy(downloadingIds = _state.value.downloadingIds - comic.id)
        }
    }

    override fun deleteCache(comicId: String) {
        driveFileCache().cachedFile(comicId)?.delete()
        _state.value = _state.value.copy(cachedIds = _state.value.cachedIds - comicId)
    }

    override fun enqueueFolder(folderId: String) {
        if (folderId in _state.value.downloadingFolderIds) return
        _state.value = _state.value.copy(downloadingFolderIds = _state.value.downloadingFolderIds + folderId)
        downloadScope.launch {
            try {
                val token = driveAccessToken() ?: return@launch
                val comics = fetchFolderContents(folderId, token).filterIsInstance<DriveEntry.ComicFile>().map { it.comic }
                _state.value = _state.value.copy(batchTotal = comics.size, batchCompleted = 0)
                comics.forEach { comic ->
                    downloadOne(comic)
                    _state.value = _state.value.copy(batchCompleted = _state.value.batchCompleted + 1)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Couldn't even list the folder's contents -- nothing to download. Per-file
                // failures inside downloadOne are already handled without aborting the rest of
                // the batch, so this only guards the initial fetchFolderContents call.
            } finally {
                _state.value = _state.value.copy(
                    downloadingFolderIds = _state.value.downloadingFolderIds - folderId,
                    batchTotal = 0,
                    batchCompleted = 0
                )
            }
        }
    }

    override fun clearCache() {
        driveFileCache().clearAll()
        _state.value = _state.value.copy(cachedIds = emptySet())
    }

    /** Stops every download this coordinator currently has in flight; already-cached files are unaffected. */
    override fun cancelAll() {
        downloadJob.cancelChildren()
    }
}
