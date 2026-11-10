package com.ytstyle.bili.ui.download

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.download.DownloadItem
import com.ytstyle.bili.data.download.DownloadStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class OfflinePlayerUiState(
    val item: DownloadItem? = null,
    /** Every other cached video, newest first: the picker's source. */
    val others: List<DownloadItem> = emptyList(),
    /** False until the index has been read, so the screen does not flash "not found". */
    val loaded: Boolean = false,
)

/**
 * Looks up one cached item by route argument `id`, and can switch to another
 * without leaving the screen.
 *
 * [select] exists because the offline screen lists the other cached videos: a
 * viewer picking one should not have to navigate back to the download list and
 * start over. The player is re-created per id by the screen's `key`, so the old
 * media source is released rather than resumed.
 */
@HiltViewModel
class OfflinePlayerViewModel @Inject constructor(
    store: DownloadStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialId: String = savedStateHandle.get<String>("id").orEmpty()
    private val currentId = MutableStateFlow(initialId)

    val state: StateFlow<OfflinePlayerUiState> =
        combine(store.items, currentId) { items, id ->
            OfflinePlayerUiState(
                item = items.firstOrNull { it.id == id },
                others = items.filterNot { it.id == id },
                loaded = true,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = OfflinePlayerUiState(),
        )

    /** Switches the playing item; the store keeps its newest-first order. */
    fun select(id: String) {
        currentId.value = id
    }
}
