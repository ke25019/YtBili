package com.ytstyle.bili.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.net.HistoryItem
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val items: List<HistoryItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: BiliRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            when (val result = repository.history()) {
                is Result.Ok -> _state.update {
                    // History includes non-video entries (articles, live rooms);
                    // only archive rows carry a bvid we can open in the player.
                    it.copy(
                        isLoading = false,
                        items = result.value.list.filter { row -> row.bvid.isNotEmpty() },
                    )
                }
                is Result.Err -> _state.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }
}
