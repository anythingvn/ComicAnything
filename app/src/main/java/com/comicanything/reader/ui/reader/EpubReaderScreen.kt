package com.comicanything.reader.ui.reader

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClientCompat() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    url: String
                                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(Uri.parse(url))

                                override fun onPageFinished(view: WebView, url: String?) {
                                    super.onPageFinished(view, url)
                                    view.post {
                                        val contentHeightPx = (view.contentHeight * density).toInt()
                                        val maxScroll = contentHeightPx - view.height
                                        if (maxScroll > 0 && resumePercentage > 0f) {
                                            view.scrollTo(0, (maxScroll * resumePercentage).toInt())
                                        }
                                        view.evaluateJavascript(
                                            buildThemeJs(viewModel.uiState.value.filterMode),
                                            null
                                        )
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
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
            if (style) { style.innerHTML = 'body { background-color: $bg !important; color: $fg !important; }'; }
        })();
    """.trimIndent()
}
