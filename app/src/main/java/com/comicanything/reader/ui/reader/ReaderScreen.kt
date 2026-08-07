package com.comicanything.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comicanything.reader.data.model.ColorFilterMode
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ReadingMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    comic: ComicItem,
    viewModel: ReaderViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var showSettingsSheet by remember { mutableStateOf(false) }

    val bgColor = when (state.filterMode) {
        ColorFilterMode.SEPIA -> Color(0xFFFBF0D9)
        ColorFilterMode.NIGHT -> Color(0xFF1E1E1E)
        ColorFilterMode.AMOLED_BLACK -> Color.Black
        ColorFilterMode.HIGH_CONTRAST -> Color.Black
        ColorFilterMode.ORIGINAL -> Color.White
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // Canvas Interactive Reader Viewport
        when (state.readingMode) {
            ReadingMode.LTR, ReadingMode.RTL -> SinglePageReader(comic, state, viewModel)
            ReadingMode.DUAL_SPREAD -> DualPageSpreadReader(state, viewModel)
            ReadingMode.WEBTOON -> WebtoonReader(state, viewModel)
        }

        // Top Bar Overlay
        AnimatedVisibility(
            visible = state.isControlsVisible,
            enter = slideInVertically(initialOffsetY = { -it }),
            exit = slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopAppBar(
                title = {
                    Text(
                        comic.title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { comic.isFavorite = !comic.isFavorite }) {
                        Icon(
                            imageVector = if (comic.isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = null,
                            tint = if (comic.isFavorite) MaterialTheme.colorScheme.secondary else Color.White
                        )
                    }
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.85f)
                )
            )
        }

        // Bottom Scrubber Bar Overlay
        AnimatedVisibility(
            visible = state.isControlsVisible,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.85f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Page ${state.currentPage} / ${state.totalPages}", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("${((state.currentPage.toFloat() / state.totalPages) * 100).toInt()}%", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = state.currentPage.toFloat(),
                        onValueChange = { viewModel.setPage(it.toInt()) },
                        valueRange = 1f..state.totalPages.toFloat(),
                        steps = state.totalPages - 1,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

        // Quick Settings Bottom Sheet
        if (showSettingsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSettingsSheet = false },
                containerColor = Color(0xFF1E1E1E)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Reader Quick Settings", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Reading Direction Mode", color = Color.Gray, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        ReadingMode.values().forEach { mode ->
                            FilterChip(
                                selected = state.readingMode == mode,
                                onClick = { viewModel.setReadingMode(mode) },
                                label = { Text(mode.name) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto-Crop White Margins", color = Color.White)
                        Switch(
                            checked = state.autoCropMargins,
                            onCheckedChange = { viewModel.toggleAutoCrop() }
                        )
                    }
                }
            }
        }
    }
}
