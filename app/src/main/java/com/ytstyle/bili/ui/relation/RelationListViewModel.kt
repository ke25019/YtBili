package com.ytstyle.bili.ui.relation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.net.RelationUser
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which of the two lists is showing. */
enum class RelationTab(val label: String) {
    Fans("粉丝"),
    Following("关注"),
}

data class RelationListUiState(
    val tab: RelationTab = RelationTab.Fans,
    val items: List<RelationUser> = emptyList(),
    val total: Long = 0,
    val page: Int = 1,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    /** True once a page came back short — no further pages are requested. */
    val reachedEnd: Boolean = false,
    /**
     * The owner hides this list.
     *
     * Deliberately separate from an empty list: 22118 / 22115 / a null `list`
     * all mean "not visible to you", which is not the same statement as "this
     * account has nobody here".
     */
    val hidden: Boolean = false,
    /** Feedback for a 取关 attempt. */
    val notice: String? = null,
    val noticeIsError: Boolean = false,
)

/**
 * 粉丝 / 关注 二级列表.
 *
 * Both lists come from the `x/relation` endpoints with **`vmid`**, and both are
 * paginated by page number. The endpoints cap other users at 100 entries (1000
 * for one's own account) and then answer with an empty page rather than an
 * error, so paging stops on a short page instead of on a failure.
 */
@HiltViewModel
class RelationListViewModel @Inject constructor(
    private val repository: BiliRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RelationListUiState())
    val state: StateFlow<RelationListUiState> = _state.asStateFlow()

    private var mid: Long = 0

    /** Loads [tab] for [mid]; called from the screen's LaunchedEffect. */
    fun start(mid: Long, tab: RelationTab) {
        val sameTarget = this.mid == mid && _state.value.tab == tab && !_state.value.isLoading
        if (sameTarget) return
        this.mid = mid
        _state.value = RelationListUiState(tab = tab, isLoading = true)
        fetch(page = 1, append = false)
    }

    fun selectTab(tab: RelationTab) {
        if (tab == _state.value.tab) return
        _state.update {
            it.copy(
                tab = tab,
                items = emptyList(),
                total = 0,
                page = 1,
                isLoading = true,
                reachedEnd = false,
                hidden = false,
                error = null,
            )
        }
        fetch(page = 1, append = false)
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoading || current.isLoadingMore || current.reachedEnd || current.hidden) return
        _state.update { it.copy(isLoadingMore = true) }
        fetch(page = current.page + 1, append = true)
    }

    fun retry() {
        _state.update { it.copy(isLoading = true, error = null) }
        fetch(page = 1, append = false)
    }

    private fun fetch(page: Int, append: Boolean) {
        val target = mid
        if (target <= 0) {
            _state.update { it.copy(isLoading = false, error = "无效的用户") }
            return
        }
        val tab = _state.value.tab
        viewModelScope.launch {
            val result = when (tab) {
                RelationTab.Fans -> repository.fans(target, page)
                RelationTab.Following -> repository.followings(target, page)
            }
            when (result) {
                is Result.Ok -> {
                    val payload = result.value
                    // `list == null` with a zero total is how a hidden list is
                    // reported when the server chooses not to use 22115.
                    if (payload.list == null) {
                        _state.update {
                            it.copy(
                                isLoading = false,
                                isLoadingMore = false,
                                hidden = true,
                                items = emptyList(),
                                total = 0,
                                reachedEnd = true,
                            )
                        }
                        return@launch
                    }
                    // `orEmpty()` rather than a smart cast: the null case was
                    // already handled above, and this keeps the two independent.
                    val incoming = payload.list.orEmpty().filter { it.mid > 0 }
                    _state.update { current ->
                        val merged = if (append) {
                            val known = current.items.mapTo(HashSet()) { it.mid }
                            current.items + incoming.filter { known.add(it.mid) }
                        } else {
                            incoming
                        }
                        current.copy(
                            items = merged,
                            total = payload.total.takeIf { it > 0 } ?: merged.size.toLong(),
                            page = page,
                            isLoading = false,
                            isLoadingMore = false,
                            error = null,
                            // A short page is the end. The server returns an
                            // empty page past the 100/1000 cap, so this also
                            // stops paging at the cap instead of looping.
                            reachedEnd = incoming.size < RELATION_PAGE_REQUEST,
                        )
                    }
                }

                is Result.Err -> _state.update {
                    val hidden = result.code == FANS_PRIVATE_CODE || result.code == FOLLOWING_PRIVATE_CODE
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        hidden = hidden,
                        error = if (hidden) null else result.message,
                        reachedEnd = true,
                    )
                }
            }
        }
    }

    /**
     * 取关 one row.
     *
     * Optimistic, and the row is removed on success rather than merely restyled:
     * this screen *is* the follow list, so a person who is no longer followed no
     * longer belongs in it.
     */
    fun unfollow(user: RelationUser) {
        if (!user.followed) return
        val before = _state.value.items
        _state.update {
            it.copy(
                items = before.filterNot { row -> row.mid == user.mid },
                total = (it.total - 1).coerceAtLeast(0),
                notice = "已取消关注 ${user.uname}",
                noticeIsError = false,
            )
        }
        viewModelScope.launch {
            when (val result = repository.modifyRelation(user.mid, UNFOLLOW_ACT)) {
                is Result.Ok -> Unit
                is Result.Err -> _state.update {
                    // Put the row back where it was: the list is ordered by the
                    // server, so appending it would quietly reorder the page.
                    it.copy(
                        items = before,
                        total = it.total + 1,
                        notice = result.message,
                        noticeIsError = true,
                    )
                }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private companion object {
        /** `act = 2` is 取消关注 per `x/relation/modify`. */
        const val UNFOLLOW_ACT = 2

        /** Matches the repository's page size; a shorter page means the end. */
        const val RELATION_PAGE_REQUEST = 20

        /** `x/relation/fans`: 该用户隐私设置，粉丝列表不可见. */
        const val FANS_PRIVATE_CODE = 22118

        /** `x/relation/followings`: 用户已设置隐私，无法查看. */
        const val FOLLOWING_PRIVATE_CODE = 22115
    }
}
