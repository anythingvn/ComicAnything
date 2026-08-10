package com.comicanything.reader.ui.reader

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.comicanything.reader.data.model.ColorFilterMode
import com.comicanything.reader.data.model.ComicItem
import java.io.File

/** Origin WebViewAssetLoader serves local EPUB content from; nothing else may be navigated to. */
private const val ASSET_LOADER_ORIGIN = "https://appassets.androidplatform.net/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReaderScreen(
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
        val book = state.epubBook
        when {
            book != null -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val extractedDir = book.extractedDir
                        val assetLoader = WebViewAssetLoader.Builder()
                            .addPathHandler(
                                "/epub/",
                                WebViewAssetLoader.InternalStoragePathHandler(ctx, extractedDir)
                            )
                            .build()
                        val relativePath = book.combinedHtmlFile.relativeTo(extractedDir).path
                            .replace(File.separatorChar, '/')
                        val targetUrl = "https://appassets.androidplatform.net/epub/$relativePath"
                        val resumePercentage = comic.progressPercentage
                        val density = ctx.resources.displayMetrics.density

                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = false // unused by this reader, disable unused capability
                            // Real network fetches are never needed: WebViewAssetLoader's
                            // shouldInterceptRequest below answers every /epub/ request BEFORE it
                            // would hit the network, so local asset loading is unaffected. This
                            // blocks EPUB-embedded JS/CSS/img references from ever reaching the
                            // live internet (tracking pixels, exfiltration, etc.) -- this app is
                            // offline-first and EPUBs are routinely sourced from untrusted origins.
                            settings.blockNetworkLoads = true
                            settings.allowFileAccess = false // WebViewAssetLoader replaces file:// entirely
                            settings.allowContentAccess = false
                            webViewClient = object : WebViewClientCompat() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    url: String
                                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(Uri.parse(url))

                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean {
                                    // Block navigation to anything outside the app's own local
                                    // asset-loader origin -- e.g. a tapped link inside EPUB content
                                    // must never take over this in-reader WebView and load the live
                                    // web with no browser chrome around it.
                                    return !request.url.toString().startsWith(ASSET_LOADER_ORIGIN)
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                                    return !url.startsWith(ASSET_LOADER_ORIGIN)
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    super.onPageFinished(view, url)
                                    view.post {
                                        view.evaluateJavascript(
                                            buildThemeJs(viewModel.uiState.value.filterMode),
                                            null
                                        )
                                        if (resumePercentage > 0f) {
                                            restoreScrollWhenLaidOut(view, density, resumePercentage, attempt = 0)
                                        }
                                    }
                                }
                            }
                            setOnScrollChangeListener { view, _, scrollY, _, _ ->
                                val webView = view as WebView
                                val contentHeightPx = (webView.contentHeight * density).toInt()
                                val maxScroll = contentHeightPx - webView.height
                                if (maxScroll > 0) {
                                    viewModel.setEpubScrollProgress(scrollY.toFloat() / maxScroll.toFloat())
                                }
                            }
                            loadUrl(targetUrl)
                        }
                    },
                    update = { webView ->
                        webView.evaluateJavascript(buildThemeJs(state.filterMode), null)
                    },
                    onRelease = { webView ->
                        // WebView is not destroyed automatically when this composable leaves
                        // composition -- without an explicit destroy() call, repeated open/close
                        // cycles of the reader accumulate native-heap WebView instances (a
                        // well-known Android leak hazard).
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.removeAllViews()
                        webView.destroy()
                    }
                )
            }
            state.pageLoadError != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = state.pageLoadError!!,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }

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
                    IconButton(onClick = { viewModel.toggleFavorite(comic) }) {
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

        if (showSettingsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSettingsSheet = false },
                containerColor = Color(0xFF1E1E1E)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Reader Quick Settings", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Theme", color = Color.Gray, fontSize = 12.sp)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        ColorFilterMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.filterMode == mode,
                                onClick = { viewModel.setFilterMode(mode) },
                                label = { Text(mode.name) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Maximum number of retries when polling for a laid-out page height before giving up on
 * restoring scroll position. At ~100ms per attempt this caps the wait at ~1.5s.
 */
private const val MAX_RESUME_SCROLL_ATTEMPTS = 15
private const val RESUME_SCROLL_RETRY_DELAY_MS = 100L

/**
 * Restores the WebView's scroll position to [resumePercentage] of the page height, once the
 * page has genuinely finished layout.
 *
 * `view.contentHeight` (a native Android WebView property) is not reliable immediately after
 * `onPageFinished` + one `view.post {}` frame: on a page load served from WebView's internal
 * cache (e.g. reopening the same asset-loader URL), `onPageFinished` can fire before layout has
 * settled, leaving `contentHeight` at 0 for that first frame. `document.documentElement.scrollHeight`
 * read via `evaluateJavascript` is more trustworthy because its callback only fires after the JS
 * engine has actually evaluated the expression against the current DOM/layout state, so polling
 * it converges on the true height instead of trusting a single native read. If the height never
 * becomes available within the retry budget, this gives up silently rather than scrolling to a
 * wrong position or hanging.
 */
private fun restoreScrollWhenLaidOut(
    view: WebView,
    density: Float,
    resumePercentage: Float,
    attempt: Int
) {
    if (!view.isAttachedToWindow) return
    view.evaluateJavascript("document.documentElement.scrollHeight.toString()") { result ->
        if (!view.isAttachedToWindow) return@evaluateJavascript
        val scrollHeightCss = result?.trim('"')?.toIntOrNull() ?: 0
        val contentHeightPx = (scrollHeightCss * density).toInt()
        val maxScroll = contentHeightPx - view.height
        if (maxScroll > 0) {
            view.scrollTo(0, (maxScroll * resumePercentage).toInt())
        } else if (attempt < MAX_RESUME_SCROLL_ATTEMPTS) {
            view.postDelayed(
                { restoreScrollWhenLaidOut(view, density, resumePercentage, attempt + 1) },
                RESUME_SCROLL_RETRY_DELAY_MS
            )
        }
    }
}

private fun buildThemeJs(mode: ColorFilterMode): String {
    val (bg, fg) = when (mode) {
        ColorFilterMode.SEPIA -> "#FBF0D9" to "#3b2f1e"
        ColorFilterMode.NIGHT -> "#1E1E1E" to "#dddddd"
        ColorFilterMode.AMOLED_BLACK -> "#000000" to "#ffffff"
        ColorFilterMode.HIGH_CONTRAST -> "#000000" to "#ffffff"
        ColorFilterMode.ORIGINAL -> "#ffffff" to "#000000"
    }
    return """
        (function() {
            var style = document.getElementById('reader-theme');
            if (style) { style.innerHTML = 'body { background-color: $bg !important; } body, body *:not(a) { color: $fg !important; }'; }
        })();
    """.trimIndent()
}
