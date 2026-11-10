package com.ytstyle.bili.ui.messages

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MessageCategoryUiState(
    val entries: List<MessageEntry> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isEnd: Boolean = false,
    val error: String? = null,
    val loggedOut: Boolean = false,
    /**
     * 系统通知's feed endpoint answers HTTP 404 — it has been withdrawn, so the
     * page explains that instead of showing an error. See
     * [MessageCategoryScreen].
     */
    val unavailable: Boolean = false,
)

/**
 * One notification category, as its own page.
 *
 * Paging is cursor-based and identical to the merged list's: the first page is
 * requested with no cursor, and each response's `cursor.{id,time}` is sent back
 * for the next one. Kept separate from [MessagesViewModel] so opening a category
 * does not refetch the other three.
 */
@HiltViewModel
class MessageCategoryViewModel @Inject constructor(
    private val repository: MessagesRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val category: MessageCategory = runCatching {
        MessageCategory.valueOf(savedStateHandle.get<String>("category").orEmpty())
    }.getOrDefault(MessageCategory.REPLY)

    private val _state = MutableStateFlow(
        MessageCategoryUiState(unavailable = category == MessageCategory.SYSTEM),
    )
    val state: StateFlow<MessageCategoryUiState> = _state.asStateFlow()

    private var cursor: MessageCursor = MessageCursor()

    init {
        // 系统通知 is not requested at all: the endpoint is gone, and firing it
        // on every visit only produces a 404 in the log.
        if (category != MessageCategory.SYSTEM) loadFirstPage()
    }

    fun loadMore() {
        val current = _state.value
        if (current.unavailable || current.isLoading || current.isLoadingMore || current.isEnd) return
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            when (val result = repository.page(category, cursor)) {
                is MsgResult.Ok -> {
                    cursor = result.value.cursor
                    _state.update { state ->
                        state.copy(
                            entries = merge(state.entries, result.value.entries),
                            isEnd = result.value.cursor.isEnd,
                            isLoadingMore = false,
                            error = null,
                        )
                    }
                }
                is MsgResult.Err -> _state.update {
                    it.copy(isLoadingMore = false, error = result.message)
                }
            }
        }
    }

    fun retry() = loadFirstPage()

    private fun loadFirstPage() {
        _state.update { it.copy(isLoading = true, error = null, loggedOut = false) }
        viewModelScope.launch {
            when (val result = repository.page(category, null)) {
                is MsgResult.Ok -> {
                    cursor = result.value.cursor
                    _state.update {
                        it.copy(
                            entries = result.value.entries,
                            isEnd = result.value.cursor.isEnd,
                            isLoading = false,
                            error = null,
                            loggedOut = false,
                        )
                    }
                }
                is MsgResult.Err -> {
                    AppLog.w("msg", "category ${category.name} code=${result.code}")
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = result.message,
                            loggedOut = result.code == MSG_CODE_NOT_LOGGED_IN,
                        )
                    }
                }
            }
        }
    }

    /** Cursor paging can overlap; a duplicate list key is a crash. */
    private fun merge(existing: List<MessageEntry>, incoming: List<MessageEntry>): List<MessageEntry> {
        if (incoming.isEmpty()) return existing
        val seen = existing.mapTo(HashSet()) { it.id }
        return existing + incoming.filter { seen.add(it.id) }
    }
}
