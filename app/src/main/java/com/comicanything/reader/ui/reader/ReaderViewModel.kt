package com.comicanything.reader.ui.reader

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comicanything.reader.data.model.ColorFilterMode
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ReadingMode
import com.comicanything.reader.data.pagesource.PageBitmapCache
import com.comicanything.reader.data.pagesource.PageDecodeException
import com.comicanything.reader.data.pagesource.UnsupportedFormatException
import com.comicanything.reader.data.pagesource.createPageSource
import com.comicanything.reader.data.pagesource.decodeThumbnail
import com.comicanything.reader.data.repository.DriveConnectionHint
import com.comicanything.reader.data.repository.DriveConnectionRepository
import com.comicanything.reader.data.repository.GoogleDriveRepository
import com.comicanything.reader.data.repository.LocalFileRepository
import com.comicanything.reader.data.repository.ReadingProgress
import com.comicanything.reader.data.repository.ReadingProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ReaderUiState(
    val libraryComics: List<ComicItem> = emptyList(),
    val driveComics: List<ComicItem> = emptyList(),
    val activeComic: ComicItem? = null,
    val currentPage: Int = 1,
    val totalPages: Int = 48,
    val readingMode: ReadingMode = ReadingMode.LTR,
    val filterMode: ColorFilterMode = ColorFilterMode.AMOLED_BLACK,
    val autoCropMargins: Boolean = true,
    val isControlsVisible: Boolean = true,
    val isLoadingDrive: Boolean = false,
    val hasStoragePermission: Boolean = false,
    val isScanningLocal: Boolean = false,
    val currentPageBitmap: Bitmap? = null,
    val pageLoadError: String? = null,
    val isPageLoading: Boolean = false,
    val pageSourceGeneration: Int = 0,
    val isDriveConnected: Boolean = false,
    val driveAccountEmail: String? = null
)

sealed interface PageLoadState {
    data object Loading : PageLoadState
    data class Loaded(val bitmap: Bitmap) : PageLoadState
    data object Failed : PageLoadState
}

sealed interface CoverLoadState {
    data object Loading : CoverLoadState
    data class Loaded(val bitmap: Bitmap) : CoverLoadState
    data object Unavailable : CoverLoadState
}

