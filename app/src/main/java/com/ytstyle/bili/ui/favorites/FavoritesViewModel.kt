package com.ytstyle.bili.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.net.FavFolder
import com.ytstyle.bili.data.net.FavResource
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

data class FavoritesUiState(
    val folders: List<FavFolder> = emptyList(),
    val selectedFolderId: Long = 0,
    val items: List<FavResource> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val repository: BiliRepository,
    private val session: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(FavoritesUiState())
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()

    init {
        loadFolders()
    }

    private fun loadFolders() {
        viewModelScope.launch {
            val mid = session.midFlow.first()
            if (mid == 0L) {
                _state.update { it.copy(isLoading = false, error = "请先登录") }
                return@launch
            }

            when (val result = repository.favFolders(mid)) {
                is Result.Ok -> {
                    val folders = result.value.list.filter { it.id != 0L }
                    _state.update { it.copy(folders = folders) }
                    // Default to the first real folder, skipping "默认收藏夹"
                    // duplicates that sometimes share an id of 0.
                    folders.firstOrNull()?.let { selectFolder(it.id) }
                        ?: _state.update { it.copy(isLoading = false, error = "没有收藏夹") }
                }
                is Result.Err -> _state.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }

    fun selectFolder(folderId: Long) {
        _state.update { it.copy(selectedFolderId = folderId, isLoading = true, items = emptyList()) }
        viewModelScope.launch {
            when (val result = repository.favResources(folderId)) {
                is Result.Ok -> _state.update {
                    it.copy(isLoading = false, items = result.value.medias)
                }
                is Result.Err -> _state.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }

    /**
     * Favourite rows give an `aid`, not a `bvid`, and the player routes on
     * bvid. Resolve it through the view endpoint before navigating.
     */
    fun openMedia(media: FavResource, onResolved: (String) -> Unit) {
        viewModelScope.launch {
            when (val detail = repository.viewDetailByAid(media.id)) {
                is Result.Ok -> onResolved(detail.value.bvid)
                is Result.Err -> _state.update { it.copy(error = detail.message) }
            }
        }
    }
}
