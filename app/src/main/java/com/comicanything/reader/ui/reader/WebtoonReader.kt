package com.comicanything.reader.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// A plain `visibleItemsInfo.firstOrNull()` reports whichever item has the smallest index among
// items that intersect the viewport at all -- including one with only a handful of pixels
// showing at the very top edge. That's misleading for "which page is the user looking at":
// confirmed on-device that after scrolling to a target page, the preceding item could still have
// a visible sliver (its real decoded height ended up taller than the Loading placeholder height
// the scroll was computed against), reporting the page before the intended one. Filtering for a
// positive visible height picks the item that's actually dominant at the top.
private fun LazyListState.topVisibleItemIndex(): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.offset + it.size > 0 }?.index

@Composable
fun WebtoonReader(state: ReaderUiState, viewModel: ReaderViewModel) {
    val listState = rememberLazyListState()

    // The single source of truth for "what page did WE (this list) last report to the
    // ViewModel". Used below to tell apart a state.currentPage change WE caused (via the
    // collector) from one caused externally (the bottom scrubber Slider) -- see the reverse-sync
    // LaunchedEffect's comment for why that distinction is essential, not optional.
    var lastReportedPage by remember { mutableIntStateOf(state.currentPage) }

    // Whether the initial resume-to-last-read-page scroll has completed. The collector below is
    // gated on this: an earlier version seeded `rememberLazyListState(initialFirstVisibleItemIndex
    // = ...)` instead, hoping the list would already be in the right place on the very first
    // frame. It wasn't reliable -- confirmed on-device via instrumentation that while page items
    // are still showing Loading/Failed placeholders (before their real bitmap finishes decoding
    // via produceState), the placeholder heights don't match the eventually-loaded content, and
    // Compose's LazyColumn remeasures during that settling window drift the seeded position all
    // the way back down to index 0. Worse, the collector (which was already running) reported
    // each of those transient, wrong positions as "the current page," overwriting
    // state.currentPage AND lastReportedPage before the reverse-sync effect ever got a chance to
    // correct it -- once lastReportedPage matched the (wrong) state.currentPage, the reverse-sync
    // effect saw nothing to fix and stayed a no-op forever, permanently discarding the resume
    // position.
    //
    // The fix: don't seed the list at all (default to index 0, same as the reverse-sync effect's
    // proven-stable scrollToItem mechanism), explicitly scrollToItem() to the resume page once on
    // mount, and don't let the collector observe/report anything until that explicit scroll has
    // landed. Any layout drift caused by images loading AFTER that point is already handled
    // correctly by the existing lastReportedPage-based reverse-sync effect below.
    var resumeScrollDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // scrollToItem() called before the LazyColumn has completed even one real layout pass
        // isn't reliable -- confirmed on-device it could land with the target item only partially
        // positioned. Waiting for the list's first natural layout (starting at index 0, as if
        // unseeded) before issuing the jump gives scrollToItem a laid-out list to reposition.
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.filter { it }.first()
        val targetIndex = (state.currentPage - 1).coerceIn(0, (state.totalPages - 1).coerceAtLeast(0))
        listState.scrollToItem(targetIndex)
        // Settle window: scrollToItem() fixes "how far scrolled past the preceding item" as an
        // absolute pixel offset at call time. If that item is still showing its Loading/Failed
        // placeholder then and later grows once its real bitmap decodes (confirmed on-device: a
        // placeholder height of 941px growing to a real 1050px, then 1440px, as decoding
        // progressed), the fixed offset no longer fully covers it -- a sliver of it reappears at
        // the top, one item early. Re-asserting the scroll a few times with a short delay between
        // attempts gives images time to reach their real size and corrects for any such growth
        // before this position is trusted or reported anywhere.
        repeat(6) {
            delay(100)
            if (listState.topVisibleItemIndex() != targetIndex) {
                listState.scrollToItem(targetIndex)
            }
        }
        lastReportedPage = targetIndex + 1
        resumeScrollDone = true
    }

    LaunchedEffect(listState, resumeScrollDone) {
        if (!resumeScrollDone) return@LaunchedEffect
        snapshotFlow { listState.topVisibleItemIndex() }
            .filterNotNull()
            .map { it + 1 }
            .distinctUntilChanged()
            .collect { page ->
                lastReportedPage = page
                viewModel.setCurrentPageIndicator(page)
            }
    }

    // Reacts to page changes that DIDN'T originate from the user scrolling this list --
    // dragging the bottom scrubber Slider (ReaderScreen.kt calls viewModel.setPage(), which
    // updates state.currentPage but has no idea this list even exists).
    //
    // An earlier version guarded with `listState.isScrollInProgress` instead of lastReportedPage,
    // reasoning that "we're mid-gesture" was a good proxy for "this change came from our own
    // collector". It was NOT: page bitmaps load asynchronously (produceState below), so an item's
    // real height replaces its placeholder height sometime after scrollToItem() lands, shifting
    // which item ends up "first visible" -- often by the time isScrollInProgress has already
    // settled back to false. That drifted index fed back through the collector as a "new" page,
    // which this effect then chased with another scrollToItem(), which drifted again, etc. -- a
    // sustained oscillation (observed on-device cycling 1->2->3->4->3->2->1->... for 10+ seconds).
    // Comparing against lastReportedPage instead is exact rather than a timing proxy: if the
    // collector itself produced this exact page value, this effect is a guaranteed no-op, full
    // stop -- no residual layout drift can re-trigger it.
    LaunchedEffect(state.currentPage) {
        if (!resumeScrollDone || state.totalPages <= 0 || state.currentPage == lastReportedPage) return@LaunchedEffect
        val targetIndex = (state.currentPage - 1).coerceIn(0, state.totalPages - 1)
        listState.scrollToItem(targetIndex)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { viewModel.toggleControls() })
            }
    ) {
        items(state.totalPages) { index ->
            val page = index + 1
            val pageState by produceState<PageLoadState>(initialValue = PageLoadState.Loading, key1 = page, key2 = state.pageSourceGeneration) {
                value = viewModel.loadPageBitmap(page)
            }
            when (val s = pageState) {
                is PageLoadState.Loaded -> Image(
                    bitmap = s.bitmap.asImageBitmap(),
                    contentDescription = "Page $page",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth()
                )
                PageLoadState.Failed -> Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(48.dp)
                    )
                }
                PageLoadState.Loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
