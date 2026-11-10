package com.ytstyle.bili.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.net.RegionApi
import com.ytstyle.bili.data.net.RegionItem
import com.ytstyle.bili.data.net.RecommendItem
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import com.ytstyle.bili.data.settings.AVAILABLE_HOME_PARTITIONS
import com.ytstyle.bili.data.settings.DEFAULT_HOME_PARTITIONS
import com.ytstyle.bili.data.settings.HomePartition
import com.ytstyle.bili.data.settings.HomePartitionStore
import com.ytstyle.bili.data.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Everything the home screen renders.
 *
 * [items] is always a [RecommendItem], even for a partition feed: the two
 * payloads are mapped into the one shape [com.ytstyle.bili.ui.components.VideoCard]
 * already consumes, so the card and the LazyColumn keys need no special cases.
 */
data class HomeUiState(
    val items: List<RecommendItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    /** The user's configured chip row, in order. Never empty. */
    val partitions: List<HomePartition> = DEFAULT_HOME_PARTITIONS,
    /** `rid` of the selected chip; `null` is 全部, the generic feed. */
    val selectedRid: Int? = null,
    /** Experimental: render the feed as a two-column grid. */
    val useGrid: Boolean = false,
    /** True once the feed stops returning anything new. */
    val reachedEnd: Boolean = false,
    /** True while the screen is in the foreground and the auto-feed is armed. */
    val autoFeedActive: Boolean = false,
    /**
     * The chip row is in editing mode.
     *
     * Part of the UI state rather than a `remember` in the composable so that it
     * survives configuration changes, and so the view model can force it closed
     * when the row it is editing changes underneath it.
     */
    val editing: Boolean = false,
    /**
     * Set when the selected chip's feed answered, but empty.
     *
     * Distinct from [error]: nothing failed, the partition simply has no content
     * for this request. The screen says so instead of showing a blank list.
     */
    val emptyFeedMessage: String? = null,
) {
    /** The selected partition, falling back to the first configured chip. */
    val selectedPartition: HomePartition
        get() = partitions.firstOrNull { it.rid == selectedRid } ?: partitions.first()

    /** Every partition the user could still add. */
    val addablePartitions: List<HomePartition>
        get() = AVAILABLE_HOME_PARTITIONS.filter { candidate ->
            partitions.none { it.rid == candidate.rid }
        }
}

