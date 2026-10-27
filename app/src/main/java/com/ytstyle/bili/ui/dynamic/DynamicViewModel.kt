package com.ytstyle.bili.ui.dynamic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.net.SpaceVideo
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import com.ytstyle.bili.data.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DynamicUiState(
    val videos: List<SpaceVideo> = emptyList(),
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    /** Space listings do not carry an uploader name, so it is supplied here. */
    val uploaderName: String = "",
)

@HiltViewModel
class DynamicViewModel @Inject constructor(
    private val repository: BiliRepository,
    private val session: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(DynamicUiState())
    val state: StateFlow<DynamicUiState> = _state.asStateFlow()

    init {
        load()
    }

    /**
     * The public API has no "feed of followed uploaders" endpoint, so this
     * resolves the user's own mid and loads their space as a stand-in. Wiring
     * a real following-feed needs `x/polymer/web-dynamic/v1/feed/all`, which
     * is not reachable without the web session's additional scopes.
     */
    private fun load() {
        viewModelScope.launch {
            val mid = session.midFlow.first()
            if (mid == 0L) {
                _state.update { it.copy(isLoggedIn = false, isLoading = false) }
                return@launch
            }

            // The uploader name is not part of the space video payload.
            val uname = runCatching { session.unameFlow.first() }.getOrDefault("")

            when (val result = repository.spaceVideos(mid)) {
                is Result.Ok -> _state.update {
                    it.copy(
                        isLoggedIn = true,
                        isLoading = false,
                        videos = result.value.list?.vlist.orEmpty(),
                        uploaderName = uname,
                    )
                }
                is Result.Err -> _state.update {
                    it.copy(isLoggedIn = true, isLoading = false, error = result.message)
                }
            }
        }
    }
}
