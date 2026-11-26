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
    /** The comment being composed. */
    val commentInput: String = "",
    val isSendingComment: Boolean = false,
    /** Result of the last send attempt, shown under the composer. */
    val commentFeedback: String? = null,
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
        _state.update {
            it.copy(
                entry = handed,
                likeCount = handed?.likeCount ?: 0L,
                // The card already knows whether this account liked the post;
                // seeding it stops the heart from resetting on every open.
                liked = handed?.liked == true,
            )
        }
        loadDetail()
    }

    /**
     * Asks for the full body and keeps whichever version is richer.
     *
     * The feed truncates long posts (`展开` on the card) and can omit images, so
     * the answer is worth having; but when it fails or arrives thinner than the
     * card, the card's copy stays. Counters are merged with `max` because the
     * detail payload does not always carry `module_stat` — replacing the card
     * wholesale turned "2692 点赞 / 210 评论" into zeros on open.
     */
    private fun loadDetail() {
        viewModelScope.launch {
            val response = runCatching { api.detail(dynId) }.getOrNull()
            if (response?.code == 0) {
                val fetched = parseDynamicDetail(response.data)
                if (fetched != null) {
                    _state.update { current ->
                        val merged = mergeEntries(current.entry, fetched)
                        current.copy(
                            entry = merged,
                            likeCount = maxOf(current.likeCount, merged.likeCount),
                            liked = current.liked || merged.liked,
                        )
                    }
                }
            }
            // Comments are only requested now: an 图文 dynamic's real comment
            // target (`basic.comment_type` / `comment_id_str`) arrives with this
            // payload, and guessing `type=17` before it lands was the `-404`.
            loadComments()
        }
    }

    private fun mergeEntries(base: DynamicEntry?, fresh: DynamicEntry): DynamicEntry {
        if (base == null) return fresh
        return fresh.copy(
            text = if (fresh.text.length >= base.text.length) fresh.text else base.text,
            imageUrls = fresh.imageUrls.ifEmpty { base.imageUrls },
            videoBvid = fresh.videoBvid.ifBlank { base.videoBvid },
            videoTitle = fresh.videoTitle.ifBlank { base.videoTitle },
            videoCover = fresh.videoCover.ifBlank { base.videoCover },
            forwardText = fresh.forwardText.ifBlank { base.forwardText },
            forwardImageUrls = fresh.forwardImageUrls.ifEmpty { base.forwardImageUrls },
            authorName = fresh.authorName.ifBlank { base.authorName },
            authorFace = fresh.authorFace.ifBlank { base.authorFace },
            authorMid = fresh.authorMid.takeIf { it > 0 } ?: base.authorMid,
            pubTimeLabel = fresh.pubTimeLabel.ifBlank { base.pubTimeLabel },
            likeCount = maxOf(base.likeCount, fresh.likeCount),
            commentCount = maxOf(base.commentCount, fresh.commentCount),
            forwardCount = maxOf(base.forwardCount, fresh.forwardCount),
            liked = base.liked || fresh.liked,
            commentType = fresh.commentType.takeIf { it > 0 } ?: base.commentType,
            commentOid = fresh.commentOid.takeIf { it > 0 } ?: base.commentOid,
        )
    }

    private fun loadComments() {
        if (dynId.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(commentsLoading = true, commentsError = null) }
            val entry = _state.value.entry
            when (
                val result = repository.dynamicReplies(
                    dynId = dynId,
                    type = entry?.commentType ?: 0,
                    oid = entry?.commentOid ?: 0L,
                )
            ) {
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

    fun onCommentInputChange(text: String) {
        _state.update { it.copy(commentInput = text, commentFeedback = null) }
    }

    /**
     * Posts the composed comment.
     *
     * The server's own reply is prepended rather than a placeholder bubble being
     * invented: it carries the real `rpid`, timestamp and member card, so it
     * behaves like every other row immediately.
     */
    fun sendComment() {
        val entry = _state.value.entry ?: return
        val text = _state.value.commentInput.trim()
        if (text.isEmpty() || _state.value.isSendingComment) return
        _state.update { it.copy(isSendingComment = true, commentFeedback = null) }
        viewModelScope.launch {
            when (
                val result = repository.commentDynamic(
                    dynId = dynId,
                    message = text,
                    type = entry.commentType,
                    oid = entry.commentOid,
                )
            ) {
                is Result.Ok -> _state.update { current ->
                    val updated = current.entry?.copy(
                        commentCount = current.entry.commentCount + 1,
                    )
                    current.copy(
                        entry = updated,
                        comments = listOf(result.value) + current.comments,
                        commentInput = "",
                        isSendingComment = false,
                        commentFeedback = "已发送",
                    )
                }

                is Result.Err -> _state.update {
                    it.copy(
                        isSendingComment = false,
                        commentFeedback = result.message,
                    )
                }
            }
        }
    }

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
