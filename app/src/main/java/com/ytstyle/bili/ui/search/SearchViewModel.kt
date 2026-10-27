package com.ytstyle.bili.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.net.ArticleSearchItem
import com.ytstyle.bili.data.net.HotwordItem
import com.ytstyle.bili.data.net.LiveRoomSearchItem
import com.ytstyle.bili.data.net.MediaSearchItem
import com.ytstyle.bili.data.net.SearchResultItem
import com.ytstyle.bili.data.net.SuggestTag
import com.ytstyle.bili.data.net.UserSearchItem
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import com.ytstyle.bili.data.repo.SearchOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The search partitions this client supports, matching Bilibili's own set.
 *
 * 番剧 and 影视 return results only for keywords that match a PGC entry —
 * searching a game name legitimately yields nothing, which is not a failure.
 * (An earlier build dropped these two tabs after testing with such a keyword;
 * that conclusion was wrong and is corrected here.)
 */
enum class SearchPartition(val id: String, val label: String) {
    Video("video", "视频"),
    Bangumi("media_bangumi", "番剧"),
    Film("media_ft", "影视"),
    User("bili_user", "用户"),
    Article("article", "专栏"),
    Live("live_room", "直播间");

    /** PGC partitions share one result list. */
    val isMedia: Boolean get() = this == Bangumi || this == Film
}

/** Video sort orders, labelled the way Bilibili labels them. */
enum class SortOption(val value: String, val label: String) {
    Relevance(SearchOrder.DEFAULT, "综合排序"),
    MostPlayed(SearchOrder.MOST_PLAYED, "最多播放"),
    Newest(SearchOrder.NEWEST, "最新发布"),
    MostDanmaku(SearchOrder.MOST_DANMAKU, "最多弹幕"),
    MostFavorited(SearchOrder.MOST_FAVORITED, "最多收藏"),
}

/** Bilibili's length filter, exposed as the `duration` parameter. */
enum class DurationOption(val value: Int, val label: String) {
    Any(0, "全部时长"),
    Under10(1, "10分钟以下"),
    From10To30(2, "10-30分钟"),
    From30To60(3, "30-60分钟"),
    Over60(4, "60分钟以上"),
}

