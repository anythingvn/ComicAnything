package com.comicanything.reader.ui.reader

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.comicanything.reader.data.model.ComicFormat
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ComicSource
import com.comicanything.reader.data.model.ReadingMode
import com.comicanything.reader.data.repository.LocalFileRepository
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * First instrumented (androidTest) test in this project. Runs on a real device/emulator, so
 * unlike the plain-JVM ReaderViewModelTest suite, BitmapFactory decode genuinely works here --
 * this test opens a real CBZ and taps the actually-rendered page.
 */
class SinglePageReaderTapZoneTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun buildCbz(pageCount: Int): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val zipFile = File.createTempFile("tapzone-${System.nanoTime()}", ".cbz", context.cacheDir)
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            for (i in 1..pageCount) {
                zip.putNextEntry(ZipEntry("page$i.jpg"))
                zip.write("fake-jpeg-bytes-$i".toByteArray())
                zip.closeEntry()
            }
        }
        return zipFile
    }

    private fun openComic(pageCount: Int): ReaderViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val repo = LocalFileRepository(rootPath = context.cacheDir.absolutePath, ioDispatcher = Dispatchers.IO)
        val viewModel = ReaderViewModel(application = application, localRepo = repo)
        val comic = ComicItem(
            id = "tap-zone-${System.nanoTime()}",
            title = "Tap Zone Test",
            pathOrUrl = buildCbz(pageCount).absolutePath,
            source = ComicSource.LOCAL,
            format = ComicFormat.CBZ
        )
        viewModel.openComic(comic)
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.activeComic?.id == comic.id && viewModel.uiState.value.totalPages == pageCount
        }
        return viewModel
    }

    @Test
    fun tappingRightZoneInLtrModeGoesToNextPage() {
        val viewModel = openComic(pageCount = 3)
        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SinglePageReader(comic = state.activeComic!!, state = state, viewModel = viewModel)
        }
        assertEquals(1, viewModel.uiState.value.currentPage)

        composeTestRule.onRoot().performTouchInput {
            click(Offset(width * 0.9f, height * 0.5f))
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.currentPage == 2 }
        assertEquals(2, viewModel.uiState.value.currentPage)
    }

    @Test
    fun tappingLeftZoneInLtrModeGoesToPreviousPage() {
        val viewModel = openComic(pageCount = 3)
        viewModel.setPage(2)
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.currentPage == 2 }
        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SinglePageReader(comic = state.activeComic!!, state = state, viewModel = viewModel)
        }

        composeTestRule.onRoot().performTouchInput {
            click(Offset(width * 0.1f, height * 0.5f))
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.currentPage == 1 }
        assertEquals(1, viewModel.uiState.value.currentPage)
    }

    @Test
    fun tappingCenterZoneTogglesControlsInsteadOfChangingPage() {
        val viewModel = openComic(pageCount = 3)
        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SinglePageReader(comic = state.activeComic!!, state = state, viewModel = viewModel)
        }
        assertTrue(viewModel.uiState.value.isControlsVisible)

        composeTestRule.onRoot().performTouchInput {
            click(Offset(width * 0.5f, height * 0.5f))
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) { !viewModel.uiState.value.isControlsVisible }
        assertFalse(viewModel.uiState.value.isControlsVisible)
        assertEquals(1, viewModel.uiState.value.currentPage)
    }

    @Test
    fun tapZonesAreReversedInRtlMode() {
        val viewModel = openComic(pageCount = 3)
        viewModel.setPage(2)
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.currentPage == 2 }
        viewModel.setReadingMode(ReadingMode.RTL)
        composeTestRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SinglePageReader(comic = state.activeComic!!, state = state, viewModel = viewModel)
        }

        // In RTL, the right zone (normally "next") goes to the previous page instead.
        composeTestRule.onRoot().performTouchInput {
            click(Offset(width * 0.9f, height * 0.5f))
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.currentPage == 1 }
        assertEquals(1, viewModel.uiState.value.currentPage)
    }
}
