package com.ytstyle.bili.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One category's page of the list. */
data class CategoryState(
    val entries: List<MessageEntry> = emptyList(),
    val cursor: MessageCursor = MessageCursor(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isEnd: Boolean = false,
    val error: String? = null,
    val errorCode: Int? = null,
) {
    val loggedOut: Boolean get() = errorCode == MSG_CODE_NOT_LOGGED_IN
}

/**
 * Screen state.
 *
 * [selected] doubles as the shortcut filter: null means the default view, which
 * is what Bilibili's own message page opens on. The default view shows the
 * 私信会话 list ([sessions]) when it has rows, and falls back to the merged
 * newest-first notification list otherwise — so a session outage still leaves a
 * usable page, with [sessionsError] as the one-line explanation.
 */
data class MessagesUiState(
    val unread: MsgUnread = MsgUnread.Zero,
    val sections: Map<MessageCategory, CategoryState> = emptySections(),
    val selected: MessageCategory? = null,
    val isRefreshing: Boolean = false,
    val loggedOut: Boolean = false,
    val hasLoaded: Boolean = false,
    val sessions: List<DirectMessageSession> = emptyList(),
    val sessionsError: String? = null,
    val sessionsLoaded: Boolean = false,
) {
    fun section(category: MessageCategory): CategoryState =
        sections[category] ?: CategoryState()
}

fun emptySections(): Map<MessageCategory, CategoryState> =
    MessageCategory.entries.associateWith { CategoryState() }

/**
 * Drives the message screen.
 *
 * All four feeds are fetched together on refresh so the merged list is
 * chronological without a second pass, the unread counters come from a fifth
 * call, and the 私信会话 list (plus its card lookup) from the VC host. A missing
 * cookie puts the screen straight into its 未登录 state instead of firing
 * doomed requests.
 */
@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val repository: MessagesRepository,
    private val session: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()

    private var loadedOnce = false

    init {
        viewModelScope.launch {
            var wasLoggedOut = true
            session.cookieFlow.collectLatest { cookie ->
                val loggedOut = cookie.isBlank()
                if (loggedOut) {
                    _state.update {
                        it.copy(
                            loggedOut = true,
                            unread = MsgUnread.Zero,
                            sections = emptySections(),
                            isRefreshing = false,
                            selected = null,
                            sessions = emptyList(),
                            sessionsError = null,
                            sessionsLoaded = false,
                        )
                    }
                } else {
                    _state.update { it.copy(loggedOut = false) }
                    if (wasLoggedOut || !loadedOnce) {
                        loadedOnce = true
                        refresh()
                    }
                }
                wasLoggedOut = loggedOut
            }
        }
    }

    /**
     * Reloads the counters, the first page of every category and the 私信会话
     * list.
     *
     * The session request runs in the same scope as the four feeds, so the page
     * fills in one pass instead of trickling; `hasLoaded` is only set once all
     * of them have settled, which is also what keeps the default view on its
     * spinner until the session list has had its chance to appear.
     */
    fun refresh() {
        val current = _state.value
        if (current.loggedOut || current.isRefreshing) return
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            loadUnread()
            coroutineScope {
                // 系统通知 is skipped: its feed endpoint answers HTTP 404 even
                // when signed in (see the screen's KDoc), so asking for it on
                // every open is a request that cannot succeed. The category is
                // still selectable, where it explains itself.
                MessageCategory.entries
                    .filter { it != MessageCategory.SYSTEM }
                    .forEach { category -> launch { loadFirstPage(category) } }
                launch { loadSessions() }
            }
            _state.update { it.copy(isRefreshing = false, hasLoaded = true) }
        }
    }

    /** Tapping a shortcut filters the list; tapping it again returns to merged. */
    fun selectCategory(category: MessageCategory?) {
        _state.update { it.copy(selected = if (it.selected == category) null else category) }
    }

    /** Appends the next page of one category. */
    fun loadMore(category: MessageCategory) {
        val snapshot = _state.value
        if (snapshot.loggedOut) return
        val section = snapshot.section(category)
        if (section.isLoadingMore || section.isEnd) return

        _state.update { it.copy(sections = it.sections + (category to it.section(category).copy(isLoadingMore = true))) }
        viewModelScope.launch {
            val cursor = _state.value.section(category).cursor
            when (val result = repository.page(category, cursor)) {
                is MsgResult.Ok -> _state.update { current ->
                    val previous = current.section(category)
                    val merged = merge(previous.entries, result.value.entries)
                    current.copy(
                        sections = current.sections + (category to previous.copy(
                            entries = merged,
                            cursor = result.value.cursor,
                            isEnd = result.value.cursor.isEnd,
                            isLoadingMore = false,
                            error = null,
                            errorCode = null,
                        )),
                    )
                }

                is MsgResult.Err -> _state.update { current ->
                    val previous = current.section(category)
                    current.copy(
                        sections = current.sections + (category to previous.copy(
                            isLoadingMore = false,
                            error = result.message,
                            errorCode = result.code,
                        )),
                    )
                }
            }
        }
    }

    /** The merged view's "加载更多": advances every category that has more. */
    fun loadMoreAll() {
        val snapshot = _state.value
        if (snapshot.loggedOut || snapshot.selected != null) return
        MessageCategory.entries.forEach { category ->
            val section = snapshot.section(category)
            if (!section.isEnd && !section.isLoadingMore) loadMore(category)
        }
    }

    private suspend fun loadUnread() {
        when (val result = repository.unread()) {
            is MsgResult.Ok -> _state.update { it.copy(unread = result.value) }
            // A badge that failed to load must not replace the feed with an
            // error panel; the feed calls report the real problem.
            is MsgResult.Err -> Unit
        }
    }

    /**
     * Loads the default view's session list.
     *
     * A failure is recorded as a one-line note, not as screen-wide state: the
     * notification feeds are still there and the user came to 消息, not to an
     * error page. The server message is kept for display only; the repository
     * has already logged the status code, and nothing here is written to the
     * log.
     */
    private suspend fun loadSessions() {
        when (val result = repository.sessions()) {
            is MsgResult.Ok -> _state.update {
                it.copy(
                    sessions = result.value,
                    sessionsError = null,
                    sessionsLoaded = true,
                )
            }
            is MsgResult.Err -> _state.update {
                it.copy(
                    sessions = emptyList(),
                    sessionsError = result.message,
                    sessionsLoaded = true,
                )
            }
        }
    }

    private suspend fun loadFirstPage(category: MessageCategory) {
        _state.update { current ->
            current.copy(
                sections = current.sections + (category to current.section(category).copy(
                    isLoading = true,
                    error = null,
                    errorCode = null,
                )),
            )
        }
        when (val result = repository.page(category, null)) {
            is MsgResult.Ok -> _state.update { current ->
                current.copy(
                    sections = current.sections + (category to current.section(category).copy(
                        entries = result.value.entries,
                        cursor = result.value.cursor,
                        isEnd = result.value.cursor.isEnd,
                        isLoading = false,
                        isLoadingMore = false,
                        error = null,
                        errorCode = null,
                    )),
                )
            }

            is MsgResult.Err -> _state.update { current ->
                val previous = current.section(category)
                current.copy(
                    sections = current.sections + (category to previous.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = result.message,
                        errorCode = result.code,
                    )),
                )
            }
        }
    }

    private fun merge(existing: List<MessageEntry>, incoming: List<MessageEntry>): List<MessageEntry> {
        if (incoming.isEmpty()) return existing
        val seen = existing.mapTo(HashSet()) { it.id }
        return existing + incoming.filter { seen.add(it.id) }
    }
}
