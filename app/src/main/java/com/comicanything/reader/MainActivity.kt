package com.comicanything.reader

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.comicanything.reader.util.StoragePermissions

class MainActivity : ComponentActivity() {

    private val viewModel: ReaderViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.setPermissionGranted(granted) }

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
                            onOpenComic = { comic -> viewModel.openComic(comic) },
                            onRequestPermission = { requestStoragePermission() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = StoragePermissions.hasAccess(this)
        viewModel.setPermissionGranted(granted)
        if (granted) {
            viewModel.refreshLibrary()
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = StoragePermissions.manageStorageSettingsIntent(this)
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                startActivity(StoragePermissions.manageStorageSettingsFallbackIntent())
            }
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
