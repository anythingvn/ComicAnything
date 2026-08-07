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
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
            .pointerInput(state.currentPage) {
                detectTapGestures(onTap = { offset ->
                    val width = size.width
                    when {
                        offset.x < width * 0.35f -> {
                            val target = (leftPage - 1).coerceAtLeast(1)
                            viewModel.setPage(target)
                        }
                        offset.x > width * 0.65f -> {
                            val target = (rightPage ?: leftPage) + 1
                            if (target <= state.totalPages) viewModel.setPage(target)
                        }
                        else -> viewModel.toggleControls()
                    }
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
    ) {
        SpreadPageSlot(page = leftPage, viewModel = viewModel, modifier = Modifier.weight(1f))
        if (rightPage != null) {
            SpreadPageSlot(page = rightPage, viewModel = viewModel, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun SpreadPageSlot(page: Int, viewModel: ReaderViewModel, modifier: Modifier = Modifier) {
    val pageState by produceState<PageLoadState>(initialValue = PageLoadState.Loading, key1 = page) {
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
