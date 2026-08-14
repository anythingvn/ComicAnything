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
import com.comicanything.reader.data.repository.LocalEntry
import com.comicanything.reader.data.repository.LocalFileRepository
import com.comicanything.reader.ui.reader.CoverLoadState
import com.comicanything.reader.ui.reader.ReaderUiState
import com.comicanything.reader.ui.reader.ReaderViewModel
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
 * Filters a folder-browse listing by [query], keeping every folder visible regardless of match
 * (so a search never blocks navigation into a folder that might contain matching files further
 * down) and only filtering the comic files by title.
 */
internal fun List<DriveEntry>.filteredDriveEntries(query: String): List<DriveEntry> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return this
    return filter { entry ->
        when (entry) {
            is DriveEntry.Folder -> true
            is DriveEntry.ComicFile -> entry.comic.title.contains(trimmedQuery, ignoreCase = true)
        }
    }
}

internal fun List<LocalEntry>.filteredLocalEntries(query: String): List<LocalEntry> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return this
    return filter { entry ->
        when (entry) {
            is LocalEntry.Folder -> true
            is LocalEntry.ComicFile -> entry.comic.title.contains(trimmedQuery, ignoreCase = true)
        }
    }
}

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
    onDisconnectDrive: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var driveUrlInput by remember { mutableStateOf("") }

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
                    entries = state.driveEntries.filteredDriveEntries(homeScreenState.searchQuery),
                    input = driveUrlInput,
                    onInputChange = { driveUrlInput = it },
                    onFetchLink = { viewModel.navigateToLinkedFolder(driveUrlInput) },
                    onNavigateFolder = { id, name -> viewModel.navigateDriveFolder(id, name) },
                    onNavigateUp = { index -> viewModel.navigateDriveUp(index) },
                    onRetry = { viewModel.retryDriveFolder() },
                    onOpenComic = onOpenComic,
                    onConnectDrive = onConnectDrive,
                    onDisconnectDrive = onDisconnectDrive
                )
                2 -> LocalFilesContent(
                    state = state,
                    entries = state.localEntries.filteredLocalEntries(homeScreenState.searchQuery),
                    isSearching = homeScreenState.searchQuery.isNotBlank(),
                    onNavigateFolder = { path, name -> viewModel.navigateLocalFolder(path, name) },
                    onNavigateUp = { index -> viewModel.navigateLocalUp(index) },
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
    input: String,
    onInputChange: (String) -> Unit,
    onFetchLink: () -> Unit,
    onNavigateFolder: (String, String) -> Unit,
    onNavigateUp: (Int) -> Unit,
    onRetry: () -> Unit,
    onOpenComic: (ComicItem) -> Unit,
    onConnectDrive: () -> Unit,
    onDisconnectDrive: () -> Unit
) {
    LaunchedEffect(state.isDriveConnected) {
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
            entries.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (state.driveEntries.isEmpty()) "This folder is empty." else "No files match your search in this folder.",
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
    isSearching: Boolean,
    onNavigateFolder: (String, String) -> Unit,
    onNavigateUp: (Int) -> Unit,
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
            entries.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isSearching) "No files match your search in this folder." else "This folder is empty.",
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
