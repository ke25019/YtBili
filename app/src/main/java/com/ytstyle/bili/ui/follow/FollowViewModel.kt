package com.ytstyle.bili.ui.follow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.net.BangumiFollowItem
import com.ytstyle.bili.data.net.BiliResponse
import com.ytstyle.bili.data.net.FollowApi
import com.ytstyle.bili.data.net.FollowEntry
import com.ytstyle.bili.data.net.FollowKind
import com.ytstyle.bili.data.net.toFollowEntries
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import com.ytstyle.bili.data.session.SessionStore
import com.ytstyle.bili.ui.components.formatCount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The categories the client can actually load.
 *
 * 课堂 and 追漫 are deliberately absent: their endpoint paths were never
 * confirmed, and an entry that can only ever fail is worse than no entry.
 */
enum class FollowTab(val label: String, val type: Int) {
    ALL("全部", 0),
    BANGUMI("追番", 1),
    CINEMA("追剧", 2),
    COLLECTION("合集", 0),
}

data class FollowUiState(
    val tab: FollowTab = FollowTab.ALL,
    val items: List<FollowEntry> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    /** Distinguishes "no session" from a server failure or an empty list. */
    val notLoggedIn: Boolean = false,
)

@HiltViewModel
class FollowViewModel @Inject constructor(
    private val api: FollowApi,
    private val repository: BiliRepository,
    private val session: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(FollowUiState())
    val state: StateFlow<FollowUiState> = _state.asStateFlow()

    /** Per-tab payload, so switching tabs does not refetch. */
    private data class TabData(
        val items: List<FollowEntry> = emptyList(),
        val error: String? = null,
    )

    private val cache = mutableMapOf<FollowTab, TabData>()
    private var mid: Long = 0L

    /** Guards against a stale load overwriting a tab the user already left. */
    private var loadToken: Int = 0

    init {
        load(FollowTab.ALL)
    }

    fun selectTab(tab: FollowTab) {
        if (tab == _state.value.tab) return
        _state.update { it.copy(tab = tab) }
        val cached = cache[tab]
        if (cached != null) {
            publish(tab, cached)
        } else {
            load(tab)
        }
    }

    fun refresh() {
        cache.clear()
        // Re-read the session: the user may have signed in/out since the
        // cached mid was captured.
        mid = 0L
        load(_state.value.tab)
    }

    private fun load(tab: FollowTab) {
        val token = ++loadToken
        viewModelScope.launch {
            _state.update {
                it.copy(isLoading = true, error = null, notLoggedIn = false)
            }

            if (mid == 0L) mid = session.midFlow.first()
            if (mid == 0L) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        items = emptyList(),
                        error = null,
                        notLoggedIn = true,
                    )
                }
                return@launch
            }

            when (tab) {
                FollowTab.ALL -> loadAll(mid, token)
                FollowTab.BANGUMI -> loadSingle(mid, FollowTab.BANGUMI, token)
                FollowTab.CINEMA -> loadSingle(mid, FollowTab.CINEMA, token)
                FollowTab.COLLECTION -> loadSingle(mid, FollowTab.COLLECTION, token)
            }
        }
    }

    /**
     * 全部 merges the three sources concurrently and keeps the per-source
     * result cached, so opening 全部 once makes every category tab instant.
     * Each row keeps its own [FollowKind], which is what decides where a tap
     * routes.
     */
    private suspend fun loadAll(mid: Long, token: Int) = coroutineScope {
        val bangumi = async { fetchBangumi(mid, FollowTab.BANGUMI) }
        val cinema = async { fetchBangumi(mid, FollowTab.CINEMA) }
        val collections = async { fetchCollections(mid) }

        val parts = listOf(bangumi.await(), cinema.await(), collections.await())
        cache[FollowTab.BANGUMI] = parts[0]
        cache[FollowTab.CINEMA] = parts[1]
        cache[FollowTab.COLLECTION] = parts[2]

        // Partial success is still a success: a failing source must not blank
        // out the sources that did answer.
        val merged = TabData(
            items = parts.flatMap { it.items },
            error = parts.firstOrNull { it.items.isEmpty() && it.error != null }?.error,
        )
        cache[FollowTab.ALL] = merged
        publish(FollowTab.ALL, merged, token)
    }

    private suspend fun loadSingle(mid: Long, tab: FollowTab, token: Int) {
        val data = when (tab) {
            FollowTab.BANGUMI -> fetchBangumi(mid, FollowTab.BANGUMI)
            FollowTab.CINEMA -> fetchBangumi(mid, FollowTab.CINEMA)
            FollowTab.COLLECTION -> fetchCollections(mid)
            FollowTab.ALL -> TabData()
        }
        cache[tab] = data
        publish(tab, data, token)
    }

    private suspend fun fetchBangumi(mid: Long, tab: FollowTab): TabData =
        when (val result = call { api.followBangumi(vmid = mid, type = tab.type) }) {
            is Result.Ok -> TabData(
                items = result.value.list.mapNotNull { it.toEntry(tab) }.withUniqueKeys(),
            )
            is Result.Err -> TabData(error = result.message)
        }

    private suspend fun fetchCollections(mid: Long): TabData =
        when (val result = call { api.seasonsSeries(mid = mid) }) {
            is Result.Ok -> TabData(
                items = result.value.toFollowEntries().map { it.withDisplayProgress() }
                    .withUniqueKeys(),
            )
            is Result.Err -> TabData(error = result.message)
        }

    private fun publish(tab: FollowTab, data: TabData, token: Int = loadToken) {
        // The user may have switched tabs while this request was in flight.
        if (token != loadToken || _state.value.tab != tab) return
        _state.update {
            it.copy(
                isLoading = false,
                items = data.items,
                // An error is only worth showing when it left the tab empty;
                // otherwise the rows themselves are the answer.
                error = data.error.takeIf { data.items.isEmpty() },
                notLoggedIn = false,
            )
        }
    }

    /**
     * Opens a row. 番剧/剧集 carry a `season_id`; a 合集 only carries recent
     * `aid`s, and the player routes on `bvid`, so that one has to be resolved
     * first — the same trick the favourites screen uses.
     */
    fun open(
        entry: FollowEntry,
        onVideoClick: (String) -> Unit,
        onBangumiClick: (Long) -> Unit,
    ) {
        when (entry.kind) {
            FollowKind.BANGUMI, FollowKind.CINEMA -> {
                if (entry.seasonId > 0) onBangumiClick(entry.seasonId)
            }
            FollowKind.COLLECTION -> {
                if (entry.bvid.isNotEmpty()) {
                    onVideoClick(entry.bvid)
                    return
                }
                if (entry.aid <= 0) return
                viewModelScope.launch {
                    when (val detail = repository.viewDetailByAid(entry.aid)) {
                        is Result.Ok -> onVideoClick(detail.value.bvid)
                        is Result.Err -> _state.update { it.copy(error = detail.message) }
                    }
                }
            }
        }
    }

    private fun BangumiFollowItem.toEntry(tab: FollowTab): FollowEntry? {
        if (title.isBlank() && cover.isBlank() && seasonId == 0L) return null
        val desc = newEp?.desc.orEmpty().trim()
        val count = totalCount.toIntOrNull() ?: 0
        val isCinema = tab == FollowTab.CINEMA
        return FollowEntry(
            key = "b:$seasonId",
            title = title.ifBlank { "未命名" },
            cover = cover,
            progress = when {
                desc.isNotEmpty() -> desc
                count > 0 -> "共${count}${if (isCinema) "集" else "话"}"
                else -> ""
            },
            kind = if (isCinema) FollowKind.CINEMA else FollowKind.BANGUMI,
            seasonId = seasonId,
        )
    }

    private fun FollowEntry.withDisplayProgress(): FollowEntry =
        if (kind == FollowKind.COLLECTION && contentCount > 0) {
            copy(progress = "${formatCount(contentCount)}个内容")
        } else {
            this
        }

    /**
     * LazyColumn keys must be unique. Payload ids normally are, but a
     * collection built from two lists with the same title is not guaranteed to
     * be, and a duplicate key crashes the list.
     */
    private fun List<FollowEntry>.withUniqueKeys(): List<FollowEntry> {
        val seen = HashSet<String>(size)
        return map { entry ->
            val base = entry.key.ifEmpty { "f" }
            var candidate = base
            var suffix = 0
            while (!seen.add(candidate)) {
                suffix++
                candidate = "$base#$suffix"
            }
            entry.copy(key = candidate)
        }
    }

    /** Uniform envelope handling so the UI never sees HTTP/JSON error shapes. */
    private suspend fun <T> call(block: suspend () -> BiliResponse<T>): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                val response = block()
                val payload = response.data
                if (response.code == 0 && payload != null) {
                    Result.Ok(payload)
                } else {
                    Result.Err(
                        response.message.ifBlank { "请求失败 (code=${response.code})" },
                        response.code,
                    )
                }
            } catch (e: CancellationException) {
                // Leaving the screen cancels the scope; that is not a network
                // error and must not be reported as one.
                throw e
            } catch (e: Exception) {
                Result.Err(e.message ?: "网络异常，请稍后重试")
            }
        }
}
