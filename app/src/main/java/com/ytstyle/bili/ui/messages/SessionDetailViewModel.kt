package com.ytstyle.bili.ui.messages

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.log.AppLog
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionDetailUiState(
    val peerName: String = "",
    /** The peer's avatar, shown beside incoming bubbles. */
    val peerFace: String = "",
    /** Oldest first, so the list reads top-to-bottom. */
    val messages: List<SessionMessage> = emptyList(),
    val emotes: Map<String, String> = emptyMap(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val loggedOut: Boolean = false,
    /** Text in the composer. */
    val input: String = "",
    val isSending: Boolean = false,
    /** Result of the last send attempt, shown under the composer. */
    val sendFeedback: String? = null,
    val sendFeedbackIsError: Boolean = false,
)

/**
 * Drives one 私信会话.
 *
 * Paging goes backwards in time only: the first load is the newest page, and
 * "加载更早的消息" prepends the page before the current `min_seqno`. Reading a
 * session does not mark it read, so [markRead] is called separately — and its
 * failure stays silent, because the messages are already on screen.
 */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val repository: MessagesRepository,
    /**
     * Sending lives here rather than in [MessagesRepository]: it is a signed
     * write, and the WBI keys, the CSRF token and the key-refresh helper all
     * belong to this one, the same way every other write in the app uses it.
     */
    private val bili: BiliRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val talkerId: Long = savedStateHandle.get<String>("talkerId")?.toLongOrNull() ?: 0L
    private val sessionType: Int = savedStateHandle.get<String>("sessionType")?.toIntOrNull() ?: 1
    /**
     * `-` is the placeholder the route uses when the list had no name for this
     * peer: a blank path segment would not match the route at all, and that is
     * one way "私信进不去" happens.
     */
    private val peerName: String = savedStateHandle.get<String>("peerName")
        .orEmpty()
        .takeIf { it != EMPTY_NAME_PLACEHOLDER }
        .orEmpty()

    private val _state = MutableStateFlow(SessionDetailUiState(peerName = peerName))
    val state: StateFlow<SessionDetailUiState> = _state.asStateFlow()

    /** Cursor for the next (older) page. */
    private var minSeqno: Long = 0

    private companion object {
        /** Kept in sync with `Routes.sessionDetail`. */
        const val EMPTY_NAME_PLACEHOLDER = "-"
    }

    init {
        load(endSeqno = null)
        loadPeerCard()
    }

    /**
     * Resolves the peer's name and avatar independently of the route.
     *
     * Failure is cosmetic and stays out of the error state: the messages are
     * what the user came for, and a missing avatar must not blank the page.
     */
    private fun loadPeerCard() {
        if (talkerId <= 0) return
        viewModelScope.launch {
            when (val result = repository.peerCard(talkerId)) {
                is MsgResult.Ok -> {
                    val (name, face) = result.value
                    _state.update { current ->
                        current.copy(
                            peerName = current.peerName.ifBlank { name },
                            peerFace = face.ifBlank { current.peerFace },
                        )
                    }
                }
                is MsgResult.Err -> AppLog.w("msg", "peer card code=${result.code}")
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoading || current.isLoadingMore || !current.hasMore) return
        load(endSeqno = minSeqno)
    }

    fun retry() = load(endSeqno = null)

    /**
     * @param silent keep the list on screen instead of showing the spinner. Used
     *   by the post-send refresh, where the messages are already visible and
     *   flashing a full-page spinner would be worse than a moment of staleness.
     */
    private fun load(endSeqno: Long?, silent: Boolean = false) {
        val firstPage = endSeqno == null
        _state.update {
            it.copy(
                isLoading = firstPage && !silent,
                isLoadingMore = !firstPage,
                error = null,
            )
        }

        viewModelScope.launch {
            when (val result = repository.sessionMessages(talkerId, sessionType, endSeqno)) {
                is MsgResult.Ok -> {
                    val page = result.value
                    minSeqno = page.minSeqno
                    _state.update { current ->
                        val merged = if (firstPage) {
                            page.messages
                        } else {
                            // Prepending can overlap the page already held; the
                            // key is the message's own id, so dedupe on it.
                            val seen = current.messages.mapTo(HashSet()) { it.key }
                            page.messages.filterNot { seen.contains(it.key) } + current.messages
                        }
                        current.copy(
                            messages = merged,
                            emotes = page.emotes.ifEmpty { current.emotes },
                            hasMore = page.hasMore,
                            isLoading = false,
                            isLoadingMore = false,
                            error = null,
                            loggedOut = false,
                        )
                    }
                    if (firstPage) markRead(page.messages.lastOrNull()?.seqno)
                }

                is MsgResult.Err -> {
                    AppLog.w("msg", "fetch_session_msgs code=${result.code}")
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = result.message,
                            loggedOut = result.code == MSG_CODE_NOT_LOGGED_IN,
                        )
                    }
                }
            }
        }
    }

    fun onInputChange(text: String) = _state.update { it.copy(input = text, sendFeedback = null) }

    /**
     * Sends the composed text.
     *
     * The newest page is refetched rather than a bubble being fabricated: the
     * message exists server-side only after this succeeds, and reloading puts it
     * in the right place with the sequence number it was actually assigned. That
     * refresh is [load]'s silent mode, so the conversation does not disappear
     * behind a spinner at the moment the user is looking for their own message.
     *
     * The text is restored on failure — losing a long message to a network blip
     * and making the user retype it is not acceptable.
     */
    fun send() {
        val current = _state.value
        val text = current.input.trim()
        if (text.isEmpty() || current.isSending) return
        if (talkerId <= 0) {
            _state.update {
                it.copy(sendFeedback = "会话信息缺失", sendFeedbackIsError = true)
            }
            return
        }

        _state.update { it.copy(isSending = true, input = "", sendFeedback = null) }
        viewModelScope.launch {
            when (val result = bili.sendDirectMessage(talkerId, text)) {
                is Result.Ok -> {
                    _state.update {
                        it.copy(
                            isSending = false,
                            sendFeedback = "已发送",
                            sendFeedbackIsError = false,
                        )
                    }
                    load(endSeqno = null, silent = true)
                }

                is Result.Err -> _state.update {
                    it.copy(
                        isSending = false,
                        input = if (it.input.isBlank()) text else it.input,
                        sendFeedback = result.message,
                        sendFeedbackIsError = true,
                    )
                }
            }
        }
    }

    fun dismissSendFeedback() = _state.update { it.copy(sendFeedback = null) }

    private fun markRead(ackSeqno: Long?) {
        viewModelScope.launch {
            when (val result = repository.markSessionRead(talkerId, sessionType, ackSeqno)) {
                is MsgResult.Ok -> Unit
                // The list is already on screen; an unread badge is not worth
                // an error panel.
                is MsgResult.Err -> AppLog.w("msg", "update_ack code=${result.code}")
            }
        }
    }
}
