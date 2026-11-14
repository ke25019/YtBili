package com.ytstyle.bili.ui.dynamic

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.net.DynamicApi
import com.ytstyle.bili.data.net.DynamicEntry
import com.ytstyle.bili.data.net.Reply
import com.ytstyle.bili.data.net.parseDynamicDetail
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The card the user tapped, handed straight to the detail screen.
 *
 * The feed already parsed everything the card shows, so re-fetching before
 * painting would make every open flash a spinner. The detail request then fills
 * in what the card omitted and never replaces a richer body with a poorer one.
 */
object DynamicDetailSelection {
    var entry: DynamicEntry? = null
}

/** Everything the 图文详情 page renders. */
data class DynamicDetailUiState(
    val entry: DynamicEntry? = null,
    val comments: List<Reply> = emptyList(),
    val commentsLoading: Boolean = false,
    val commentsError: String? = null,
    val liked: Boolean = false,
    val likeCount: Long = 0,
    val notice: String? = null,
) {
    val images: List<String> get() = entry?.imageUrls.orEmpty()
}

/**
 * Drives one 图文动态 page.
 *
 * Two independent fills: the body (handed over first, refined by
 * `polymer/.../detail`) and the comments (`x/v2/reply/wbi/main` with `type=17`).
 * A failure in either leaves the other usable — a detail page that cannot load
 * its comments still shows the post, which is the part the user tapped.
 */
@HiltViewModel
class DynamicDetailViewModel @Inject constructor(
    private val api: DynamicApi,
    private val repository: BiliRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val dynId: String = savedStateHandle.get<String>("dynId").orEmpty()

    private val _state = MutableStateFlow(DynamicDetailUiState())
    val state = _state.asStateFlow()

    init {
        val handed = DynamicDetailSelection.entry?.takeIf { it.id == dynId }
        _state.update { it.copy(entry = handed, likeCount = handed?.likeCount ?: 0L) }
        loadDetail()
        loadComments()
    }

    /**
     * Asks for the full body and keeps whichever version is longer.
     *
     * The feed truncates long posts (`展开` on the card) and can omit images, so
     * the answer is worth having; but when it fails or arrives thinner than the
     * card, the card's copy stays.
     */
    private fun loadDetail() {
        viewModelScope.launch {
            val response = runCatching { api.detail(dynId) }.getOrNull() ?: return@launch
            if (response.code != 0) return@launch
            val fetched = parseDynamicDetail(response.data) ?: return@launch
            _state.update { current ->
                val shown = current.entry
                val richer = if (shown == null || fetched.text.length >= shown.text.length) {
                    fetched
                } else {
                    shown
                }
                current.copy(
                    entry = richer,
                    likeCount = maxOf(current.likeCount, richer.likeCount),
                )
            }
        }
    }

    private fun loadComments() {
        if (dynId.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(commentsLoading = true, commentsError = null) }
            when (val result = repository.dynamicReplies(dynId)) {
                is Result.Ok -> _state.update {
                    it.copy(commentsLoading = false, comments = result.value.replies)
                }

                is Result.Err -> _state.update {
                    it.copy(commentsLoading = false, commentsError = result.message)
                }
            }
        }
    }

    fun retryComments() = loadComments()

    /**
     * Optimistic like, rolled back with the server's own message.
     *
     * `thumbDynamic` sends an explicit 1/2 rather than a toggle precisely so a
     * failed write can be undone without guessing what the server holds.
     */
    fun toggleLike() {
        val entry = _state.value.entry ?: return
        val target = !_state.value.liked
        _state.update {
            it.copy(liked = target, likeCount = it.likeCount + if (target) 1 else -1)
        }
        viewModelScope.launch {
            when (val result = repository.thumbDynamic(entry.id, target)) {
                is Result.Ok -> Unit
                is Result.Err -> _state.update {
                    it.copy(
                        liked = !target,
                        likeCount = it.likeCount + if (target) -1 else 1,
                        notice = result.message,
                    )
                }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }
}
