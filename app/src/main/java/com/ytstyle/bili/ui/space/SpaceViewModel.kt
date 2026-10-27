package com.ytstyle.bili.ui.space

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.net.SpaceProfile
import com.ytstyle.bili.data.net.SpaceVideo
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import com.ytstyle.bili.data.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpaceUiState(
    val profile: SpaceProfile? = null,
    val videos: List<SpaceVideo> = emptyList(),
    val followers: Long = 0,
    val following: Long = 0,
    val isFollowing: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val notice: String? = null,
    /** True when this is the signed-in user's own space. */
    val isSelf: Boolean = false,
)

@HiltViewModel
class SpaceViewModel @Inject constructor(
    private val repository: BiliRepository,
    private val session: SessionStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val mid: Long = savedStateHandle.get<String>("mid")?.toLongOrNull() ?: 0L

    private val _state = MutableStateFlow(SpaceUiState())
    val state: StateFlow<SpaceUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (mid <= 0) {
            _state.update { it.copy(isLoading = false, error = "无效的用户 ID") }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            // Following yourself is rejected by the API; hide the button instead
            // of letting the user hit that error.
            val ownMid = session.midFlow.first()
            _state.update { it.copy(isSelf = ownMid != 0L && ownMid == mid) }

            // Profile, counters and uploads are independent calls.
            val profileDeferred = async { repository.spaceInfo(mid) }
            val statDeferred = async { repository.relationStat(mid) }
            val videosDeferred = async { repository.spaceVideos(mid) }
            val followDeferred = async { repository.isFollowing(mid) }

            when (val profile = profileDeferred.await()) {
                is Result.Ok -> _state.update { it.copy(profile = profile.value) }
                is Result.Err -> _state.update { it.copy(error = profile.message) }
            }

            (statDeferred.await() as? Result.Ok)?.let { stat ->
                _state.update {
                    it.copy(followers = stat.value.follower, following = stat.value.following)
                }
            }

            (videosDeferred.await() as? Result.Ok)?.let { videos ->
                _state.update { it.copy(videos = videos.value.list?.vlist.orEmpty()) }
            }

            (followDeferred.await() as? Result.Ok)?.let { following ->
                _state.update { it.copy(isFollowing = following.value) }
            }

            _state.update { it.copy(isLoading = false) }
        }
    }

    fun toggleFollow() {
        if (mid <= 0) return
        val target = !_state.value.isFollowing
        _state.update { it.copy(isFollowing = target) }
        viewModelScope.launch {
            val result = repository.modifyRelation(mid, if (target) 1 else 2)
            if (result is Result.Err) {
                _state.update { it.copy(isFollowing = !target, error = result.message) }
            } else {
                _state.update {
                    it.copy(notice = if (target) "已关注" else "已取消关注")
                }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun roomId(): Long = _state.value.profile?.liveRoom?.roomid ?: 0L

    fun isLive(): Boolean = _state.value.profile?.liveRoom?.liveStatus == 1
}
