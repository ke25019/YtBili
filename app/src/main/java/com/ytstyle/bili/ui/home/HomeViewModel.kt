package com.ytstyle.bili.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.net.RecommendItem
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import com.ytstyle.bili.data.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val items: List<RecommendItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val selectedChip: Int = 0,
    /** Experimental: render the feed as a two-column grid. */
    val useGrid: Boolean = false,
)

/** YouTube's home has a horizontal chip row above the feed; we mirror that. */
val HOME_CHIPS = listOf("全部", "动画", "游戏", "音乐", "科技", "生活", "影视", "知识")

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: BiliRepository,
    settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var freshIdx = 1

    init {
        load()
        // The grid toggle lives in settings; react to it without recreating
        // the screen.
        viewModelScope.launch {
            settings.homeGrid.collect { enabled ->
                _state.update { it.copy(useGrid = enabled) }
            }
        }
    }

    fun load() {
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.recommend(pageSize = 20, freshIdx = freshIdx)) {
                is Result.Ok -> {
                    freshIdx++
                    _state.update {
                        it.copy(
                            items = it.items + result.value,
                            isLoading = false,
                            isRefreshing = false,
                            error = null,
                        )
                    }
                }
                is Result.Err -> _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = result.message,
                    )
                }
            }
        }
    }

    /** Pull-to-refresh replaces the feed rather than appending. */
    fun refresh() {
        freshIdx = 1
        _state.update { it.copy(items = emptyList(), isRefreshing = true) }
        load()
    }

    fun selectChip(index: Int) {
        // Bilibili's recommendation endpoint is not category-parameterised for
        // this feed, so the chip swaps the visible label and refetches fresh
        // content instead of pretending to filter server-side.
        _state.update { it.copy(selectedChip = index, items = emptyList()) }
        refresh()
    }
}