/**
 * The home feed.
 *
 * ## Where the videos come from
 *
 *  - The **全部** chip uses `repository.recommend`, the generic recommendation
 *    feed it has always used.
 *  - Every other chip uses [RegionApi]'s region ranking for its `rid`. This is
 *    the fix for "首页提供的那些分区并不是从对应分区获取的推流而是默认全部的推荐流":
 *    picking 动画 now fetches 动画's region, not the default feed again.
 *
 * ## Why the region feed is windowed
 *
 * `x/web-interface/ranking/v2` has no offset parameter — it returns the whole
 * 90-odd-item list in one response. Loading it and appending all of it at once
 * would dump the entire partition into the list and make both the trigger-based
 * "load more" and the once-a-minute auto-feed pointless (every later request
 * would be a duplicate). So the flat list is held as a pool in [RegionPool] and
 * handed to the UI [PAGE_SIZE] at a time, which keeps the endless-feed behaviour
 * the screen already had.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: BiliRepository,
    private val regionApi: RegionApi,
    private val partitionStore: HomePartitionStore,
    settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var freshIdx = 1
    private var autoFeedJob: Job? = null

    /**
     * The region list currently being consumed, plus how far into it we are.
     *
     * Null means "not fetched yet for the selected rid"; [partitionRid] is what
     * it belongs to, so a chip change invalidates it instead of stitching two
     * partitions' videos into one feed.
     */
    private var pool: List<RegionItem>? = null
    private var poolRid: Int? = null
    private var poolCursor = 0

    /**
     * Feeds already fetched, keyed by rid (`null` = 全部).
     *
     * Switching chips used to wipe the list and re-request, so 动画 → 游戏 → 动画
     * cost a ranking call each way and threw away everything already on screen.
     * The remembered feed is replayed instead; pull-to-refresh is how new data
     * is asked for.
     */
    private val feedCache = mutableMapOf<Int?, CachedFeed>()

    /**
     * Where each partition's list was left, keyed by (`rid`, grid layout).
     *
     * The composable owns the real `LazyListState`s, but it leaves composition
     * when a video opens, so those states are disposed with it and the return
     * trip used to land back at the top of the feed. The position is therefore
     * parked here — the view model outlives the screen while it sits in the back
     * stack — and handed back when the list is rebuilt.
     */
    private val scrollMemory = mutableMapOf<Pair<Int?, Boolean>, Pair<Int, Int>>()

    fun savedScroll(rid: Int?, grid: Boolean): Pair<Int, Int>? = scrollMemory[rid to grid]

    fun rememberScroll(rid: Int?, grid: Boolean, index: Int, offset: Int) {
        scrollMemory[rid to grid] = index to offset
    }

    /** One partition's remembered feed, cursor and all. */
    private class CachedFeed(
        val items: List<RecommendItem>,
        val pool: List<RegionItem>?,
        val poolCursor: Int,
        val freshIdx: Int,
        val reachedEnd: Boolean,
    )

    /**
     * Whether the configured partition list has been read from disk yet.
     *
     * The first `load()` runs against the default row, and the stored row then
     * arrives asynchronously. If it still contains the selected rid — the common
     * case, since 全部 is the default and stays in the list — reloading would
     * throw away good content and fire a second request for nothing.
     */
    private var partitionsLoaded = false

    init {
        load()
        // The grid toggle lives in settings; react to it without recreating
        // the screen.
        viewModelScope.launch {
            settings.homeGrid.collect { enabled ->
                _state.update { it.copy(useGrid = enabled) }
            }
        }
        // The chip row is user-editable now, so the view model has to follow it
        // rather than treat the list as a constant.
        viewModelScope.launch {
            partitionStore.partitions.collect { partitions ->
                val previous = _state.value
                // Ordinal-indexed before this feature existed; keep a valid
                // selection when the selected chip is still configured.
                val selectionSurvives = partitions.any { it.rid == previous.selectedRid }
                val nextRid = if (selectionSurvives) previous.selectedRid else partitions.first().rid
                _state.update {
                    it.copy(
                        partitions = partitions,
                        selectedRid = nextRid,
                        // Edit mode now survives an ordinary write. Moving,
                        // removing or adding a chip is what changes this very
                        // list, so closing the editor on every write would end
                        // edit mode after the first tap and make the row
                        // impossible to rearrange — the exact opposite of what
                        // the editor is for. Nothing can strand the user in it
                        // either: the editor is drawn from this list, so a
                        // shorter row simply shows fewer chips, and the store
                        // never lets the list become empty.
                        editing = it.editing,
                    )
                }
                if (partitionsLoaded && !selectionSurvives) {
                    // The selected chip was removed or reordered out of service;
                    // restart the feed on whatever is first now.
                    invalidatePool()
                    reload()
                }
                partitionsLoaded = true
            }
        }
    }

    /**
     * Appends the next slice of the feed.
     *
     * Items are de-duplicated by bvid: neither endpoint guarantees that
     * consecutive requests are disjoint, and without this an LazyColumn with bvid
     * keys crashes on the duplicate key — and the feed repeats itself when the
     * duplicate happens not to crash it.
     */
    fun load() {
        if (_state.value.isLoading) return
        val rid = _state.value.selectedRid
        if (rid != poolRid) invalidatePool()
        _state.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            if (rid == null) loadGeneric() else loadRegion(rid)
        }
    }

    /** 全部: the generic recommendation feed, appended one page at a time. */
    private suspend fun loadGeneric() {
        when (val result = repository.recommend(pageSize = PAGE_SIZE, freshIdx = freshIdx)) {
            is Result.Ok -> {
                freshIdx++
                appendPage(result.value)
            }
            is Result.Err -> fail(result.message)
        }
    }

    /**
     * A partition chip: read the region pool once, then serve windows of it.
     *
     * The cursor is advanced in [nextWindow] even when the fetched items are
     * already on screen. That matters for the auto-feed: a minute may pass
     * before the user scrolls past the current window, and without the advance
     * every tick would re-fetch the same slice, find it all duplicated, and
     * declare the partition exhausted at 20 items out of ~95.
     */
    private suspend fun loadRegion(rid: Int) {
        if (pool == null) {
            when (val result = regionApi.regionRanking(rid)) {
                is Result.Ok -> {
                    // `Result.Ok` carries the envelope's `data`; a failure was
                    // already turned into `Err` by the repository-style helper
                    // below, so reaching here means the payload is present.
                    result.value.let { data ->
                        pool = data.list.filter { it.bvid.isNotEmpty() }
                        poolRid = rid
                        poolCursor = 0
                    }
                }
                is Result.Err -> {
                    fail(result.message)
                    return
                }
            }
        }

        val window = nextWindow()
        if (window.isEmpty()) {
            _state.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = null,
                    // Everything the region had has been shown. Stop here rather
                    // than quietly falling back to the generic feed: the user
                    // asked for this partition, and filling it with 全部 videos
                    // is the exact complaint this change fixes.
                    reachedEnd = true,
                    emptyFeedMessage = if (it.items.isEmpty()) {
                        "该分区暂时没有内容"
                    } else {
                        null
                    },
                )
            }
            return
        }
        appendPage(window)
    }

    /**
     * Advances the cursor and returns the next slice of the pool.
     *
     * If the slice starting at the cursor is entirely present in the feed — the
     * auto-feed fired while the user was still reading the previous window — the
     * cursor skips past it, so the next slice is genuinely new.
     */
    private fun nextWindow(): List<RegionItem> {
        val items = pool ?: return emptyList()
        var start = poolCursor
        while (start < items.size) {
            val slice = items.subList(start, minOf(start + PAGE_SIZE, items.size))
            val known = _state.value.items.mapTo(HashSet()) { it.bvid }
            if (slice.none { it.bvid in known }) {
                poolCursor = start + slice.size
                return slice
            }
            start += slice.size
        }
        poolCursor = items.size
        return emptyList()
    }

    /**
     * Shared tail of both feeds: de-duplicate, append, and work out whether the
     * feed is exhausted.
     */
    private fun appendPage(page: List<RecommendItem>) {
        // Computed outside `update`, which may re-run its lambda.
        val known = _state.value.items.mapTo(HashSet()) { it.bvid }
        val fresh = page.filter { it.bvid.isNotEmpty() && known.add(it.bvid) }
        _state.update {
            it.copy(
                items = it.items + fresh,
                isLoading = false,
                isRefreshing = false,
                error = null,
                emptyFeedMessage = if (it.items.isEmpty() && fresh.isEmpty()) {
                    "该分区暂时没有内容"
                } else {
                    null
                },
                // A page that contributed nothing new means the feed is
                // exhausted; the auto-feed then stops instead of hammering the
                // endpoint every minute.
                reachedEnd = fresh.isEmpty() && it.items.isNotEmpty(),
            )
        }
    }

    private fun fail(message: String) {
        _state.update {
            it.copy(isLoading = false, isRefreshing = false, error = message)
        }
    }

    /** Pull-to-refresh replaces the feed rather than appending. */
    fun refresh() {
        freshIdx = 1
        invalidatePool()
        // An explicit refresh is the one action that must never be answered
        // from the chip cache.
        feedCache.remove(_state.value.selectedRid)
        _state.update {
            it.copy(
                items = emptyList(),
                isRefreshing = true,
                reachedEnd = false,
                emptyFeedMessage = null,
            )
        }
        load()
    }

    /**
     * Discards the cached region pool.
     *
     * Called whenever the feed restarts or the chip changes. Without it a
     * refresh would replay the same 90 videos, and switching from 动画 to 游戏
     * and back would resurrect 动画's exhausted cursor.
     */
    private fun invalidatePool() {
        pool = null
        poolRid = null
        poolCursor = 0
    }

    /**
     * Arms or disarms the continuous feed.
     *
     * Called from the screen's lifecycle so the loop only runs while the user is
     * actually looking at the home page: a background timer appending pages
     * would burn data and battery for nothing.
     */
    fun setAutoFeedActive(active: Boolean) {
        if (active == _state.value.autoFeedActive) return
        _state.update { it.copy(autoFeedActive = active) }
        autoFeedJob?.cancel()
        autoFeedJob = null
        if (!active) return

        autoFeedJob = viewModelScope.launch {
            while (isActive) {
                delay(AUTO_FEED_INTERVAL_MS)
                val current = _state.value
                if (current.reachedEnd || current.isLoading || current.isRefreshing) continue
                if (current.items.isEmpty()) continue
                load()
            }
        }
    }

    /**
     * Selects a chip by its `rid` (`null` = 全部).
     *
     * A partition that has already been loaded is replayed from [feedCache]
     * instead of being fetched again. Every switch used to clear the list and
     * re-request, so 动画 → 游戏 → 动画 cost a ranking call each way and threw
     * away everything already on screen. Pull-to-refresh is what asks for new
     * data now; a chip tap only changes which feed is shown.
     */
    fun selectChip(rid: Int?) {
        val previous = _state.value.selectedRid
        if (rid == previous) {
            // Tapping the chip that is already selected is a no-op, so a
            // mis-tap cannot clear the feed.
            return
        }

        // Park the outgoing feed before the selection moves.
        feedCache[previous] = CachedFeed(
            items = _state.value.items,
            pool = pool,
            poolCursor = poolCursor,
            freshIdx = freshIdx,
            reachedEnd = _state.value.reachedEnd,
        )

        val cached = feedCache[rid]
        if (cached != null && cached.items.isNotEmpty()) {
            // Restore, and do not touch the network.
            pool = cached.pool
            poolRid = if (cached.pool != null) rid else null
            poolCursor = cached.poolCursor
            freshIdx = cached.freshIdx
            _state.update {
                it.copy(
                    selectedRid = rid,
                    items = cached.items,
                    reachedEnd = cached.reachedEnd,
                    error = null,
                    emptyFeedMessage = null,
                    isLoading = false,
                    isRefreshing = false,
                )
            }
            return
        }

        invalidatePool()
        freshIdx = 1
        _state.update {
            it.copy(
                selectedRid = rid,
                items = emptyList(),
                reachedEnd = false,
                error = null,
                emptyFeedMessage = null,
                isRefreshing = true,
            )
        }
        load()
    }

    // ---- chip-row editing -------------------------------------------------
    //
    // The store owns the list; these are thin wrappers so the screen never talks
    // to DataStore directly. Every one of them is safe to call in any state: the
    // store ignores removals that would empty the row and moves off either end,
    // which is what makes it impossible to get stuck in edit mode with a row
    // that cannot be repaired.

    fun setEditing(editing: Boolean) {
        _state.update { it.copy(editing = editing) }
    }

    fun addPartition(partition: HomePartition) {
        viewModelScope.launch { partitionStore.add(partition) }
    }

    fun removePartition(rid: Int?) {
        viewModelScope.launch { partitionStore.remove(rid) }
    }

    /** [offset] is -1 for "‹" (earlier) and +1 for "›" (later). */
    fun movePartition(rid: Int?, offset: Int) {
        viewModelScope.launch { partitionStore.move(rid, offset) }
    }

    fun resetPartitions() {
        viewModelScope.launch { partitionStore.reset() }
    }

    private fun reload() {
        _state.update {
            it.copy(
                items = emptyList(),
                reachedEnd = false,
                error = null,
                emptyFeedMessage = null,
                isRefreshing = true,
            )
        }
        load()
    }

    override fun onCleared() {
        autoFeedJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val PAGE_SIZE = 20

        /**
         * How often a new page is appended while the home page is on screen.
         * Chosen to stay well inside the endpoint's tolerance: it is one request
         * per minute, not a loop.
         */
        const val AUTO_FEED_INTERVAL_MS = 60_000L
    }
}
