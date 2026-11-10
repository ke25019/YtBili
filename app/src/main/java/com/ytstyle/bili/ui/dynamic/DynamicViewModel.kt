package com.ytstyle.bili.ui.dynamic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.log.AppLog
import com.ytstyle.bili.data.net.BiliResponse
import com.ytstyle.bili.data.net.DynamicApi
import com.ytstyle.bili.data.net.DynamicEntry
import com.ytstyle.bili.data.net.DynamicFeedPage
import com.ytstyle.bili.data.net.DynamicKind
import com.ytstyle.bili.data.net.DynamicRailUser
import com.ytstyle.bili.data.net.parseDynamicPage
import com.ytstyle.bili.data.net.parseFollowings
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import com.ytstyle.bili.data.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject

/**
 * The feed filters offered at the top of the page.
 *
 * `up` is deliberately missing: the official tab bar shows one, but
 * `feed/all` documents only `all` / `video` / `pgc` / `article`, and a tab that
 * can only ever answer with an error is worse than no tab.
 */
enum class DynamicTab(val label: String, val type: String) {
    ALL("全部", "all"),
    VIDEO("投稿", "video"),
    PGC("番剧", "pgc"),
    ARTICLE("专栏", "article"),
}

/** What the left rail currently points at. */
sealed interface DynamicRail {
    /** Client-side filter of the loaded feed down to live items. */
    data object Live : DynamicRail
    data object All : DynamicRail
    data object Mine : DynamicRail
    data class User(val mid: Long, val name: String) : DynamicRail
}

/**
 * Optimistic counters for one dynamic.
 *
 * The feed's `module_stat` is a snapshot taken when the page was fetched, so a
 * like / repost / comment has to be drawn as `snapshot + delta` instead of by
 * mutating the entry — the same rule the video page follows for its own
 * counters.
 */
data class DynamicDelta(
    val like: Int = 0,
    val comment: Int = 0,
    val forward: Int = 0,
)

data class DynamicUiState(
    val tab: DynamicTab = DynamicTab.ALL,
    val rail: DynamicRail = DynamicRail.All,
    val railUsers: List<DynamicRailUser> = emptyList(),
    val entries: List<DynamicEntry> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    /** Dynamics liked during this session, so the icon can start filled. */
    val likedIds: Set<String> = emptySet(),
    val deltas: Map<String, DynamicDelta> = emptyMap(),
    /** Result of the last write; shown briefly, then dismissed. */
    val feedback: String? = null,
    val feedbackIsError: Boolean = false,
    val notLoggedIn: Boolean = false,
) {
    /**
     * `Live(N)` in the rail: distinct uploaders with a live dynamic in what has
     * been loaded. Derived rather than requested — there is no endpoint that
     * answers "how many of my followings are live" for a third-party client.
     */
    val liveCount: Int
        get() = entries
            .filter { it.kind == DynamicKind.LIVE }
            .map { it.authorMid }
            .distinct()
            .size
}

/**
 * Drives the 动态 page.
 *
 * One page of `feed/all` (or `feed/space` for a single uploader) at a time, kept
 * in a per-selection cache so switching tabs is instant, plus the followed
 * uploaders for the rail. `loadToken` guards against a slow response landing
 * after the user has already moved on and overwriting the wrong list.
 */
