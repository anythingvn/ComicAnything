package com.comicanything.reader.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.repository.DriveEntry
import com.comicanything.reader.data.repository.DriveSearchHit
import com.comicanything.reader.data.repository.LocalEntry
import com.comicanything.reader.data.repository.LocalFileRepository
import com.comicanything.reader.data.repository.SavedDriveLink
import com.comicanything.reader.ui.reader.CoverLoadState
import com.comicanything.reader.ui.reader.DriveBreadcrumb
import com.comicanything.reader.ui.reader.LocalBreadcrumb
import com.comicanything.reader.ui.reader.ReaderUiState
import com.comicanything.reader.ui.reader.ReaderViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val MAX_CONTINUE_READING_COMICS = 10

internal fun List<ComicItem>.filtered(query: String, formats: Set<ComicFormat>): List<ComicItem> {
    val trimmedQuery = query.trim()
    return filter { comic ->
        (formats.isEmpty() || comic.format in formats) &&
            (trimmedQuery.isEmpty() || comic.title.contains(trimmedQuery, ignoreCase = true))
    }
}

/**
 * The folder path to show under a local search hit's name: the segment(s) between the search
 * root and the entry's own containing folder, joined with " / ". Null when the entry sits
 * directly in the search root (nothing useful to show).
 */
internal fun localDisplayPath(rootPath: String, entryPath: String): String? {
    // Plain string splitting on '/' rather than java.io.File: Android paths are always
    // forward-slash absolute paths, and File's path resolution is platform-dependent -- on a
    // Windows dev/test machine, File("/root/book.cbz") resolves against the current drive
    // instead of treating "/root" literally, which would make this behave differently in tests
    // than it does on-device.
    val parent = entryPath.substringBeforeLast('/', missingDelimiterValue = "")
    val relative = parent.removePrefix(rootPath).trim('/')
    return relative.ifEmpty { null }?.replace("/", " / ")
}

/**
 * Builds the full breadcrumb trail to navigate directly into [folderPath], by appending one
 * breadcrumb per path segment between [rootPath] (the search root, already the last entry in
 * [base]) and [folderPath] onto [base].
 */
internal fun localBreadcrumbsForFolder(base: List<LocalBreadcrumb>, rootPath: String, folderPath: String): List<LocalBreadcrumb> {
    val relative = folderPath.removePrefix(rootPath).trim('/')
    if (relative.isEmpty()) return base
    var cumulative = rootPath
    return base + relative.split("/").map { segment ->
        cumulative = "$cumulative/$segment"
        LocalBreadcrumb(cumulative, segment)
    }
}

/**
 * Builds the full breadcrumb trail to navigate directly into a Drive folder search hit, by
 * appending [hit]'s recorded parent path (the folders between the search root and the hit) plus
 * [folder] itself onto [base].
 */
internal fun driveBreadcrumbsForHit(base: List<DriveBreadcrumb>, hit: DriveSearchHit, folder: DriveEntry.Folder): List<DriveBreadcrumb> =
    base + hit.parentPath.map { DriveBreadcrumb(it.id, it.name) } + DriveBreadcrumb(folder.id, folder.name)

class HomeScreenState {
    var selectedTab by mutableIntStateOf(0)
    var searchQuery by mutableStateOf("")
    var isSearchActive by mutableStateOf(false)
    var selectedFormats by mutableStateOf(setOf<ComicFormat>())
    var isGridLayout by mutableStateOf(true)
}

