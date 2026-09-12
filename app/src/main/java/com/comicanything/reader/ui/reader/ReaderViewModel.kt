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
import com.comicanything.reader.data.model.ComicSource
import com.comicanything.reader.data.model.ReadingMode
import com.comicanything.reader.data.pagesource.PageBitmapCache
import com.comicanything.reader.data.pagesource.PageDecodeException
import com.comicanything.reader.data.pagesource.UnsupportedFormatException
import com.comicanything.reader.data.pagesource.createPageSource
import com.comicanything.reader.data.pagesource.decodeThumbnail
import com.comicanything.reader.data.repository.DriveConnectionHint
import com.comicanything.reader.data.repository.DriveConnectionRepository
import com.comicanything.reader.data.repository.DriveEntry
import com.comicanything.reader.data.repository.DriveFileCache
import com.comicanything.reader.data.repository.DriveSearchHit
import com.comicanything.reader.data.repository.GoogleDriveRepository
import com.comicanything.reader.data.repository.LocalEntry
import com.comicanything.reader.data.repository.LocalFileRepository
import com.comicanything.reader.data.repository.ReaderSettings
import com.comicanything.reader.data.repository.ReaderSettingsRepository
import com.comicanything.reader.data.repository.ReadingProgress
import com.comicanything.reader.data.repository.ReadingProgressRepository
import com.comicanything.reader.data.repository.SavedDriveLink
import com.comicanything.reader.data.repository.SavedDriveLinkRepository
import com.comicanything.reader.data.repository.resolveComicFile
import com.comicanything.reader.service.DefaultDriveDownloadCoordinator
import com.comicanything.reader.service.DriveDownloadCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicReference

data class DriveBreadcrumb(val folderId: String, val name: String)
data class LocalBreadcrumb(val path: String, val name: String)

