package com.comicanything.reader.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

fun spreadPagesFor(currentPage: Int, totalPages: Int): Pair<Int, Int?> {
    if (currentPage <= 1) return 1 to null
    val left = if (currentPage % 2 == 0) currentPage else currentPage - 1
    val right = (left + 1).takeIf { it <= totalPages }
    return left to right
}

@Composable
fun DualPageSpreadReader(state: ReaderUiState, viewModel: ReaderViewModel) {
    val (leftPage, rightPage) = spreadPagesFor(state.currentPage, state.totalPages)
    val currentState by rememberUpdatedState(state)

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.currentPage) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset ->
                    val width = size.width
                    val (liveLeft, liveRight) = spreadPagesFor(currentState.currentPage, currentState.totalPages)
                    when {
                        offset.x < width * 0.35f -> {
                            val target = (liveLeft - 1).coerceAtLeast(1)
                            viewModel.setPage(target)
                        }
                        offset.x > width * 0.65f -> {
                            val target = (liveRight ?: liveLeft) + 1
                            if (target <= currentState.totalPages) viewModel.setPage(target)
                        }
                        else -> viewModel.toggleControls()
                    }
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    if (scale > 1f) {
                        val maxX = size.width * (scale - 1f) / 2f
                        val maxY = size.height * (scale - 1f) / 2f
                        offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                        offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        ) {
            SpreadPageSlot(page = leftPage, generation = state.pageSourceGeneration, viewModel = viewModel, modifier = Modifier.weight(1f))
            if (rightPage != null) {
                SpreadPageSlot(page = rightPage, generation = state.pageSourceGeneration, viewModel = viewModel, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SpreadPageSlot(page: Int, generation: Int, viewModel: ReaderViewModel, modifier: Modifier = Modifier) {
    val pageState by produceState<PageLoadState>(initialValue = PageLoadState.Loading, key1 = page, key2 = generation) {
        value = viewModel.loadPageBitmap(page)
    }
    when (val s = pageState) {
        is PageLoadState.Loaded -> Image(
            bitmap = s.bitmap.asImageBitmap(),
            contentDescription = "Page $page",
            modifier = modifier
        )
        PageLoadState.Failed -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(48.dp)
            )
        }
        PageLoadState.Loading -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}
