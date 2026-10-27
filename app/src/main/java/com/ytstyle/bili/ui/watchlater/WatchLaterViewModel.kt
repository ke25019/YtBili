package com.ytstyle.bili.ui.watchlater

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.net.WatchLaterItem
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WatchLaterUiState(
    val items: List<WatchLaterItem> = emptyList(),
    val count: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class WatchLaterViewModel @Inject constructor(
    private val repository: BiliRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WatchLaterUiState())
    val state: StateFlow<WatchLaterUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.watchLater()) {
                is Result.Ok -> _state.update {
                    it.copy(
                        items = result.value.list,
                        count = result.value.count,
                        isLoading = false,
                    )
                }
                is Result.Err -> _state.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }
}
