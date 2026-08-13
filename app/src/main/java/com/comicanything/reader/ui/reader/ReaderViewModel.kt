package com.comicanything.reader.ui.reader

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comicanything.reader.data.epub.EpubBook
import com.comicanything.reader.data.epub.extractEpub
import com.comicanything.reader.data.model.ColorFilterMode
import com.comicanything.reader.data.model.ComicFormat
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
import java.io.File

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
    val driveAccountEmail: String? = null,
    val epubBook: EpubBook? = null
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
    private val thumbnailDecoder: suspend (ComicItem) -> Bitmap? = ::decodeThumbnail,
    private val epubExtractor: suspend (File, File) -> EpubBook? = ::extractEpub,
    private val epubCacheRoot: () -> File = { File(application.cacheDir, "epub_temp") },
    private val cbrCacheRoot: () -> File = { File(application.cacheDir, "cbr_temp") }
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var pageCache: PageBitmapCache? = null
    private var debounceJob: Job? = null
    private var epubExtractedDir: File? = null

    /**
     * Tracks the coroutine currently performing an `openComic`/`openEpubComic` open, so that
     * starting a new open can cancel any still-in-flight previous one. Without this, opening
     * comic A (slow extraction/decode), backing out, then quickly opening comic B could let A's
     * coroutine finish later and unconditionally overwrite B's already-applied state -- and for
     * EPUB specifically, two overlapping opens of the *same* comic id could race
     * `extractionDir.deleteRecursively()` against a still-in-progress extraction into that same
     * path. Cancellation alone isn't guaranteed to land before every suspension point, so result
     * application is additionally guarded by an `activeComic?.id` check (see [openComic] and
     * [openEpubComic]) as belt-and-suspenders.
     */
    private var activeOpenJob: Job? = null
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
        // Temporary stub for Task 1 (GoogleDriveRepository's new Bearer-auth signature) --
        // Task 3 of the Drive file access plan replaces this function entirely with
        // navigateDriveFolder/navigateDriveUp/retryDriveFolder/navigateToLinkedFolder.
        _uiState.value = _uiState.value.copy(isLoadingDrive = false)
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
        activeOpenJob?.cancel()
        if (comic.format == ComicFormat.EPUB) {
            openEpubComic(comic)
            return
        }
        val (previousCache, previousExtractedDir) = capturePreviousComicResources()
        _uiState.value = _uiState.value.copy(
            activeComic = comic,
            currentPage = comic.currentPage,
            totalPages = if (comic.totalPages > 0) comic.totalPages else 48,
            isControlsVisible = true,
            currentPageBitmap = null,
            pageLoadError = null,
            epubBook = null,
            pageSourceGeneration = _uiState.value.pageSourceGeneration + 1
        )
        activeOpenJob = viewModelScope.launch {
            closePreviousComicResources(previousCache, previousExtractedDir)
            val source = try {
                withContext(ioDispatcher) { createPageSource(comic, cbrCacheRoot) }
            } catch (e: UnsupportedFormatException) {
                // A newer open may have superseded this one while the page source was being
                // created -- only surface this error if this open is still the active one, so a
                // stale failure can't stomp a newer comic's already-applied state.
                if (_uiState.value.activeComic?.id == comic.id) {
                    _uiState.value = _uiState.value.copy(pageLoadError = "This format isn't supported yet")
                }
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_uiState.value.activeComic?.id == comic.id) {
                    _uiState.value = _uiState.value.copy(pageLoadError = e.message ?: "Failed to open comic")
                }
                return@launch
            }
            val cache = PageBitmapCache(source)
            val pageCount = cache.pageCount
            if (pageCount <= 0) {
                cache.close()
                if (_uiState.value.activeComic?.id == comic.id) {
                    _uiState.value = _uiState.value.copy(pageLoadError = "This comic has no readable pages")
                }
                return@launch
            }
            if (_uiState.value.activeComic?.id != comic.id) {
                // A newer open superseded this one while the page source was being created --
                // discard this result instead of assigning a cache nothing will reference.
                cache.close()
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

    /**
     * Captures whichever of [pageCache] (from a previously-open PDF/CBZ) or [epubExtractedDir]
     * (from a previously-open EPUB) is currently active -- regardless of which format is about to
     * be opened next -- and clears both fields immediately so the new open can proceed without
     * racing the old resource's teardown. At most one of the two will ever be non-null in
     * practice (each open path clears the other's field), but capturing both unconditionally here
     * is what makes cross-format switches (PDF/CBZ -> EPUB or EPUB -> PDF/CBZ) safe: without this,
     * only the field matching the *new* format's own open path got captured/cleared, silently
     * leaking the *other* format's resource (an open [PageBitmapCache] or an extracted directory
     * on disk) for as long as the new comic stayed open.
     */
    private fun capturePreviousComicResources(): Pair<PageBitmapCache?, File?> {
        val previousCache = pageCache
        val previousExtractedDir = epubExtractedDir
        pageCache = null
        epubExtractedDir = null
        return previousCache to previousExtractedDir
    }

    /**
     * Closes/deletes whatever [capturePreviousComicResources] captured, under [NonCancellable] so
     * the cleanup always completes even if this coroutine is cancelled by a subsequent open.
     */
    private suspend fun closePreviousComicResources(previousCache: PageBitmapCache?, previousExtractedDir: File?) {
        withContext(ioDispatcher + NonCancellable) {
            previousCache?.close()
            previousExtractedDir?.let { runCatching { it.deleteRecursively() } }
        }
    }

    private fun openEpubComic(comic: ComicItem) {
        activeOpenJob?.cancel()
        val (previousCache, previousExtractedDir) = capturePreviousComicResources()
        _uiState.value = _uiState.value.copy(
            activeComic = comic,
            isControlsVisible = true,
            pageLoadError = null,
            epubBook = null,
            currentPageBitmap = null,
            pageSourceGeneration = _uiState.value.pageSourceGeneration + 1
        )
        activeOpenJob = viewModelScope.launch {
            closePreviousComicResources(previousCache, previousExtractedDir)
            val extractionDir = File(epubCacheRoot(), comic.id)
            val book = withContext(ioDispatcher) { epubExtractor(File(comic.pathOrUrl), extractionDir) }
            if (_uiState.value.activeComic?.id != comic.id) {
                // A newer open superseded this one while extraction was in flight -- discard
                // this result and clean up its directory rather than applying stale state.
                book?.let { runCatching { it.extractedDir.deleteRecursively() } }
                return@launch
            }
            if (book == null) {
                _uiState.value = _uiState.value.copy(pageLoadError = "Couldn't open this EPUB file")
                return@launch
            }
            epubExtractedDir = book.extractedDir
            _uiState.value = _uiState.value.copy(epubBook = book)
        }
    }

    fun closeComic() {
        flushAndTeardown()
        _uiState.value = _uiState.value.copy(
            activeComic = null,
            currentPageBitmap = null,
            pageLoadError = null,
            epubBook = null
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
        activeOpenJob?.cancel()
        val cacheToClose = pageCache
        val extractedDirToClean = epubExtractedDir
        val comicToFlush = _uiState.value.activeComic
        pageCache = null
        epubExtractedDir = null
        if (comicToFlush != null) {
            persistProgress(comicToFlush)
        } else {
            debounceJob?.cancel()
        }
        if (cacheToClose != null) {
            viewModelScope.launch(ioDispatcher + NonCancellable) {
                cacheToClose.close()
            }
        }
        if (extractedDirToClean != null) {
            viewModelScope.launch(ioDispatcher + NonCancellable) {
                runCatching { extractedDirToClean.deleteRecursively() }
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

    fun setEpubScrollProgress(percentage: Float) {
        val clamped = percentage.coerceIn(0f, 1f)
        _uiState.value.activeComic?.let { comic ->
            comic.progressPercentage = clamped
            comic.lastReadTimestamp = System.currentTimeMillis()
            schedulePersist(comic)
        }
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