@HiltViewModel
class DynamicViewModel @Inject constructor(
    private val api: DynamicApi,
    private val session: SessionStore,
    private val repository: BiliRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DynamicUiState())
    val state: StateFlow<DynamicUiState> = _state.asStateFlow()

    private data class Snapshot(
        val entries: List<DynamicEntry>,
        val offset: String,
        val hasMore: Boolean,
    )

    private val cache = mutableMapOf<String, Snapshot>()

    /** `-1` = not read yet, `0` = signed out. */
    private var mid: Long = -1L
    private var loadToken = 0

    init {
        load(reset = true)
        loadRail()
    }

    fun selectTab(tab: DynamicTab) {
        if (tab == _state.value.tab) return
        _state.update { it.copy(tab = tab, error = null) }
        load(reset = true)
    }

    fun selectRail(rail: DynamicRail) {
        if (rail == _state.value.rail) return
        _state.update { it.copy(rail = rail, error = null) }
        load(reset = true)
    }

    /** Pull-to-refresh / the error panel's 重试: drops the cache for this view. */
    fun refresh() {
        cache.remove(keyOf(_state.value.rail, _state.value.tab))
        // Someone may have signed in or out since the last read.
        mid = -1L
        load(reset = true)
        loadRail()
    }

    fun loadMore() {
        if (_state.value.isLoading || _state.value.isLoadingMore || !_state.value.hasMore) return
        load(reset = false)
    }

    // ---- write side ----
    //
    // All three follow the same shape: apply the change locally first, then send
    // it, and undo the local change if the server refuses. Without the undo a
    // failed like leaves a filled icon that will never match the server again.

    fun toggleLike(entry: DynamicEntry) {
        if (entry.id.isBlank()) return
        val liked = entry.id in _state.value.likedIds
        val target = !liked
        applyLike(entry.id, target)

        viewModelScope.launch {
            when (val result = repository.thumbDynamic(entry.id, target)) {
                is Result.Ok -> Unit
                is Result.Err -> {
                    applyLike(entry.id, !target)
                    AppLog.w("dynamic", "thumb failed code=${result.code}")
                    showFeedback(result.message, isError = true)
                }
            }
        }
    }

    fun repost(entry: DynamicEntry, text: String) {
        if (entry.id.isBlank()) return
        applyDelta(entry.id) { it.copy(forward = it.forward + 1) }
        viewModelScope.launch {
            when (val result = repository.repostDynamic(entry.id, text)) {
                is Result.Ok -> showFeedback("已转发", isError = false)
                is Result.Err -> {
                    applyDelta(entry.id) { it.copy(forward = it.forward - 1) }
                    AppLog.w("dynamic", "repost failed code=${result.code}")
                    showFeedback(result.message, isError = true)
                }
            }
        }
    }

    fun comment(entry: DynamicEntry, text: String) {
        if (entry.id.isBlank() || text.isBlank()) return
        applyDelta(entry.id) { it.copy(comment = it.comment + 1) }
        viewModelScope.launch {
            when (val result = repository.commentDynamic(entry.id, text)) {
                is Result.Ok -> showFeedback("评论已发送", isError = false)
                is Result.Err -> {
                    applyDelta(entry.id) { it.copy(comment = it.comment - 1) }
                    AppLog.w("dynamic", "comment failed code=${result.code}")
                    showFeedback(result.message, isError = true)
                }
            }
        }
    }

    fun dismissFeedback() = _state.update { it.copy(feedback = null) }

    private fun applyLike(id: String, liked: Boolean) = _state.update { current ->
        current.copy(
            likedIds = if (liked) current.likedIds + id else current.likedIds - id,
            deltas = current.deltas.withDelta(id) {
                it.copy(like = it.like + if (liked) 1 else -1)
            },
        )
    }

    private fun applyDelta(id: String, change: (DynamicDelta) -> DynamicDelta) =
        _state.update { current -> current.copy(deltas = current.deltas.withDelta(id, change)) }

    private fun Map<String, DynamicDelta>.withDelta(
        id: String,
        change: (DynamicDelta) -> DynamicDelta,
    ): Map<String, DynamicDelta> = this + (id to change(this[id] ?: DynamicDelta()))

    private fun showFeedback(message: String, isError: Boolean) =
        _state.update { it.copy(feedback = message, feedbackIsError = isError) }

    /**
     * Loads the first page of the current selection, or the next page when
     * [reset] is false. A cached first page is published without a request.
     */
    private fun load(reset: Boolean) {
        val token = ++loadToken
        val rail = _state.value.rail
        val tab = _state.value.tab
        val key = keyOf(rail, tab)

        viewModelScope.launch {
            if (reset) {
                val cached = cache[key]
                if (cached != null) {
                    publish(rail, cached, token, fromCache = true)
                    return@launch
                }
                _state.update {
                    it.copy(isLoading = true, isLoadingMore = false, error = null, notLoggedIn = false)
                }
            } else {
                val cached = cache[key] ?: return@launch
                if (!cached.hasMore) return@launch
                _state.update { it.copy(isLoadingMore = true) }
            }

            if (mid < 0L) mid = runCatching { session.midFlow.first() }.getOrDefault(0L)

            // `feed/space` is public, `feed/all` is not: only the follow feed
            // and the (client-side) live filter need a session.
            val needsLogin = rail == DynamicRail.All || rail == DynamicRail.Live
            if (needsLogin && mid == 0L) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        notLoggedIn = true,
                        entries = emptyList(),
                        error = null,
                    )
                }
                return@launch
            }

            val offset = if (reset) "" else cache[key]?.offset.orEmpty()
            val page = when (rail) {
                is DynamicRail.User -> fetchPage { api.feedSpace(hostMid = rail.mid, offset = offset) }
                DynamicRail.Mine -> fetchPage { api.feedSpace(hostMid = mid, offset = offset) }
                else -> fetchPage { api.feedAll(type = tab.type, offset = offset) }
            }

            when (page) {
                is Result.Ok -> {
                    val previous = if (reset) emptyList() else cache[key]?.entries.orEmpty()
                    val merged = dedupe(previous, page.value.entries)
                    val snapshot = Snapshot(merged, page.value.offset, page.value.hasMore)
                    cache[key] = snapshot
                    publish(rail, snapshot, token, fromCache = false)
                }

                is Result.Err -> {
                    if (token != loadToken) return@launch
                    val message = page.message
                    AppLog.w(
                        "dynamic",
                        "feed rail=${rail::class.simpleName} type=${tab.type} " +
                            "code=${page.code}",
                    )
                    _state.update { current ->
                        current.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            // A failed "load more" keeps what is on screen and
                            // says nothing; only an empty first page is worth
                            // an error panel.
                            error = if (current.entries.isEmpty()) message else null,
                        )
                    }
                }
            }
        }
    }

    private fun publish(rail: DynamicRail, snapshot: Snapshot, token: Int, fromCache: Boolean) {
        if (token != loadToken) return
        _state.update {
            it.copy(
                entries = visible(rail, snapshot.entries),
                isLoading = false,
                isLoadingMore = false,
                hasMore = snapshot.hasMore,
                notLoggedIn = false,
                error = if (fromCache) it.error else null,
            )
        }
    }

    /** The followed uploaders for the rail; a failure just leaves it short. */
    private fun loadRail() {
        viewModelScope.launch {
            if (mid < 0L) mid = runCatching { session.midFlow.first() }.getOrDefault(0L)
            if (mid == 0L) return@launch
            val users = withContext(Dispatchers.IO) {
                try {
                    val response = api.followings(vmid = mid, ps = RAIL_PAGE_SIZE)
                    if (response.code == 0 && response.data != null) {
                        parseFollowings(response.data)
                    } else {
                        AppLog.w("dynamic", "followings code=${response.code}")
                        emptyList()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("dynamic", "followings failed ${e.javaClass.simpleName}")
                    emptyList()
                }
            }
            _state.update { it.copy(railUsers = users) }
        }
    }

    private suspend fun fetchPage(
        block: suspend () -> BiliResponse<JsonElement>,
    ): Result<DynamicFeedPage> = withContext(Dispatchers.IO) {
        try {
            val response = block()
            val payload = response.data
            if (response.code == 0 && payload != null) {
                Result.Ok(parseDynamicPage(payload))
            } else {
                Result.Err(
                    response.message.ifBlank { "请求失败 (code=${response.code})" },
                    response.code,
                )
            }
        } catch (e: CancellationException) {
            // Leaving the tab cancels the scope; that is not a network error.
            throw e
        } catch (e: Exception) {
            Result.Err(e.message?.takeIf { it.isNotBlank() } ?: "网络异常，请稍后重试")
        }
    }

    private fun visible(rail: DynamicRail, entries: List<DynamicEntry>): List<DynamicEntry> =
        if (rail == DynamicRail.Live) entries.filter { it.kind == DynamicKind.LIVE } else entries

    /**
     * The feed repeats items across pages. A duplicate `LazyColumn` key is a
     * crash rather than a cosmetic glitch, so this is not optional.
     */
    private fun dedupe(existing: List<DynamicEntry>, incoming: List<DynamicEntry>): List<DynamicEntry> {
        if (incoming.isEmpty()) return existing
        val seen = existing.mapTo(HashSet()) { it.id }
        return existing + incoming.filter { it.id.isNotEmpty() && seen.add(it.id) }
    }

    /** The cache key for a selection; `Live` shares `All`'s fetch. */
    private fun keyOf(rail: DynamicRail, tab: DynamicTab): String = when (rail) {
        is DynamicRail.User -> "user:${rail.mid}"
        DynamicRail.Mine -> "mine"
        DynamicRail.Live, DynamicRail.All -> "all:${tab.type}"
    }

    private companion object {
        /** Enough to fill the rail's visible height without a second page. */
        const val RAIL_PAGE_SIZE = 20
    }
}