@Composable
fun rememberHomeScreenState(): HomeScreenState = remember { HomeScreenState() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: ReaderViewModel,
    homeScreenState: HomeScreenState,
    onOpenComic: (ComicItem) -> Unit,
    onRequestPermission: () -> Unit,
    onConnectDrive: () -> Unit,
    onDisconnectDrive: () -> Unit,
    onSwitchDriveAccount: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var driveUrlInput by remember { mutableStateOf("") }

    // Debounces the recursive Drive/Local tree search behind the query field: a live search call
    // per keystroke would hammer the Drive API (a network round trip per folder visited) and
    // re-walk a potentially large local filesystem tree needlessly often. Keying on
    // (searchQuery, selectedTab) means Compose cancels and restarts this delay whenever either
    // changes, which is exactly the debounce -- only the LAST keystroke within the delay window
    // actually triggers a search.
    LaunchedEffect(homeScreenState.searchQuery, homeScreenState.selectedTab) {
        when (homeScreenState.selectedTab) {
            1 -> if (homeScreenState.searchQuery.isBlank()) {
                viewModel.clearDriveSearch()
            } else {
                delay(400)
                viewModel.searchDriveTree(homeScreenState.searchQuery)
            }
            2 -> if (homeScreenState.searchQuery.isBlank()) {
                viewModel.clearJumpToSearch()
            } else {
                delay(400)
                viewModel.searchJumpToTree(homeScreenState.searchQuery)
            }
            3 -> if (homeScreenState.searchQuery.isBlank()) {
                viewModel.clearLocalSearch()
            } else {
                delay(400)
                viewModel.searchLocalTree(homeScreenState.searchQuery)
            }
        }
    }

    val recentlyReadComics = state.libraryComics
        .filter { it.currentPage > 1 || (it.format == ComicFormat.EPUB && it.progressPercentage > 0f) }
        .filtered(homeScreenState.searchQuery, homeScreenState.selectedFormats)
        .sortedByDescending { it.lastReadTimestamp }
        .take(MAX_CONTINUE_READING_COMICS)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (homeScreenState.isSearchActive) {
                        TextField(
                            value = homeScreenState.searchQuery,
                            onValueChange = { homeScreenState.searchQuery = it },
                            placeholder = { Text("Search by name...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Book,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = "ComicAnything",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        }
                    }
                },
                actions = {
                    if (homeScreenState.isSearchActive) {
                        IconButton(onClick = { homeScreenState.isSearchActive = false; homeScreenState.searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Close search")
                        }
                    } else {
                        IconButton(onClick = { homeScreenState.isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("Recent") },
                    selected = homeScreenState.selectedTab == 0,
                    onClick = { homeScreenState.selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                    label = { Text("Google Drive") },
                    selected = homeScreenState.selectedTab == 1,
                    onClick = { homeScreenState.selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Jump to Folder") },
                    selected = homeScreenState.selectedTab == 2,
                    onClick = { homeScreenState.selectedTab = 2 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("Storage") },
                    selected = homeScreenState.selectedTab == 3,
                    onClick = { homeScreenState.selectedTab = 3 }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (homeScreenState.selectedTab) {
                0 -> ContinueReadingContent(
                    state = state,
                    viewModel = viewModel,
                    comics = recentlyReadComics,
                    selectedFormats = homeScreenState.selectedFormats,
                    onFormatToggle = { format ->
                        homeScreenState.selectedFormats = if (format in homeScreenState.selectedFormats) homeScreenState.selectedFormats - format else homeScreenState.selectedFormats + format
                    },
                    isGridLayout = homeScreenState.isGridLayout,
                    onToggleLayout = { homeScreenState.isGridLayout = !homeScreenState.isGridLayout },
                    onOpenComic = onOpenComic,
                    onRequestPermission = onRequestPermission
                )
                1 -> DriveContent(
                    state = state,
                    entries = state.driveEntries,
                    searchResults = state.driveSearchResults,
                    isSearchingTree = state.isSearchingDriveTree,
                    searchError = state.driveSearchError,
                    onRetrySearch = { viewModel.searchDriveTree(homeScreenState.searchQuery) },
                    onNavigateFolder = { id, name -> viewModel.navigateDriveFolder(id, name) },
                    onNavigateUp = { index -> viewModel.navigateDriveUp(index) },
                    onNavigateToBreadcrumbs = { breadcrumbs -> viewModel.navigateDriveToBreadcrumbs(breadcrumbs) },
                    onRetry = { viewModel.retryDriveFolder() },
                    onOpenComic = onOpenComic,
                    onConnectDrive = onConnectDrive,
                    onDisconnectDrive = onDisconnectDrive,
                    onSwitchDriveAccount = onSwitchDriveAccount
                )
                2 -> JumpToFolderContent(
                    state = state,
                    input = driveUrlInput,
                    onInputChange = { driveUrlInput = it },
                    onFetchLink = { viewModel.navigateToLinkedFolderInJumpTab(driveUrlInput) },
                    onConnectDrive = onConnectDrive,
                    entries = state.jumpToEntries,
                    searchResults = state.jumpToSearchResults,
                    isSearchingTree = state.isSearchingJumpToTree,
                    searchError = state.jumpToSearchError,
                    onRetrySearch = { viewModel.searchJumpToTree(homeScreenState.searchQuery) },
                    onNavigateFolder = { id, name -> viewModel.navigateJumpToFolder(id, name) },
                    onNavigateUp = { index -> viewModel.navigateJumpToUp(index) },
                    onNavigateToBreadcrumbs = { breadcrumbs -> viewModel.navigateJumpToBreadcrumbs(breadcrumbs) },
                    onRetry = { viewModel.retryJumpToFolder() },
                    onOpenComic = onOpenComic,
                    onSetFavorite = { folderId, isFavorite, customName -> viewModel.setJumpToFolderFavorite(folderId, isFavorite, customName) },
                    onRemoveSavedLink = { folderId -> viewModel.removeSavedDriveLink(folderId) },
                    onOpenSavedLink = { folderId ->
                        driveUrlInput = folderId
                        viewModel.navigateToLinkedFolderInJumpTab(folderId)
                    },
                    onClearJumpToFolder = {
                        driveUrlInput = ""
                        viewModel.clearJumpToFolder()
                    }
                )
                3 -> LocalFilesContent(
                    state = state,
                    entries = state.localEntries,
                    searchResults = state.localSearchResults,
                    isSearchingTree = state.isSearchingLocalTree,
                    onNavigateFolder = { path, name -> viewModel.navigateLocalFolder(path, name) },
                    onNavigateUp = { index -> viewModel.navigateLocalUp(index) },
                    onNavigateToBreadcrumbs = { breadcrumbs -> viewModel.navigateLocalToBreadcrumbs(breadcrumbs) },
                    onOpenComic = onOpenComic,
                    onRequestPermission = onRequestPermission
                )
            }
        }
    }
}

@Composable
fun PermissionRequiredCard(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Storage access needed",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "ComicAnything needs access to your device storage to find PDF, CBZ, CBR, and EPUB files.",
            color = Color.Gray,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermission) {
            Text("Grant Access")
        }
    }
}

@Composable
fun ComicCoverThumbnail(
    comic: ComicItem,
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 64.dp,
    iconTint: Color = Color.White.copy(alpha = 0.3f)
) {
    val coverState by produceState<CoverLoadState>(initialValue = CoverLoadState.Loading, key1 = comic.id) {
        value = viewModel.loadCoverThumbnail(comic)
    }
    when (val state = coverState) {
        is CoverLoadState.Loaded -> Image(
            bitmap = state.bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
        CoverLoadState.Loading, CoverLoadState.Unavailable -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Book,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
fun ContinueReadingContent(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    comics: List<ComicItem>,
    selectedFormats: Set<ComicFormat>,
    onFormatToggle: (ComicFormat) -> Unit,
    isGridLayout: Boolean,
    onToggleLayout: () -> Unit,
    onOpenComic: (ComicItem) -> Unit,
    onRequestPermission: () -> Unit
) {
    if (!state.hasStoragePermission) {
        PermissionRequiredCard(onRequestPermission)
        return
    }

    if (state.isScanningLocal) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        FormatFilterRow(selectedFormats = selectedFormats, onFormatToggle = onFormatToggle, isGridLayout = isGridLayout, onToggleLayout = onToggleLayout)

        when {
            comics.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No reading history yet.\nOpen a comic from Storage or Google Drive to see it here.",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp)
                    )
                }
            }
            isGridLayout -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(comics) { comic ->
                        ContinueReadingGridCard(comic = comic, viewModel = viewModel, onClick = { onOpenComic(comic) })
                    }
                }
            }
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(comics) { comic ->
                        ContinueReadingListRow(comic = comic, viewModel = viewModel, onClick = { onOpenComic(comic) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueReadingGridCard(comic: ComicItem, viewModel: ReaderViewModel, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .height(220.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1E2638)),
                contentAlignment = Alignment.Center
            ) {
                ComicCoverThumbnail(
                    comic = comic,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                    iconSize = 64.dp,
                    iconTint = Color.White.copy(alpha = 0.3f)
                )
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = comic.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.White
                )
                LinearProgressIndicator(
                    progress = comic.progressPercentage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (comic.format == ComicFormat.EPUB) {
                        "${(comic.progressPercentage * 100).roundToInt()}%"
                    } else {
                        "Page ${comic.currentPage}/${comic.totalPages}"
                    },
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun ContinueReadingListRow(comic: ComicItem, viewModel: ReaderViewModel, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                text = comic.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                LinearProgressIndicator(
                    progress = comic.progressPercentage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (comic.format == ComicFormat.EPUB) {
                        "${(comic.progressPercentage * 100).roundToInt()}%"
                    } else {
                        "Page ${comic.currentPage}/${comic.totalPages}"
                    },
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
        },
        leadingContent = {
            ComicCoverThumbnail(
                comic = comic,
                viewModel = viewModel,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp)),
                iconSize = 20.dp,
                iconTint = MaterialTheme.colorScheme.primary
            )
        },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.surface)
    )
}

@Composable
fun FormatFilterRow(
    selectedFormats: Set<ComicFormat>,
    onFormatToggle: (ComicFormat) -> Unit,
    isGridLayout: Boolean,
    onToggleLayout: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(ComicFormat.entries) { format ->
                FilterChip(
                    selected = format in selectedFormats,
                    onClick = { onFormatToggle(format) },
                    label = { Text(format.name) }
                )
            }
        }
        IconButton(onClick = onToggleLayout) {
            Icon(
                imageVector = if (isGridLayout) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                contentDescription = if (isGridLayout) "Switch to list view" else "Switch to grid view",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun DriveContent(
    state: ReaderUiState,
    entries: List<DriveEntry>,
    searchResults: List<DriveSearchHit>?,
    isSearchingTree: Boolean,
    searchError: String?,
    onRetrySearch: () -> Unit,
    onNavigateFolder: (String, String) -> Unit,
    onNavigateUp: (Int) -> Unit,
    onNavigateToBreadcrumbs: (List<DriveBreadcrumb>) -> Unit,
    onRetry: () -> Unit,
    onOpenComic: (ComicItem) -> Unit,
    onConnectDrive: () -> Unit,
    onDisconnectDrive: () -> Unit,
    onSwitchDriveAccount: () -> Unit
) {
    LaunchedEffect(state.driveConnectionVersion) {
        if (state.isDriveConnected && state.driveBreadcrumbs.isEmpty()) {
            onNavigateFolder("root", "My Drive")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.isDriveConnected) {
                Text(
                    text = "Connected" + (state.driveAccountEmail?.let { " as $it" } ?: ""),
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSwitchDriveAccount) {
                    Text("Switch Account")
                }
                TextButton(onClick = onDisconnectDrive) {
                    Text("Disconnect")
                }
            } else {
                Text(
                    text = "Connect your Google Drive to browse it.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onConnectDrive) {
                    Text("Connect")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.isDriveConnected) {
            DriveFolderBrowser(
                breadcrumbs = state.driveBreadcrumbs,
                entries = entries,
                error = state.driveError,
                isLoading = state.isLoadingDrive,
                searchResults = searchResults,
                isSearchingTree = isSearchingTree,
                searchError = searchError,
                emptyBreadcrumbsPrompt = "Loading My Drive...",
                onNavigateFolder = onNavigateFolder,
                onNavigateUp = onNavigateUp,
                onNavigateToBreadcrumbs = onNavigateToBreadcrumbs,
                onRetry = onRetry,
                onRetrySearch = onRetrySearch,
                onOpenComic = onOpenComic
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Connect your Google Drive above to browse your files.",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * The breadcrumb trail + folder listing (or search results) shared by the Google Drive tab and
 * the Jump to Folder tab -- each drives it from its own independent breadcrumb/entries state (see
 * [ReaderUiState.jumpToBreadcrumbs]'s doc comment for why those stay separate), but the rendering
 * itself is identical, so only one copy of it exists.
 */
@Composable
private fun DriveFolderBrowser(
    breadcrumbs: List<DriveBreadcrumb>,
    entries: List<DriveEntry>,
    error: String?,
    isLoading: Boolean,
    searchResults: List<DriveSearchHit>?,
    isSearchingTree: Boolean,
    searchError: String?,
    emptyBreadcrumbsPrompt: String,
    onNavigateFolder: (String, String) -> Unit,
    onNavigateUp: (Int) -> Unit,
    onNavigateToBreadcrumbs: (List<DriveBreadcrumb>) -> Unit,
    onRetry: () -> Unit,
    onRetrySearch: () -> Unit,
    onOpenComic: (ComicItem) -> Unit,
    // Only the Jump to Folder tab passes this -- it clears jumpToBreadcrumbs back to the
    // Favorites/Recent list, a concept the main Google Drive tab (always anchored at My Drive)
    // doesn't have. When present, it also becomes what the system back button does once there's
    // no parent folder left to go up to, instead of leaving back a no-op at that point.
    onHome: (() -> Unit)? = null
) {
    if (breadcrumbs.isNotEmpty()) {
        BackHandler(enabled = breadcrumbs.size > 1 || onHome != null) {
            if (breadcrumbs.size > 1) onNavigateUp(breadcrumbs.size - 2) else onHome?.invoke()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onHome != null) {
                IconButton(
                    onClick = onHome,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Home,
                        contentDescription = "Back to Favorites",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (breadcrumbs.size > 1) {
                IconButton(
                    onClick = { onNavigateUp(breadcrumbs.size - 2) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Up one folder",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                breadcrumbs.forEachIndexed { index, crumb ->
                    if (index > 0) {
                        Text(" > ", color = Color.Gray, fontSize = 13.sp)
                    }
                    Text(
                        text = crumb.name,
                        color = if (index == breadcrumbs.lastIndex) MaterialTheme.colorScheme.primary else Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { onNavigateUp(index) }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    when {
        isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        error != null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = error,
                        color = Color.Gray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onRetry) {
                        Text("Retry")
                    }
                }
            }
        }
        searchResults != null || searchError != null -> {
            when {
                isSearchingTree -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Searching this folder and its subfolders...", color = Color.Gray, fontSize = 13.sp)
                    }
                }
                searchError != null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = searchError,
                            color = Color.Gray,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onRetrySearch) {
                            Text("Retry")
                        }
                    }
                }
                searchResults.isNullOrEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No matches in this folder or its subfolders.",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(searchResults) { hit ->
                        val pathLabel = hit.parentPath.joinToString(" / ") { it.name }.ifEmpty { null }
                        when (val entry = hit.entry) {
                            is DriveEntry.Folder -> ListItem(
                                headlineContent = { Text(entry.name, color = Color.White, fontWeight = FontWeight.Bold) },
                                supportingContent = pathLabel?.let { { Text(it, color = Color.Gray, fontSize = 12.sp) } },
                                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onNavigateToBreadcrumbs(driveBreadcrumbsForHit(breadcrumbs, hit, entry)) }
                                    .background(MaterialTheme.colorScheme.surface)
                            )
                            is DriveEntry.ComicFile -> ListItem(
                                headlineContent = { Text(entry.comic.title, color = Color.White, fontWeight = FontWeight.Bold) },
                                supportingContent = { Text(pathLabel?.let { "$it • ${entry.comic.format.name}" } ?: entry.comic.format.name, color = Color.Gray, fontSize = 12.sp) },
                                leadingContent = { Icon(Icons.Default.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onOpenComic(entry.comic) }
                                    .background(MaterialTheme.colorScheme.surface)
                            )
                        }
                    }
                }
            }
        }
        breadcrumbs.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = emptyBreadcrumbsPrompt,
                    color = Color.Gray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
        entries.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "This folder is empty.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        }
        else -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(1),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries) { entry ->
                    when (entry) {
                        is DriveEntry.Folder -> ListItem(
                            headlineContent = { Text(entry.name, color = Color.White, fontWeight = FontWeight.Bold) },
                            leadingContent = { Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onNavigateFolder(entry.id, entry.name) }
                                .background(MaterialTheme.colorScheme.surface)
                        )
                        is DriveEntry.ComicFile -> ListItem(
                            headlineContent = { Text(entry.comic.title, color = Color.White, fontWeight = FontWeight.Bold) },
                            supportingContent = { Text(entry.comic.format.name, color = Color.Gray, fontSize = 12.sp) },
                            leadingContent = { Icon(Icons.Default.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenComic(entry.comic) }
                                .background(MaterialTheme.colorScheme.surface)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JumpToFolderContent(
    state: ReaderUiState,
    input: String,
    onInputChange: (String) -> Unit,
    onFetchLink: () -> Unit,
    onConnectDrive: () -> Unit,
    entries: List<DriveEntry>,
    searchResults: List<DriveSearchHit>?,
    isSearchingTree: Boolean,
    searchError: String?,
    onRetrySearch: () -> Unit,
    onNavigateFolder: (String, String) -> Unit,
    onNavigateUp: (Int) -> Unit,
    onNavigateToBreadcrumbs: (List<DriveBreadcrumb>) -> Unit,
    onRetry: () -> Unit,
    onOpenComic: (ComicItem) -> Unit,
    onSetFavorite: (String, Boolean, String?) -> Unit,
    onRemoveSavedLink: (String) -> Unit,
    onOpenSavedLink: (String) -> Unit,
    onClearJumpToFolder: () -> Unit
) {
    // folderId to the name pre-filled into the dialog -- "" for a brand new favorite, or the
    // existing custom name when reopened via a saved entry's rename (pencil) icon.
    var favoriteDialogTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showFavoritesSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Jump to a Drive folder",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Paste a shared Google Drive folder link or ID to open it directly, without navigating there by hand.",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
            if (state.isDriveConnected) {
                IconButton(onClick = { showFavoritesSheet = true }) {
                    Icon(Icons.Default.Star, contentDescription = "View Favorites", tint = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        if (!state.isDriveConnected) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Connect your Google Drive first.",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onConnectDrive) {
                        Text("Connect")
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text("Drive folder URL or ID") },
                trailingIcon = {
                    IconButton(onClick = onFetchLink, enabled = input.isNotBlank()) {
                        Icon(Icons.Default.Search, contentDescription = "Go", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (input.isNotBlank()) onFetchLink() }),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            val currentRootId = state.jumpToBreadcrumbs.firstOrNull()?.folderId
            val currentSavedEntry = currentRootId?.let { id -> state.savedDriveLinks.find { it.folderId == id } }
            if (currentRootId != null) {
                val isFavorite = currentSavedEntry?.isFavorite == true
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable {
                            if (isFavorite) onSetFavorite(currentRootId, false, null) else favoriteDialogTarget = currentRootId to ""
                        }
                        .padding(vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = null,
                        tint = if (isFavorite) MaterialTheme.colorScheme.secondary else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isFavorite) "Favorited" else "Add to Favorites",
                        color = if (isFavorite) MaterialTheme.colorScheme.secondary else Color.Gray,
                        fontSize = 13.sp
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (state.jumpToBreadcrumbs.isEmpty() && state.savedDriveLinks.isNotEmpty()) {
                SavedDriveLinksList(
                    links = state.savedDriveLinks,
                    onOpen = onOpenSavedLink,
                    onRemove = onRemoveSavedLink,
                    onEdit = { link -> favoriteDialogTarget = link.folderId to (link.customName ?: "") }
                )
            } else {
                DriveFolderBrowser(
                    breadcrumbs = state.jumpToBreadcrumbs,
                    entries = entries,
                    error = state.jumpToError,
                    isLoading = state.isLoadingJumpTo,
                    searchResults = searchResults,
                    isSearchingTree = isSearchingTree,
                    searchError = searchError,
                    emptyBreadcrumbsPrompt = "Paste a Drive folder link above to browse it.",
                    onNavigateFolder = onNavigateFolder,
                    onNavigateUp = onNavigateUp,
                    onNavigateToBreadcrumbs = onNavigateToBreadcrumbs,
                    onRetry = onRetry,
                    onRetrySearch = onRetrySearch,
                    onOpenComic = onOpenComic,
                    onHome = onClearJumpToFolder
                )
            }
        }
    }

    val dialogTarget = favoriteDialogTarget
    if (dialogTarget != null) {
        val (dialogFolderId, initialName) = dialogTarget
        val isRename = state.savedDriveLinks.any { it.folderId == dialogFolderId && it.isFavorite }
        var nameInput by remember(dialogFolderId) { mutableStateOf(initialName) }
        AlertDialog(
            onDismissRequest = { favoriteDialogTarget = null },
            title = { Text(if (isRename) "Rename Favorite" else "Add to Favorites") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Name (optional)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetFavorite(dialogFolderId, true, nameInput.trim().ifEmpty { null })
                    favoriteDialogTarget = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { favoriteDialogTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showFavoritesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFavoritesSheet = false },
            containerColor = Color(0xFF1E1E1E)
        ) {
            Column(modifier = Modifier.padding(16.dp).heightIn(max = 480.dp)) {
                Text("Favorites & Recent", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(12.dp))
                if (state.savedDriveLinks.isEmpty()) {
                    Text("No saved folders yet.", color = Color.Gray, fontSize = 13.sp)
                } else {
                    SavedDriveLinksList(
                        links = state.savedDriveLinks,
                        onOpen = { folderId ->
                            showFavoritesSheet = false
                            onOpenSavedLink(folderId)
                        },
                        onRemove = onRemoveSavedLink,
                        onEdit = { link ->
                            showFavoritesSheet = false
                            favoriteDialogTarget = link.folderId to (link.customName ?: "")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedDriveLinksList(
    links: List<SavedDriveLink>,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onEdit: (SavedDriveLink) -> Unit
) {
    val favorites = links.filter { it.isFavorite }.sortedBy { (it.customName ?: it.folderId).lowercase() }
    val recents = links.filter { !it.isFavorite }.sortedByDescending { it.lastUsedTimestamp }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (favorites.isNotEmpty()) {
            item {
                Text("Favorites", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            }
            items(favorites) { link -> SavedDriveLinkRow(link, onOpen, onRemove, onEdit) }
        }
        if (recents.isNotEmpty()) {
            item {
                Text("Recent", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            items(recents) { link -> SavedDriveLinkRow(link, onOpen, onRemove, onEdit) }
        }
    }
}

@Composable
private fun SavedDriveLinkRow(
    link: SavedDriveLink,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onEdit: (SavedDriveLink) -> Unit
) {
    ListItem(
        headlineContent = { Text(link.customName ?: link.folderId, color = Color.White, fontWeight = FontWeight.Bold) },
        leadingContent = {
            Icon(
                imageVector = if (link.isFavorite) Icons.Default.Star else Icons.Default.History,
                contentDescription = null,
                tint = if (link.isFavorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (link.isFavorite) {
                    IconButton(onClick = { onEdit(link) }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename", tint = Color.Gray)
                    }
                }
                IconButton(onClick = { onRemove(link.folderId) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.Gray)
                }
            }
        },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onOpen(link.folderId) }
            .background(MaterialTheme.colorScheme.surface)
    )
}

@Composable
fun LocalFilesContent(
    state: ReaderUiState,
    entries: List<LocalEntry>,
    searchResults: List<LocalEntry>?,
    isSearchingTree: Boolean,
    onNavigateFolder: (String, String) -> Unit,
    onNavigateUp: (Int) -> Unit,
    onNavigateToBreadcrumbs: (List<LocalBreadcrumb>) -> Unit,
    onOpenComic: (ComicItem) -> Unit,
    onRequestPermission: () -> Unit
) {
    if (!state.hasStoragePermission) {
        PermissionRequiredCard(onRequestPermission)
        return
    }

    LaunchedEffect(state.hasStoragePermission) {
        if (state.hasStoragePermission && state.localBreadcrumbs.isEmpty()) {
            onNavigateFolder(LocalFileRepository.DEFAULT_ROOT, "Internal Storage")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (state.localBreadcrumbs.isNotEmpty()) {
            BackHandler(enabled = state.localBreadcrumbs.size > 1) {
                onNavigateUp(state.localBreadcrumbs.size - 2)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.localBreadcrumbs.size > 1) {
                    IconButton(
                        onClick = { onNavigateUp(state.localBreadcrumbs.size - 2) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Up one folder",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    state.localBreadcrumbs.forEachIndexed { index, crumb ->
                        if (index > 0) {
                            Text(" > ", color = Color.Gray, fontSize = 13.sp)
                        }
                        Text(
                            text = crumb.name,
                            color = if (index == state.localBreadcrumbs.lastIndex) MaterialTheme.colorScheme.primary else Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable { onNavigateUp(index) }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        when {
            state.isLoadingLocalFolder -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            searchResults != null -> {
                val rootPath = state.localBreadcrumbs.lastOrNull()?.path
                when {
                    isSearchingTree -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    searchResults.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No matches in this folder or its subfolders.",
                            color = Color.Gray,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(32.dp)
                        )
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(1),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(searchResults) { entry ->
                            when (entry) {
                                is LocalEntry.Folder -> {
                                    val pathLabel = rootPath?.let { localDisplayPath(it, entry.path) }
                                    ListItem(
                                        headlineContent = { Text(entry.name, color = Color.White, fontWeight = FontWeight.Bold) },
                                        supportingContent = pathLabel?.let { { Text(it, color = Color.Gray, fontSize = 12.sp) } },
                                        leadingContent = { Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                if (rootPath != null) {
                                                    onNavigateToBreadcrumbs(localBreadcrumbsForFolder(state.localBreadcrumbs, rootPath, entry.path))
                                                }
                                            }
                                            .background(MaterialTheme.colorScheme.surface)
                                    )
                                }
                                is LocalEntry.ComicFile -> {
                                    val pathLabel = rootPath?.let { localDisplayPath(it, entry.comic.pathOrUrl) }
                                    ListItem(
                                        headlineContent = { Text(entry.comic.title, color = Color.White, fontWeight = FontWeight.Bold) },
                                        supportingContent = { Text(pathLabel?.let { "$it • ${entry.comic.format.name}" } ?: entry.comic.format.name, color = Color.Gray, fontSize = 12.sp) },
                                        leadingContent = { Icon(Icons.Default.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                        trailingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onOpenComic(entry.comic) }
                                            .background(MaterialTheme.colorScheme.surface)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            entries.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "This folder is empty.",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp)
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries) { entry ->
                        when (entry) {
                            is LocalEntry.Folder -> ListItem(
                                headlineContent = { Text(entry.name, color = Color.White, fontWeight = FontWeight.Bold) },
                                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onNavigateFolder(entry.path, entry.name) }
                                    .background(MaterialTheme.colorScheme.surface)
                            )
                            is LocalEntry.ComicFile -> ListItem(
                                headlineContent = { Text(entry.comic.title, color = Color.White, fontWeight = FontWeight.Bold) },
                                supportingContent = { Text(entry.comic.format.name, color = Color.Gray, fontSize = 12.sp) },
                                leadingContent = { Icon(Icons.Default.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onOpenComic(entry.comic) }
                                    .background(MaterialTheme.colorScheme.surface)
                            )
                        }
                    }
                }
            }
        }
    }
}