data class ReaderUiState(
    val libraryComics: List<ComicItem> = emptyList(),
    val driveEntries: List<DriveEntry> = emptyList(),
    val driveBreadcrumbs: List<DriveBreadcrumb> = emptyList(),
    val driveError: String? = null,
    // null = no recursive search active for this folder (show driveEntries normally); non-null
    // (possibly empty) = a search is active and this is its result set, searched from the
    // current folder down through its subfolders.
    val driveSearchResults: List<DriveSearchHit>? = null,
    val isSearchingDriveTree: Boolean = false,
    // Set when the search itself couldn't run (e.g. an expired token, no connection) rather than
    // when it ran fine and simply found nothing -- those two cases must stay visibly different,
    // since silently reporting a failed search as "no matches" reads as the feature being broken.
    val driveSearchError: String? = null,
    // A second, independent Drive browsing session for the "Jump to Folder" tab -- deliberately
    // separate from driveBreadcrumbs/driveEntries above rather than sharing them, since jumping to
    // a pasted folder there must not disturb wherever the user was browsing in the main Google
    // Drive tab (and vice versa). Mirrors the drive* fields' shape and meaning field-for-field.
    val jumpToBreadcrumbs: List<DriveBreadcrumb> = emptyList(),
    val jumpToEntries: List<DriveEntry> = emptyList(),
    val jumpToError: String? = null,
    val isLoadingJumpTo: Boolean = false,
    val jumpToSearchResults: List<DriveSearchHit>? = null,
    val isSearchingJumpToTree: Boolean = false,
    val jumpToSearchError: String? = null,
    // Folders jumped to from the Jump to Folder tab, favorited or just recently used -- see
    // SavedDriveLinkRepository's doc comment. Independent of jumpToBreadcrumbs/jumpToEntries
    // (which track wherever the user currently is), this is the persisted list shown before any
    // link has been pasted yet.
    val savedDriveLinks: List<SavedDriveLink> = emptyList(),
    val localEntries: List<LocalEntry> = emptyList(),
    val localBreadcrumbs: List<LocalBreadcrumb> = emptyList(),
    val localSearchResults: List<LocalEntry>? = null,
    val isSearchingLocalTree: Boolean = false,
    val isLoadingLocalFolder: Boolean = false,
    val activeComic: ComicItem? = null,
    val currentPage: Int = 1,
    val totalPages: Int = 48,
    val readingMode: ReadingMode = ReadingMode.LTR,
    val filterMode: ColorFilterMode = ColorFilterMode.AMOLED_BLACK,
    val autoCropMargins: Boolean = true,
    // Home screen's Continue Reading (Recent) tab grid-vs-list toggle. Lives here alongside the
    // other simple global preferences (readingMode/filterMode/autoCropMargins) rather than as
    // local Compose state in HomeScreenState, specifically so it persists across app restarts the
    // same way they do.
    val isGridLayout: Boolean = true,
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
    // Increments on every successful (re-)authorization, including switching to the SAME
    // account. DriveContent's auto-navigate-to-root LaunchedEffect keys on this instead of
    // isDriveConnected -- that boolean doesn't change value when switching accounts while
    // already connected, so it would never re-fire and the UI would get stuck showing cleared
    // (empty) breadcrumbs/entries with no re-fetch.
    val driveConnectionVersion: Int = 0,
    val epubBook: EpubBook? = null,
    // Drive file ids currently mid-download (either a single-file "download for offline reading"
    // tap, or one being processed as part of a folder batch) -- drives the spinner shown in place
    // of the download/delete icon on that row.
    val driveDownloadingIds: Set<String> = emptySet(),
    // Drive file ids known to have a cached copy on disk (see DriveFileCache), maintained purely
    // by this session's own downloadDriveComic/downloadDriveFolder/deleteDriveComicCache/
    // clearDriveCache calls -- drives whether a row shows the download icon or the delete icon.
    // Deliberately NOT reconciled against the filesystem when a folder's entries load: a file
    // cached in an earlier session (or by a previous downloadDriveFolder run) just shows the
    // download icon again until re-downloaded, which is a much cheaper and simpler contract than
    // stat-ing every visible entry's cache file on every folder open.
    val driveCachedIds: Set<String> = emptySet(),
    // Folder ids currently running a "download everything directly in this folder" batch.
    val driveDownloadingFolderIds: Set<String> = emptySet()
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
    // Lazy supplier for the same reason as settingsRepo/driveFileCache below: a plain
    // `= SavedDriveLinkRepository(application)` default would construct eagerly at every
    // ReaderViewModel construction, touching Context.getApplicationContext() (throws "not mocked"
    // in plain JVM unit tests) even for the ~70 existing test cases that never touch saved Drive
    // links at all. Wrapping it as a supplier defers that construction to actual use.
    private val savedDriveLinkRepo: () -> SavedDriveLinkRepository = { SavedDriveLinkRepository(application) },
    private val thumbnailDecoder: suspend (ComicItem) -> Bitmap? = ::decodeThumbnail,
    private val epubExtractor: suspend (File, File) -> EpubBook? = ::extractEpub,
    private val epubCacheRoot: () -> File = { File(application.cacheDir, "epub_temp") },
    private val cbrCacheRoot: () -> File = { File(application.cacheDir, "cbr_temp") },
    // A plain (non-lambda) `DriveFileCache = DriveFileCache(File(application.filesDir, ...))`
    // default would construct eagerly at every ReaderViewModel construction (Kotlin evaluates
    // constructor parameter defaults unconditionally when the caller omits the argument),
    // immediately touching Context.getFilesDir() -- an Android stub-jar method that throws "not
    // mocked" in plain JVM unit tests, exactly like the progressRepo/connectionRepo hazard
    // documented in ReaderViewModelTest. Wrapping it as a `() -> DriveFileCache` supplier, matching
    // the existing epubCacheRoot/cbrCacheRoot pattern, defers that construction to actual use.
    private val driveFileCache: () -> DriveFileCache = { DriveFileCache(File(application.filesDir, "drive_cache")) },
    // Lazy supplier for the same reason as driveFileCache above: a plain
    // `= ReaderSettingsRepository(application)` default would construct eagerly at every
    // ReaderViewModel construction, touching Context.getApplicationContext() (throws "not
    // mocked" in plain JVM unit tests) even for the ~50 existing test cases that never touch
    // reader settings at all. Wrapping it as a supplier defers that construction to actual use.
    private val settingsRepo: () -> ReaderSettingsRepository = { ReaderSettingsRepository(application) },
    // Backing state for the token-push mechanism (see [updateDriveAccessToken]), boxed in an
    // AtomicReference rather than exposed as a plain `var` property. A plain
    // `private var pushedDriveAccessToken: String? = null` constructor parameter was tried first,
    // with `driveAccessToken`'s default reading it directly -- but a primary-constructor
    // parameter default that references an EARLIER parameter by name captures that parameter's
    // construction-time VALUE (a local in the constructor's initialization scope), not a live
    // read of the resulting property, so updateDriveAccessToken()'s later reassignment was
    // invisible to the captured lambda (confirmed via a failing test: pushedDriveAccessToken read
    // "real-token" directly while driveAccessToken() still returned null). Boxing the mutable
    // state in a stable object sidesteps this: `pushedDriveAccessToken` itself is a `val` (the
    // AtomicReference instance never changes, so its capture is unambiguous), and mutation
    // happens through `.set()`/`.get()` on that object's own internal state instead.
    private val pushedDriveAccessToken: AtomicReference<String?> = AtomicReference(null),
    // Defaults to reading `pushedDriveAccessToken` above rather than closing over anything
    // Activity-owned. MainActivity used to pass `driveAccessToken = { lastAccessToken }`, a
    // closure capturing `this@MainActivity` directly -- that kept a possibly-destroyed Activity
    // instance reachable from this retained ViewModel, and if the Activity WAS recreated, the
    // closure kept reading the OLD (dead) instance's field forever, going permanently stale.
    // MainActivity now instead PUSHES fresh tokens in via [updateDriveAccessToken] at the moment
    // it obtains them. The parameter itself is kept (rather than removed) since it's still useful
    // for direct test injection -- a test passing `driveAccessToken = { "token" }` or `{ null }`
    // explicitly overrides the whole lambda, bypassing `pushedDriveAccessToken` entirely.
    private val driveAccessToken: () -> String? = { pushedDriveAccessToken.get() },
    // The LOCAL short-circuit is duplicated here (matching resolveComicFile's own LOCAL check)
    // so that a LOCAL comic open never invokes `cacheProvider()` at all -- preserving the same
    // "never touches a lazy resource it doesn't need" guarantee cbrCacheRoot already has for
    // non-CBR formats. Calling resolveComicFile(comic, cacheProvider(), ...) unconditionally would
    // defeat driveFileCache's laziness, since Kotlin evaluates function arguments (including
    // cacheProvider()) before the callee runs, regardless of what branch resolveComicFile takes
    // internally.
    private val comicFileResolver: suspend (ComicItem, () -> DriveFileCache, () -> String?) -> File =
        { comic, cacheProvider, token ->
            if (comic.source == ComicSource.LOCAL) {
                File(comic.pathOrUrl)
            } else {
                resolveComicFile(comic, cacheProvider(), driveRepo::downloadFile, token)
            }
        },
    private val fetchDriveFolderContents: suspend (String, String) -> List<DriveEntry> = driveRepo::fetchFolderContents,
    // A plain `driveRepo::searchTree` bound reference doesn't work here: searchTree's full
    // signature carries two trailing parameters with defaults (maxDepth, fetchFolder), and Kotlin
    // won't bind a method reference to a narrower functional type by dropping them. Wrapping in an
    // explicit lambda sidesteps that -- the maxDepth/fetchFolder defaults still apply underneath.
    private val searchDriveFolderTree: suspend (String, String, String) -> List<DriveSearchHit> =
        { folderId, query, token -> driveRepo.searchTree(folderId, query, token) },
    private val searchLocalFolderTree: suspend (String, String) -> List<LocalEntry> = localRepo::searchTree,
    // Null (the default every existing test above still gets, since none of them pass this
    // parameter) means "build the default viewModelScope-backed coordinator below" -- that default
    // can't live directly in THIS parameter list since it needs `viewModelScope`, an extension
    // property on `this`, which isn't available yet while primary-constructor parameter defaults
    // are being evaluated (this@ReaderViewModel doesn't exist as a usable receiver until the
    // AndroidViewModel(application) superclass call below completes). MainActivity's factory
    // instead passes a real ServiceBoundDriveDownloadCoordinator here, which runs downloads on
    // DriveDownloadService's own scope (independent of this ViewModel/Activity) so they can keep
    // going -- with a visible progress notification -- after the app is backgrounded or this
    // ViewModel is destroyed.
    driveDownloadCoordinatorOverride: DriveDownloadCoordinator? = null
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val driveDownloadCoordinator: DriveDownloadCoordinator = driveDownloadCoordinatorOverride
        ?: DefaultDriveDownloadCoordinator(
            scope = viewModelScope,
            driveFileCache = driveFileCache,
            driveAccessToken = driveAccessToken,
            comicFileResolver = comicFileResolver,
            fetchFolderContents = fetchDriveFolderContents
        )

    init {
        // Mirrors driveDownloadCoordinator's own state into the matching driveDownloadingIds/
        // driveCachedIds/driveDownloadingFolderIds fields above -- kept as separate ReaderUiState
        // fields (rather than exposing DriveDownloadState directly) so HomeScreen's existing
        // contract doesn't change regardless of which coordinator is behind it.
        viewModelScope.launch {
            driveDownloadCoordinator.state.collect { s ->
                _uiState.value = _uiState.value.copy(
                    driveDownloadingIds = s.downloadingIds,
                    driveCachedIds = s.cachedIds,
                    driveDownloadingFolderIds = s.downloadingFolderIds
                )
            }
        }
    }

    private var pageCache: PageBitmapCache? = null
    private var debounceJob: Job? = null
    private var epubExtractedDir: File? = null
    private var driveSearchJob: Job? = null
    private var localSearchJob: Job? = null
    private var jumpToSearchJob: Job? = null

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
            _uiState.value = _uiState.value.copy(
                libraryComics = emptyList(),
                localEntries = emptyList(),
                localBreadcrumbs = emptyList()
            )
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

    fun navigateDriveFolder(folderId: String, name: String) {
        _uiState.value = _uiState.value.copy(
            driveBreadcrumbs = _uiState.value.driveBreadcrumbs + DriveBreadcrumb(folderId, name)
        )
        fetchCurrentDriveFolder()
    }

    fun navigateDriveUp(toIndex: Int) {
        val breadcrumbs = _uiState.value.driveBreadcrumbs
        if (toIndex !in breadcrumbs.indices) return
        _uiState.value = _uiState.value.copy(driveBreadcrumbs = breadcrumbs.take(toIndex + 1))
        fetchCurrentDriveFolder()
    }

    fun retryDriveFolder() {
        fetchCurrentDriveFolder()
    }

    private fun fetchCurrentDriveFolder() {
        val current = _uiState.value.driveBreadcrumbs.lastOrNull() ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingDrive = true, driveError = null)
            val token = driveAccessToken()
            if (token == null) {
                _uiState.value = _uiState.value.copy(
                    isLoadingDrive = false,
                    driveError = "Connect your Google Drive to browse it"
                )
                return@launch
            }
            try {
                val entries = fetchDriveFolderContents(current.folderId, token)
                _uiState.value = _uiState.value.copy(driveEntries = entries, isLoadingDrive = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Widened from `catch (e: DriveApiException)` as defense in depth, matching the
                // pattern already used in openComic/openEpubComic: GoogleDriveRepository's own
                // exception coverage was widened too (see its fetchFolderContents), but this is
                // the layer that actually prevents an uncaught exception inside
                // viewModelScope.launch from crashing the app if anything still slips through.
                _uiState.value = _uiState.value.copy(
                    isLoadingDrive = false,
                    driveError = e.message ?: "Couldn't load this folder"
                )
            }
        }
    }

    /** Downloads [comic] into the Drive cache ahead of time, without opening the reader. A no-op if it's already cached or already downloading. Delegates to [driveDownloadCoordinator], which does the actual work and reports back through the state mirrored in the `init` block above. */
    fun downloadDriveComic(comic: ComicItem) {
        driveDownloadCoordinator.enqueueComic(comic)
    }

    /** Removes [comicId]'s cached file, if any, so it no longer counts toward the Drive cache. Available any time, independent of read progress. */
    fun deleteDriveComicCache(comicId: String) {
        driveDownloadCoordinator.deleteCache(comicId)
    }

    /** Downloads every comic file directly inside [folderId] (not recursing into subfolders) sequentially, skipping ones already cached. A no-op if this folder's batch is already running. */
    fun downloadDriveFolder(folderId: String) {
        driveDownloadCoordinator.enqueueFolder(folderId)
    }

    /** Clears every Drive file currently cached on disk. */
    fun clearDriveCache() {
        driveDownloadCoordinator.clearCache()
    }

    /**
     * Recursively searches the CURRENT Drive folder and everything beneath it for a folder or
     * file name containing [query], replacing [ReaderUiState.driveSearchResults]. Superseding a
     * still-in-flight search (the user kept typing) cancels the old one -- callers are expected
     * to debounce keystrokes themselves before calling this, since a live Drive API call per
     * keystroke would be wasteful and slow.
     */
    fun searchDriveTree(query: String) {
        driveSearchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = _uiState.value.copy(driveSearchResults = null, driveSearchError = null, isSearchingDriveTree = false)
            return
        }
        val current = _uiState.value.driveBreadcrumbs.lastOrNull() ?: return
        driveSearchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearchingDriveTree = true, driveSearchError = null)
            val token = driveAccessToken()
            if (token == null) {
                _uiState.value = _uiState.value.copy(
                    isSearchingDriveTree = false,
                    driveSearchResults = null,
                    driveSearchError = "Not connected to Google Drive"
                )
                return@launch
            }
            try {
                val results = searchDriveFolderTree(current.folderId, trimmed, token)
                _uiState.value = _uiState.value.copy(driveSearchResults = results, isSearchingDriveTree = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSearchingDriveTree = false,
                    driveSearchResults = null,
                    driveSearchError = e.message ?: "Search failed -- try again"
                )
            }
        }
    }

    fun clearDriveSearch() {
        driveSearchJob?.cancel()
        _uiState.value = _uiState.value.copy(driveSearchResults = null, driveSearchError = null, isSearchingDriveTree = false)
    }

    /** Jumps straight to a folder found via [searchDriveTree], replacing the breadcrumb trail. */
    fun navigateDriveToBreadcrumbs(breadcrumbs: List<DriveBreadcrumb>) {
        clearDriveSearch()
        _uiState.value = _uiState.value.copy(driveBreadcrumbs = breadcrumbs)
        fetchCurrentDriveFolder()
    }

    // -- "Jump to Folder" tab: a second, independent Drive browsing session (see
    // ReaderUiState.jumpToBreadcrumbs' doc comment). Each function below mirrors its drive*
    // counterpart above field-for-field, operating on the jumpTo* state instead.

    /**
     * Starts a fresh browsing session at the pasted folder, discarding whatever breadcrumb trail
     * was there before -- pasting a new link is a deliberate "start over here" action, unlike
     * [navigateJumpToFolder] which descends from wherever the user already is.
     */
    fun navigateToLinkedFolderInJumpTab(folderUrlOrId: String) {
        val folderId = driveRepo.extractFolderId(folderUrlOrId)
        clearJumpToSearch()
        _uiState.value = _uiState.value.copy(jumpToBreadcrumbs = listOf(DriveBreadcrumb(folderId, folderId)))
        fetchCurrentJumpToFolder()
        // Recorded regardless of whether the fetch above ends up succeeding -- a saved link is
        // fundamentally "a folder the user pasted," and a transient load failure right now
        // shouldn't erase that from their recent list. Synchronous (runBlocking) for the same
        // reason as persistReaderSettings() -- see its comment: NonCancellable only protects
        // against viewModelScope itself being cancelled, not against the OS killing the whole
        // process, which real devices confirmed can happen fast enough that a background
        // coroutine never gets to run at all.
        runBlocking(ioDispatcher) {
            val repo = savedDriveLinkRepo()
            repo.recordUsed(folderId)
            _uiState.value = _uiState.value.copy(savedDriveLinks = repo.getAll())
        }
    }

    fun loadSavedDriveLinks() {
        viewModelScope.launch {
            // The suspending call is resolved to a local BEFORE building the .copy() -- calling
            // `_uiState.value.copy(savedDriveLinks = savedDriveLinkRepo().getAll())` directly reads
            // `_uiState.value` (the copy receiver) BEFORE the suspend argument runs, per Kotlin's
            // left-to-right evaluation order. If ANY other coroutine updates `_uiState.value` while
            // this call is suspended awaiting the DataStore read (confirmed on-device: this races
            // against onResume()'s permission check on cold start, since this is launched from
            // onCreate() before permission state is known), resuming here would overwrite that
            // update with the stale pre-suspension snapshot -- reintroducing the exact
            // "settings/permission silently revert" bug this session fixed elsewhere.
            val links = savedDriveLinkRepo().getAll()
            _uiState.value = _uiState.value.copy(savedDriveLinks = links)
        }
    }

    /** Stars or un-stars [folderId], creating a saved entry for it first if it doesn't have one yet (e.g. favoriting a folder immediately after jumping to it). [customName] is only applied when non-null -- un-starring an entry doesn't clear its name. Shared by both the Google Drive tab and the Jump to Folder tab -- favorites aren't scoped to whichever tab a folder was found through. */
    fun setFolderFavorite(folderId: String, isFavorite: Boolean, customName: String? = null) {
        // Synchronous -- see navigateToLinkedFolderInJumpTab's comment above.
        runBlocking(ioDispatcher) {
            val repo = savedDriveLinkRepo()
            repo.setFavorite(folderId, isFavorite, customName)
            _uiState.value = _uiState.value.copy(savedDriveLinks = repo.getAll())
        }
    }

    fun removeSavedDriveLink(folderId: String) {
        // Synchronous -- see navigateToLinkedFolderInJumpTab's comment above.
        runBlocking(ioDispatcher) {
            val repo = savedDriveLinkRepo()
            repo.remove(folderId)
            _uiState.value = _uiState.value.copy(savedDriveLinks = repo.getAll())
        }
    }

    fun navigateJumpToFolder(folderId: String, name: String) {
        _uiState.value = _uiState.value.copy(
            jumpToBreadcrumbs = _uiState.value.jumpToBreadcrumbs + DriveBreadcrumb(folderId, name)
        )
        fetchCurrentJumpToFolder()
    }

    fun navigateJumpToUp(toIndex: Int) {
        val breadcrumbs = _uiState.value.jumpToBreadcrumbs
        if (toIndex !in breadcrumbs.indices) return
        _uiState.value = _uiState.value.copy(jumpToBreadcrumbs = breadcrumbs.take(toIndex + 1))
        fetchCurrentJumpToFolder()
    }

    fun retryJumpToFolder() {
        fetchCurrentJumpToFolder()
    }

    private fun fetchCurrentJumpToFolder() {
        val current = _uiState.value.jumpToBreadcrumbs.lastOrNull() ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingJumpTo = true, jumpToError = null)
            val token = driveAccessToken()
            if (token == null) {
                _uiState.value = _uiState.value.copy(
                    isLoadingJumpTo = false,
                    jumpToError = "Connect your Google Drive to browse it"
                )
                return@launch
            }
            try {
                val entries = fetchDriveFolderContents(current.folderId, token)
                _uiState.value = _uiState.value.copy(jumpToEntries = entries, isLoadingJumpTo = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingJumpTo = false,
                    jumpToError = e.message ?: "Couldn't load this folder"
                )
            }
        }
    }

    fun searchJumpToTree(query: String) {
        jumpToSearchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = _uiState.value.copy(jumpToSearchResults = null, jumpToSearchError = null, isSearchingJumpToTree = false)
            return
        }
        val current = _uiState.value.jumpToBreadcrumbs.lastOrNull() ?: return
        jumpToSearchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearchingJumpToTree = true, jumpToSearchError = null)
            val token = driveAccessToken()
            if (token == null) {
                _uiState.value = _uiState.value.copy(
                    isSearchingJumpToTree = false,
                    jumpToSearchResults = null,
                    jumpToSearchError = "Not connected to Google Drive"
                )
                return@launch
            }
            try {
                val results = searchDriveFolderTree(current.folderId, trimmed, token)
                _uiState.value = _uiState.value.copy(jumpToSearchResults = results, isSearchingJumpToTree = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSearchingJumpToTree = false,
                    jumpToSearchResults = null,
                    jumpToSearchError = e.message ?: "Search failed -- try again"
                )
            }
        }
    }

    fun clearJumpToSearch() {
        jumpToSearchJob?.cancel()
        _uiState.value = _uiState.value.copy(jumpToSearchResults = null, jumpToSearchError = null, isSearchingJumpToTree = false)
    }

    /** Jumps straight to a folder found via [searchJumpToTree], replacing the breadcrumb trail. */
    fun navigateJumpToBreadcrumbs(breadcrumbs: List<DriveBreadcrumb>) {
        clearJumpToSearch()
        _uiState.value = _uiState.value.copy(jumpToBreadcrumbs = breadcrumbs)
        fetchCurrentJumpToFolder()
    }

    /** Returns the Jump to Folder tab to its starting state (the Favorites/Recent list) without touching the main Google Drive tab. */
    fun clearJumpToFolder() {
        clearJumpToSearch()
        _uiState.value = _uiState.value.copy(jumpToBreadcrumbs = emptyList(), jumpToEntries = emptyList(), jumpToError = null)
    }

    fun navigateLocalFolder(path: String, name: String) {
        _uiState.value = _uiState.value.copy(
            localBreadcrumbs = _uiState.value.localBreadcrumbs + LocalBreadcrumb(path, name)
        )
        fetchCurrentLocalFolder()
    }

    fun navigateLocalUp(toIndex: Int) {
        val breadcrumbs = _uiState.value.localBreadcrumbs
        if (toIndex !in breadcrumbs.indices) return
        _uiState.value = _uiState.value.copy(localBreadcrumbs = breadcrumbs.take(toIndex + 1))
        fetchCurrentLocalFolder()
    }

    private fun fetchCurrentLocalFolder() {
        val current = _uiState.value.localBreadcrumbs.lastOrNull() ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingLocalFolder = true)
            val entries = localRepo.listDirectory(current.path)
            _uiState.value = _uiState.value.copy(localEntries = entries, isLoadingLocalFolder = false)
        }
    }

    /**
     * Recursively searches the CURRENT local folder and everything beneath it for a folder or
     * file name containing [query], replacing [ReaderUiState.localSearchResults]. See
     * [searchDriveTree]'s doc for the debounce expectation -- a full recursive filesystem walk
     * per keystroke would be wasteful on a large storage tree.
     */
    fun searchLocalTree(query: String) {
        localSearchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = _uiState.value.copy(localSearchResults = null, isSearchingLocalTree = false)
            return
        }
        val current = _uiState.value.localBreadcrumbs.lastOrNull() ?: return
        localSearchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearchingLocalTree = true)
            val results = searchLocalFolderTree(current.path, trimmed)
            _uiState.value = _uiState.value.copy(localSearchResults = results, isSearchingLocalTree = false)
        }
    }

    fun clearLocalSearch() {
        localSearchJob?.cancel()
        _uiState.value = _uiState.value.copy(localSearchResults = null, isSearchingLocalTree = false)
    }

    /** Jumps straight to a folder found via [searchLocalTree], replacing the breadcrumb trail. */
    fun navigateLocalToBreadcrumbs(breadcrumbs: List<LocalBreadcrumb>) {
        clearLocalSearch()
        _uiState.value = _uiState.value.copy(localBreadcrumbs = breadcrumbs)
        fetchCurrentLocalFolder()
    }

    /**
     * Called by MainActivity every time it obtains a fresh Drive access token (interactive
     * consent success, silent re-check success, or explicit connect success), and with `null` on
     * disconnect. Replaces the old pull-based `driveAccessToken = { lastAccessToken }` closure
     * MainActivity used to pass at ViewModel construction, which captured the Activity instance
     * itself -- see [driveAccessToken]'s doc comment for the staleness/leak hazard that created.
     *
     * Also fixes the related cold-start race: `loadDriveConnectionState()` (a fast DataStore
     * read) and the slow Play Services silent-auth round-trip that actually produces a token run
     * concurrently on every onResume(); the fast one almost always finishes first, flipping
     * isDriveConnected = true and triggering an auto-navigate-to-root before a real token exists
     * yet, which previously left a stale "Connect your Google Drive to browse it" driveError on
     * screen for an already-connected user with no automatic retry. Re-fetching here when a real
     * token lands while that specific error is still showing closes that gap automatically.
     */
    fun updateDriveAccessToken(token: String?) {
        pushedDriveAccessToken.set(token)
        // DefaultDriveDownloadCoordinator (the default above) reads pushedDriveAccessToken itself,
        // so this is a no-op for it -- but ServiceBoundDriveDownloadCoordinator has no access to
        // that AtomicReference (it isn't in the same process-local object graph the Service
        // reconstructs its own coordinator from), so it needs the fresh token pushed explicitly.
        driveDownloadCoordinator.updateAccessToken(token)
        if (token != null && _uiState.value.driveError != null && _uiState.value.driveBreadcrumbs.isNotEmpty()) {
            fetchCurrentDriveFolder()
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
        // Clearing driveEntries/driveBreadcrumbs here matters for the account-switch path
        // (MainActivity.switchDriveAccount()): without it, browsing state from the PREVIOUS
        // account (a folder ID deep in someone else's Drive) would stick around and get
        // re-fetched against the NEW account's token, either erroring out or leaking a stale
        // view. A fresh connect already starts with empty breadcrumbs, so this is a no-op there.
        _uiState.value = _uiState.value.copy(
            isDriveConnected = true,
            driveAccountEmail = accountEmail,
            driveEntries = emptyList(),
            driveBreadcrumbs = emptyList(),
            driveError = null,
            driveConnectionVersion = _uiState.value.driveConnectionVersion + 1
        )
        // Synchronous -- see persistReaderSettings()'s comment: connecting Drive and then
        // immediately navigating away must not abandon the write mid-flight, and NonCancellable
        // alone doesn't guarantee that against a killed process.
        runBlocking(ioDispatcher) {
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
        _uiState.value = _uiState.value.copy(
            isDriveConnected = false,
            driveAccountEmail = null,
            driveEntries = emptyList(),
            driveBreadcrumbs = emptyList(),
            driveError = null
        )
        // Synchronous -- see persistReaderSettings()'s comment.
        runBlocking(ioDispatcher) {
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
                withTimeout(OPEN_TIMEOUT_MS) {
                    withContext(ioDispatcher) {
                        val file = comicFileResolver(comic, driveFileCache, driveAccessToken)
                        createPageSource(comic, cbrCacheRoot, file)
                    }
                }
            } catch (e: UnsupportedFormatException) {
                // A newer open may have superseded this one while the page source was being
                // created -- only surface this error if this open is still the active one, so a
                // stale failure can't stomp a newer comic's already-applied state.
                if (_uiState.value.activeComic?.id == comic.id) {
                    _uiState.value = _uiState.value.copy(pageLoadError = "This format isn't supported yet")
                }
                return@launch
            } catch (e: TimeoutCancellationException) {
                // TimeoutCancellationException is itself a CancellationException, so it must be
                // caught ahead of the blanket CancellationException rethrow below or it would be
                // silently swallowed as if a newer open had just superseded this one, leaving the
                // UI stuck on its loading spinner forever. Confirmed on-device: opening a large
                // (100+MB) PDF freshly downloaded from Google Drive could hang indefinitely with
                // no feedback -- this timeout turns that into a recoverable error instead. Note
                // the underlying blocking call this races against (native PdfRenderer construction
                // in particular) isn't itself interruptible, so a genuinely stuck attempt leaks
                // its worker thread rather than truly stopping; the timeout's job here is only to
                // stop the UI from waiting on it forever and let the user retry.
                if (_uiState.value.activeComic?.id == comic.id) {
                    _uiState.value = _uiState.value.copy(pageLoadError = "This is taking too long to open -- try again")
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
            val book = try {
                withContext(ioDispatcher) {
                    val file = comicFileResolver(comic, driveFileCache, driveAccessToken)
                    epubExtractor(file, extractionDir)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_uiState.value.activeComic?.id == comic.id) {
                    _uiState.value = _uiState.value.copy(pageLoadError = e.message ?: "Couldn't open this EPUB file")
                }
                return@launch
            }
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
        driveDownloadCoordinator.close()
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
        // pageCache is null both when no comic is open (Failed is correct -- nothing to show)
        // and, transiently, while openComic() is still resolving the file and building the page
        // source for a comic that IS open (activeComic already set). Treating that second case as
        // Loading instead of Failed keeps every page item's placeholder at a uniform height
        // (400dp) throughout the open sequence -- WebtoonReader's initial resume-to-page scroll
        // depends on that: if some items flash to the smaller Failed placeholder before the real
        // page source is ready, LazyColumn's "first visible item" drifts during that settling
        // window (confirmed on-device: reopening a comic parked at page 3/4 landed on page 1).
        val cache = pageCache
            ?: return if (_uiState.value.activeComic != null) PageLoadState.Loading else PageLoadState.Failed
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
        persistReaderSettings()
    }

    fun setFilterMode(mode: ColorFilterMode) {
        _uiState.value = _uiState.value.copy(filterMode = mode)
        persistReaderSettings()
    }

    fun toggleAutoCrop() {
        _uiState.value = _uiState.value.copy(autoCropMargins = !_uiState.value.autoCropMargins)
        persistReaderSettings()
    }

    fun toggleGridLayout() {
        _uiState.value = _uiState.value.copy(isGridLayout = !_uiState.value.isGridLayout)
        persistReaderSettings()
    }

    /**
     * Loads the persisted Quick Settings (reading mode, color filter, auto-crop, Recent tab
     * grid/list layout) and applies them to state. Called once from MainActivity.onCreate() --
     * these are simple global preferences, not permission- or connection-gated, so a single load
     * per app launch is enough (no need to re-check like Drive's connection state does).
     */
    fun loadReaderSettings() {
        viewModelScope.launch {
            val settings = settingsRepo().get()
            _uiState.value = _uiState.value.copy(
                readingMode = settings.readingMode,
                filterMode = settings.filterMode,
                autoCropMargins = settings.autoCropMargins,
                isGridLayout = settings.isGridLayout
            )
        }
    }

    // Deliberately synchronous (blocks the calling thread until the write is actually durable),
    // not a fire-and-forget `viewModelScope.launch`. A prior attempt used
    // `viewModelScope.launch(NonCancellable)`, reasoning that the risk was viewModelScope being
    // cancelled -- but that only protects against the coroutine's OWN scope going away; it does
    // nothing if the OS kills the whole process outright, which is exactly what happens on
    // confirmed reports: many Android builds (aggressive OEM task killers in particular, but also
    // plain force-stop) can SIGKILL an app within a moment of it being backgrounded/swiped away,
    // with no general guarantee that in-flight background coroutines get to finish first --
    // NonCancellable or not, a killed process runs no more Kotlin code at all. The only way to
    // truly guarantee this write survives is to make it happen INSIDE the same call stack as the
    // user's tap, before this function (and therefore the Compose click handler that invoked it)
    // ever returns control to the OS -- at which point the process is, by definition, still alive
    // and actively running (an app can't be mid-event-handler and already killed). The settings
    // file this writes is a few dozen bytes, so this blocks the main thread for on the order of a
    // millisecond -- imperceptible, and vastly cheaper than losing the user's choice entirely.
    private fun persistReaderSettings() {
        val state = _uiState.value
        runBlocking(ioDispatcher) {
            settingsRepo().save(ReaderSettings(state.readingMode, state.filterMode, state.autoCropMargins, state.isGridLayout))
        }
    }

    companion object {
        private const val THUMBNAIL_CACHE_SIZE = 60
        // Confirmed on-device: a large (100+MB, 150-200 page) PDF freshly downloaded from Google
        // Drive can legitimately take close to a minute to open on its first attempt in a
        // session (0% CPU throughout -- an I/O wait, not a slow decode), then opens in seconds on
        // any later attempt. 90s gives that real-but-slow case comfortable room rather than
        // racing it, while still guaranteeing the UI never waits silently forever.
        private const val OPEN_TIMEOUT_MS = 90_000L
    }
}
