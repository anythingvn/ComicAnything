package com.comicanything.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.ui.home.HomeScreen
import com.comicanything.reader.ui.reader.ReaderScreen
import com.comicanything.reader.ui.reader.ReaderViewModel
import com.comicanything.reader.ui.theme.ComicAnythingTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComicAnythingTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by viewModel.uiState.collectAsState()

                    if (state.activeComic != null) {
                        ReaderScreen(
                            comic = state.activeComic!!,
                            viewModel = viewModel,
                            onBack = { viewModel.openComic(null as ComicItem? ?: return@ReaderScreen) }
                        )
                    } else {
                        HomeScreen(
                            viewModel = viewModel,
                            onOpenComic = { comic -> viewModel.openComic(comic) }
                        )
                    }
                }
            }
        }
    }
}
