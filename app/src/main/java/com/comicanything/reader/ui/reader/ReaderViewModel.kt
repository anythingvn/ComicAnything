package com.comicanything.reader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comicanything.reader.data.model.ColorFilterMode
import com.comicanything.reader.data.model.ComicItem
import com.comicanything.reader.data.model.ReadingMode
import com.comicanything.reader.data.repository.GoogleDriveRepository
import com.comicanything.reader.data.repository.LocalFileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReaderUiState(
    val libraryComics: List<ComicItem> = emptyList(),
    val driveComics: List<ComicItem> = emptyList(),
    val activeComic: ComicItem? = null,
    val currentPage: Int = 1,
    val totalPages: Int = 48,
    val readingMode: ReadingMode = ReadingMode.LTR,
    val filterMode: ColorFilterMode = ColorFilterMode.AMOLED_BLACK,
    val autoCropMargins: Boolean = true,
    val isControlsVisible: Boolean = true,
    val isLoadingDrive: Boolean = false
)

class ReaderViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val localRepo = LocalFileRepository()
    private val driveRepo = GoogleDriveRepository()

    init {
        loadLocalLibrary()
    }

    fun loadLocalLibrary() {
        viewModelScope.launch {
            val items = localRepo.scanStorageDirectories()
            _uiState.value = _uiState.value.copy(libraryComics = items)
        }
    }

    fun fetchDriveFolder(folderUrlOrId: String, apiKey: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingDrive = true)
            val items = driveRepo.fetchFolderContents(folderUrlOrId, apiKey)
            _uiState.value = _uiState.value.copy(driveComics = items, isLoadingDrive = false)
        }
    }

    fun openComic(comic: ComicItem) {
        _uiState.value = _uiState.value.copy(
            activeComic = comic,
            currentPage = comic.currentPage,
            totalPages = if (comic.totalPages > 0) comic.totalPages else 48,
            isControlsVisible = true
        )
    }

    fun setPage(page: Int) {
        val clamped = page.coerceIn(1, _uiState.value.totalPages)
        _uiState.value = _uiState.value.copy(currentPage = clamped)

        _uiState.value.activeComic?.let { comic ->
            comic.currentPage = clamped
            comic.progressPercentage = clamped.toFloat() / _uiState.value.totalPages.toFloat()
        }
    }

    fun toggleControls() {
        _uiState.value = _uiState.value.copy(isControlsVisible = !_uiState.value.isControlsVisible)
    }

    fun setReadingMode(mode: ReadingMode) {
        _uiState.value = _uiState.value.copy(readingMode = mode)
    }

    fun setFilterMode(mode: ColorFilterMode) {
        _uiState.value = _uiState.value.copy(filterMode = mode)
    }

    fun toggleAutoCrop() {
        _uiState.value = _uiState.value.copy(autoCropMargins = !_uiState.value.autoCropMargins)
    }
}