class ReaderViewModel @JvmOverloads constructor(
    application: Application,
    private val localRepo: LocalFileRepository = LocalFileRepository(),
    private val driveRepo: GoogleDriveRepository = GoogleDriveRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val progressRepo: ReadingProgressRepository = ReadingProgressRepository(application),
    private val connectionRepo: DriveConnectionRepository = DriveConnectionRepository(application),
    private val thumbnailDecoder: suspend (ComicItem) -> Bitmap? = ::decodeThumbnail
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var pageCache: PageBitmapCache? = null
    private var debounceJob: Job? = null
    private val thumbnailCache = object : LinkedHashMap<String, Bitmap?>(THUMBNAIL_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>) =
            size > THUMBNAIL_CACHE_SIZE
    }
    private val thumbnailMutex = Mutex()

    fun setPermissionGranted(granted: Boolean) {
        val wasGranted = _uiState.value.hasStoragePermission
        _uiState.value = _uiState.value.copy(hasStoragePermission = granted)
        if (granted && !wasGranted) {
            loadLocalLibrary()
        } else if (!granted && wasGranted) {
            _uiState.value = _uiState.value.copy(libraryComics = emptyList())
        }
    }

    fun refreshLibrary() {
        if (_uiState.value.hasStoragePermission) {
            loadLocalLibrary()
        }
    }

    fun loadLocalLibrary() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanningLocal = true)
            val scanned = localRepo.scanStorageDirectories()
            val persisted = progressRepo.getAll()
            val merged = scanned.map { comic ->
                persisted[comic.id]?.let { progress ->
                    comic.apply {
                        currentPage = progress.currentPage
                        totalPages = progress.totalPages
                        progressPercentage = progress.progressPercentage
                        lastReadTimestamp = progress.lastReadTimestamp
                        isFavorite = progress.isFavorite
                    }
                } ?: comic
            }
            _uiState.value = _uiState.value.copy(libraryComics = merged, isScanningLocal = false)
        }
    }

    fun fetchDriveFolder(folderUrlOrId: String, apiKey: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingDrive = true)
            val items = driveRepo.fetchFolderContents(folderUrlOrId, apiKey)
            _uiState.value = _uiState.value.copy(driveComics = items, isLoadingDrive = false)
        }
    }

    fun loadDriveConnectionState() {
        viewModelScope.launch {
            val hint = connectionRepo.get()
            _uiState.value = _uiState.value.copy(
                isDriveConnected = hint.isConnected,
                driveAccountEmail = hint.accountEmail
            )
        }
    }

    fun onDriveAuthorized(accountEmail: String?) {
        _uiState.value = _uiState.value.copy(isDriveConnected = true, driveAccountEmail = accountEmail)
        viewModelScope.launch {
            connectionRepo.save(DriveConnectionHint(isConnected = true, accountEmail = accountEmail))
        }
    }

    fun onDriveAuthorizationFailed() {
        _uiState.value = _uiState.value.copy(isDriveConnected = false, driveAccountEmail = null)
    }

    fun onDriveSilentCheckSucceeded() {
        viewModelScope.launch {
            val hint = connectionRepo.get()
            if (hint.isConnected) {
                _uiState.value = _uiState.value.copy(isDriveConnected = true, driveAccountEmail = hint.accountEmail)
            }
            // If the persisted hint says disconnected, ignore this success. Play Services' grant is
            // still valid (clearToken() only clears the local token cache, it doesn't revoke the
            // grant server-side), so authorize() succeeding here does NOT mean the user wants to be
            // reconnected -- it just means Google still thinks the app has access. Silently
            // re-establishing "connected" from this signal would undo an explicit disconnectDrive()
            // on the very next app resume. Only an explicit Connect tap (onDriveAuthorized) may
            // establish a connected state; this method may only confirm one that already exists.
        }
    }

    fun disconnectDrive() {
        _uiState.value = _uiState.value.copy(isDriveConnected = false, driveAccountEmail = null)
        viewModelScope.launch {
            connectionRepo.save(DriveConnectionHint(isConnected = false, accountEmail = null))
        }
    }

    fun openComic(comic: ComicItem) {
        val previousCache = pageCache
        pageCache = null
        _uiState.value = _uiState.value.copy(
            activeComic = comic,
            currentPage = comic.currentPage,
            totalPages = if (comic.totalPages > 0) comic.totalPages else 48,
            isControlsVisible = true,
            currentPageBitmap = null,
            pageLoadError = null,
            pageSourceGeneration = _uiState.value.pageSourceGeneration + 1
        )
        viewModelScope.launch {
            withContext(NonCancellable) {
                previousCache?.close()
            }
            val source = try {
                withContext(ioDispatcher) { createPageSource(comic) }
            } catch (e: UnsupportedFormatException) {
                _uiState.value = _uiState.value.copy(pageLoadError = "This format isn't supported yet")
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(pageLoadError = e.message ?: "Failed to open comic")
                return@launch
            }
            val cache = PageBitmapCache(source)
            val pageCount = cache.pageCount
            if (pageCount <= 0) {
                cache.close()
                _uiState.value = _uiState.value.copy(pageLoadError = "This comic has no readable pages")
                return@launch
            }
            pageCache = cache
            val resumePage = _uiState.value.currentPage.coerceIn(1, pageCount)
            _uiState.value = _uiState.value.copy(
                totalPages = pageCount,
                currentPage = resumePage,
                pageSourceGeneration = _uiState.value.pageSourceGeneration + 1
            )
            comic.totalPages = pageCount
            comic.currentPage = resumePage
            comic.progressPercentage = resumePage.toFloat() / pageCount.toFloat()
            persistProgress(comic)
            loadPage(cache, resumePage)
        }
    }

    fun closeComic() {
        flushAndTeardown()
        _uiState.value = _uiState.value.copy(
            activeComic = null,
            currentPageBitmap = null,
            pageLoadError = null
        )
    }

    fun toggleFavorite(comic: ComicItem) {
        comic.isFavorite = !comic.isFavorite
        persistProgress(comic)
    }

    override fun onCleared() {
        super.onCleared()
        flushAndTeardown()
    }

    internal fun clearForTest() = onCleared()

    /**
     * Captures the active cache and comic, flushes (or cancels) any pending persistence, and
     * closes the cache. Shared by [closeComic] and [onCleared] since both need the same
     * capture-then-teardown sequence; [onCleared] additionally relies on the flush running under
     * [NonCancellable] since viewModelScope's backing job is already cancelled by the time
     * onCleared() is invoked (ViewModel.clear() cancels the scope before calling onCleared()).
     */
    private fun flushAndTeardown() {
        val cacheToClose = pageCache
        val comicToFlush = _uiState.value.activeComic
        pageCache = null
        if (comicToFlush != null) {
            persistProgress(comicToFlush)
        } else {
            debounceJob?.cancel()
        }
        if (cacheToClose != null) {
            viewModelScope.launch(NonCancellable) {
                cacheToClose.close()
            }
        }
    }

    fun setCurrentPageIndicator(page: Int): Int {
        val clamped = page.coerceIn(1, _uiState.value.totalPages)
        _uiState.value = _uiState.value.copy(currentPage = clamped)
        _uiState.value.activeComic?.let { comic ->
            comic.currentPage = clamped
            comic.progressPercentage = clamped.toFloat() / _uiState.value.totalPages.toFloat()
            comic.lastReadTimestamp = System.currentTimeMillis()
            schedulePersist(comic)
        }
        return clamped
    }

    fun setPage(page: Int) {
        val clamped = setCurrentPageIndicator(page)
        val cache = pageCache ?: return
        viewModelScope.launch {
            loadPage(cache, clamped)
        }
    }

    suspend fun loadPageBitmap(page: Int): PageLoadState {
        val cache = pageCache ?: return PageLoadState.Failed
        return try {
            PageLoadState.Loaded(cache.getPage(page))
        } catch (e: PageDecodeException) {
            PageLoadState.Failed
        }
    }

    suspend fun loadCoverThumbnail(comic: ComicItem): CoverLoadState {
        thumbnailMutex.withLock {
            if (thumbnailCache.containsKey(comic.id)) {
                val cached = thumbnailCache.getValue(comic.id)
                return if (cached != null) CoverLoadState.Loaded(cached) else CoverLoadState.Unavailable
            }
        }
        val bitmap = thumbnailDecoder(comic)
        thumbnailMutex.withLock { thumbnailCache[comic.id] = bitmap }
        return if (bitmap != null) CoverLoadState.Loaded(bitmap) else CoverLoadState.Unavailable
    }

    private suspend fun loadPage(cache: PageBitmapCache, page: Int) {
        _uiState.value = _uiState.value.copy(isPageLoading = true)
        try {
            val bitmap = cache.getPage(page)
            _uiState.value = _uiState.value.copy(
                currentPageBitmap = bitmap,
                pageLoadError = null,
                isPageLoading = false
            )
        } catch (e: PageDecodeException) {
            _uiState.value = _uiState.value.copy(
                currentPageBitmap = null,
                pageLoadError = e.message,
                isPageLoading = false
            )
        }
        cache.prefetch(listOf(page - 1, page + 1))
    }

    private fun ComicItem.toReadingProgress() = ReadingProgress(
        currentPage = currentPage,
        totalPages = totalPages,
        progressPercentage = progressPercentage,
        lastReadTimestamp = lastReadTimestamp,
        isFavorite = isFavorite
    )

    private fun persistProgress(comic: ComicItem) {
        debounceJob?.cancel()
        viewModelScope.launch(NonCancellable) {
            progressRepo.save(comic.id, comic.toReadingProgress())
        }
    }

    private fun schedulePersist(comic: ComicItem) {
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(1_500)
            progressRepo.save(comic.id, comic.toReadingProgress())
        }
    }

    fun toggleControls() {
        _uiState.value = _uiState.value.copy(isControlsVisible = !_uiState.value.isControlsVisible)
    }

    fun setReadingMode(mode: ReadingMode) {
        _uiState.value = _uiState.value.copy(readingMode = mode)
    }

    fun setFilterMode(mode: ColorFilterMode) {
        _uiState.value = _uiState.value.copy(filterMode = mode)
    }

    fun toggleAutoCrop() {
        _uiState.value = _uiState.value.copy(autoCropMargins = !_uiState.value.autoCropMargins)
    }

    companion object {
        private const val THUMBNAIL_CACHE_SIZE = 60
    }
}