data class SearchUiState(
    val query: String = "",
    val hotwords: List<HotwordItem> = emptyList(),
    val suggestions: List<SuggestTag> = emptyList(),
    val partition: SearchPartition = SearchPartition.Video,

    // One list per partition; the active partition decides which is rendered.
    val videos: List<SearchResultItem> = emptyList(),
    val users: List<UserSearchItem> = emptyList(),
    val articles: List<ArticleSearchItem> = emptyList(),
    val liveRooms: List<LiveRoomSearchItem> = emptyList(),
    /** Shared by the 番剧 and 影视 partitions; they use the same element shape. */
    val media: List<MediaSearchItem> = emptyList(),

    val sort: SortOption = SortOption.Relevance,
    val duration: DurationOption = DurationOption.Any,
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false,
) {
    /** True when the active partition produced nothing. */
    val isCurrentPartitionEmpty: Boolean
        get() = when (partition) {
            SearchPartition.Video -> videos.isEmpty()
            SearchPartition.Bangumi, SearchPartition.Film -> media.isEmpty()
            SearchPartition.User -> users.isEmpty()
            SearchPartition.Article -> articles.isEmpty()
            SearchPartition.Live -> liveRooms.isEmpty()
        }

    val showSuggestions: Boolean
        get() = !hasSearched && query.isNotBlank() && suggestions.isNotEmpty()

    val showHotwords: Boolean
        get() = !hasSearched && query.isBlank()

    /** Sort and length filters only apply to video search. */
    val showFilters: Boolean
        get() = partition == SearchPartition.Video
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: BiliRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var suggestJob: Job? = null

    init {
        loadHotwords()
    }

    private fun loadHotwords() {
        viewModelScope.launch {
            when (val result = repository.hotSearchWords()) {
                is Result.Ok -> _state.update { it.copy(hotwords = result.value) }
                // Trending terms are decorative; a failure must not block search.
                is Result.Err -> Unit
            }
        }
    }

    fun onQueryChange(value: String) {
        _state.update {
            it.copy(
                query = value,
                hasSearched = if (value.isBlank()) false else it.hasSearched,
                suggestions = if (value.isBlank()) emptyList() else it.suggestions,
            )
        }

        suggestJob?.cancel()
        if (value.isBlank()) return

        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            when (val result = repository.searchSuggest(value)) {
                is Result.Ok -> _state.update { it.copy(suggestions = result.value) }
                is Result.Err -> Unit
            }
        }
    }

    fun onTermSelected(term: String) {
        suggestJob?.cancel()
        _state.update { it.copy(query = term, suggestions = emptyList()) }
        search()
    }

    fun onPartitionChange(partition: SearchPartition) {
        if (partition == _state.value.partition) return
        _state.update { it.copy(partition = partition) }
        // Re-run for the new partition if a search is already active.
        if (_state.value.hasSearched) search()
    }

    fun search() {
        val keyword = _state.value.query.trim()
        if (keyword.isEmpty()) return

        _state.update {
            it.copy(
                isLoading = true,
                error = null,
                suggestions = emptyList(),
                hasSearched = true,
                // Clear only the active partition's results.
                videos = if (it.partition == SearchPartition.Video) emptyList() else it.videos,
                users = if (it.partition == SearchPartition.User) emptyList() else it.users,
                articles = if (it.partition == SearchPartition.Article) emptyList() else it.articles,
                liveRooms = if (it.partition == SearchPartition.Live) emptyList() else it.liveRooms,
                media = if (it.partition.isMedia) emptyList() else it.media,
            )
        }

        val partition = _state.value.partition
        viewModelScope.launch {
            val error = when (partition) {
                SearchPartition.Video -> loadVideos(keyword)
                SearchPartition.Bangumi, SearchPartition.Film -> loadMedia(keyword, partition.id)
                SearchPartition.User -> loadUsers(keyword)
                SearchPartition.Article -> loadArticles(keyword)
                SearchPartition.Live -> loadLiveRooms(keyword)
            }
            _state.update { it.copy(isLoading = false, error = error) }
        }
    }

    /** 番剧 and 影视 share a payload shape, so they share a loader. */
    private suspend fun loadMedia(keyword: String, searchType: String): String? =
        when (val result = repository.searchMedia(keyword, searchType)) {
            is Result.Ok -> {
                _state.update { it.copy(media = result.value.result) }
                null
            }
            is Result.Err -> result.message
        }

    private suspend fun loadVideos(keyword: String): String? {
        val current = _state.value
        return when (
            val result = repository.search(
                keyword = keyword,
                page = 1,
                order = current.sort.value,
                duration = current.duration.value,
            )
        ) {
            is Result.Ok -> {
                _state.update { it.copy(videos = result.value.result.filter { r -> r.bvid.isNotEmpty() }) }
                null
            }
            is Result.Err -> result.message
        }
    }

    private suspend fun loadUsers(keyword: String): String? =
        when (val result = repository.searchUsers(keyword)) {
            is Result.Ok -> {
                _state.update { it.copy(users = result.value.result) }
                null
            }
            is Result.Err -> result.message
        }

    private suspend fun loadArticles(keyword: String): String? =
        when (val result = repository.searchArticles(keyword)) {
            is Result.Ok -> {
                _state.update { it.copy(articles = result.value.result) }
                null
            }
            is Result.Err -> result.message
        }

    private suspend fun loadLiveRooms(keyword: String): String? =
        when (val result = repository.searchLiveRooms(keyword)) {
            is Result.Ok -> {
                _state.update { it.copy(liveRooms = result.value.result) }
                null
            }
            is Result.Err -> result.message
        }

    fun onSortChange(option: SortOption) {
        if (option == _state.value.sort) return
        _state.update { it.copy(sort = option) }
        if (_state.value.hasSearched) search()
    }

    fun onDurationChange(option: DurationOption) {
        if (option == _state.value.duration) return
        _state.update { it.copy(duration = option) }
        if (_state.value.hasSearched) search()
    }

    fun clear() {
        suggestJob?.cancel()
        _state.value = SearchUiState(hotwords = _state.value.hotwords)
    }

    private companion object {
        const val SUGGEST_DEBOUNCE_MS = 250L
    }
}
