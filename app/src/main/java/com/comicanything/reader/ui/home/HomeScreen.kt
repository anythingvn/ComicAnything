package com.comicanything.reader.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.ui.reader.ReaderUiState
import com.comicanything.reader.ui.reader.ReaderViewModel

internal fun List<ComicItem>.filtered(query: String, formats: Set<ComicFormat>): List<ComicItem> =
    filter { comic ->
        (formats.isEmpty() || comic.format in formats) &&
            (query.isBlank() || comic.title.contains(query, ignoreCase = true))
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: ReaderViewModel,
    onOpenComic: (ComicItem) -> Unit,
    onRequestPermission: () -> Unit,
    onConnectDrive: () -> Unit,
    onDisconnectDrive: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var driveUrlInput by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectedFormats by remember { mutableStateOf(setOf<ComicFormat>()) }
    var isGridLayout by remember { mutableStateOf(true) }

    val filteredLibraryComics = state.libraryComics.filtered(searchQuery, selectedFormats)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search your library...") },
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
                    if (isSearchActive) {
                        IconButton(onClick = { isSearchActive = false; searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Close search")
                        }
                    } else {
                        IconButton(onClick = { isSearchActive = true }) {
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
                    icon = { Icon(Icons.Default.CollectionsBookmark, contentDescription = null) },
                    label = { Text("Library") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                    label = { Text("Google Drive") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("Local Files") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
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
            when (selectedTab) {
                0 -> LibraryContent(
                    state = state,
                    comics = filteredLibraryComics,
                    selectedFormats = selectedFormats,
                    onFormatToggle = { format ->
                        selectedFormats = if (format in selectedFormats) selectedFormats - format else selectedFormats + format
                    },
                    isGridLayout = isGridLayout,
                    onToggleLayout = { isGridLayout = !isGridLayout },
                    onOpenComic = onOpenComic,
                    onRequestPermission = onRequestPermission
                )
                1 -> DriveContent(state, driveUrlInput, onInputChange = { driveUrlInput = it }, onFetch = { viewModel.fetchDriveFolder(driveUrlInput) }, onOpenComic, onConnectDrive, onDisconnectDrive)
                2 -> LocalFilesContent(
                    state = state,
                    comics = filteredLibraryComics,
                    selectedFormats = selectedFormats,
                    onFormatToggle = { format ->
                        selectedFormats = if (format in selectedFormats) selectedFormats - format else selectedFormats + format
                    },
                    isGridLayout = isGridLayout,
                    onToggleLayout = { isGridLayout = !isGridLayout },
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
            text = "ComicAnything needs access to your device storage to find PDF, CBZ, CBR, EPUB, and MOBI files.",
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
fun LibraryContent(
    state: ReaderUiState,
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

    val inProgress = comics.filter { it.currentPage > 1 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        FormatFilterRow(selectedFormats = selectedFormats, onFormatToggle = onFormatToggle, isGridLayout = isGridLayout, onToggleLayout = onToggleLayout)

        if (inProgress.isNotEmpty()) {
            Text(
                text = "⚡ CONTINUE READING",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 20.dp)
            ) {
                items(inProgress) { comic ->
                    Card(
                        modifier = Modifier
                            .width(140.dp)
                            .height(180.dp)
                            .clickable { onOpenComic(comic) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(Color(0xFF2C2C2C)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Book,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = comic.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
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
                                    text = "Page ${comic.currentPage}/${comic.totalPages}",
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = "📚 MY BOOKSHELF",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (isGridLayout) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(comics) { comic ->
                    ComicGridCard(comic = comic, onClick = { onOpenComic(comic) })
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(comics) { comic ->
                    ComicListRow(comic = comic, onClick = { onOpenComic(comic) })
                }
            }
        }
    }
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
fun ComicGridCard(comic: ComicItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .height(200.dp)
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
                Icon(
                    imageVector = Icons.Default.Book,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(64.dp)
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.8f)
                ) {
                    Text(
                        text = comic.format.name,
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = comic.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
fun ComicListRow(comic: ComicItem, onClick: () -> Unit) {
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
        leadingContent = {
            Icon(Icons.Default.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.8f)
            ) {
                Text(
                    text = comic.format.name,
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.surface)
    )
}

@Composable
fun DriveContent(
    state: ReaderUiState,
    input: String,
    onInputChange: (String) -> Unit,
    onFetch: () -> Unit,
    onOpenComic: (ComicItem) -> Unit,
    onConnectDrive: () -> Unit,
    onDisconnectDrive: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (state.isDriveConnected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Connected" + (state.driveAccountEmail?.let { " as $it" } ?: ""),
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDisconnectDrive) {
                    Text("Disconnect")
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Optional: connect your Google Drive to read comics stored there.",
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

        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text("Paste Google Drive Folder URL / ID") },
            trailingIcon = {
                IconButton(onClick = onFetch) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (state.isLoadingDrive) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (state.driveComics.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No Drive folder linked yet.\nPaste a shared Drive folder link above!",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(1),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.driveComics) { comic ->
                    ListItem(
                        headlineContent = { Text(comic.title, color = Color.White, fontWeight = FontWeight.Bold) },
                        supportingContent = { Text(comic.folderName ?: "", color = Color.Gray, fontSize = 12.sp) },
                        leadingContent = { Icon(Icons.Default.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenComic(comic) }
                            .background(MaterialTheme.colorScheme.surface)
                    )
                }
            }
        }
    }
}

@Composable
fun LocalFilesContent(
    state: ReaderUiState,
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

    if (state.libraryComics.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No comics found on this device.\nAdd PDF, CBZ, CBR, EPUB, or MOBI files to your storage.",
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        FormatFilterRow(selectedFormats = selectedFormats, onFormatToggle = onFormatToggle, isGridLayout = isGridLayout, onToggleLayout = onToggleLayout)

        if (isGridLayout) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(comics) { comic ->
                    ComicGridCard(comic = comic, onClick = { onOpenComic(comic) })
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(comics) { comic ->
                    ComicListRow(comic = comic, onClick = { onOpenComic(comic) })
                }
            }
        }
    }
}
