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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
                    label = { Text("Continue Reading") },
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
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("Local Files") },
                    selected = homeScreenState.selectedTab == 2,
                    onClick = { homeScreenState.selectedTab = 2 }
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
                    input = driveUrlInput,
                    onInputChange = { driveUrlInput = it },
                    onFetchLink = { viewModel.navigateToLinkedFolder(driveUrlInput) },
                    onNavigateFolder = { id, name -> viewModel.navigateDriveFolder(id, name) },
                    onNavigateUp = { index -> viewModel.navigateDriveUp(index) },
                    onNavigateToBreadcrumbs = { breadcrumbs -> viewModel.navigateDriveToBreadcrumbs(breadcrumbs) },
                    onRetry = { viewModel.retryDriveFolder() },
                    onOpenComic = onOpenComic,
                    onConnectDrive = onConnectDrive,
                    onDisconnectDrive = onDisconnectDrive,
                    onSwitchDriveAccount = onSwitchDriveAccount
                )
                2 -> LocalFilesContent(
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
                        text = "No reading history yet.\nOpen a comic from Local Files or Google Drive to see it here.",
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
    input: String,
    onInputChange: (String) -> Unit,
    onFetchLink: () -> Unit,
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

        if (state.isDriveConnected && state.driveBreadcrumbs.isNotEmpty()) {
            BackHandler(enabled = state.driveBreadcrumbs.size > 1) {
                onNavigateUp(state.driveBreadcrumbs.size - 2)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.driveBreadcrumbs.size > 1) {
                    IconButton(
                        onClick = { onNavigateUp(state.driveBreadcrumbs.size - 2) },
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
                    state.driveBreadcrumbs.forEachIndexed { index, crumb ->
                        if (index > 0) {
                            Text(" > ", color = Color.Gray, fontSize = 13.sp)
                        }
                        Text(
                            text = crumb.name,
                            color = if (index == state.driveBreadcrumbs.lastIndex) MaterialTheme.colorScheme.primary else Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable { onNavigateUp(index) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text("Jump to folder (paste a Drive folder URL/ID)") },
            trailingIcon = {
                IconButton(onClick = onFetchLink) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        when {
            state.isLoadingDrive -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            !state.isDriveConnected -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Connect your Google Drive above to browse your files.",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
            state.driveError != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = state.driveError,
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
            searchResults != null -> {
                when {
                    isSearchingTree -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    searchResults.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                                        .clickable { onNavigateToBreadcrumbs(driveBreadcrumbsForHit(state.driveBreadcrumbs, hit, entry)) }
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
