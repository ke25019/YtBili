package com.ytstyle.bili.ui.space

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.net.DynamicApi
import com.ytstyle.bili.data.net.DynamicEntry
import com.ytstyle.bili.data.net.DynamicKind
import com.ytstyle.bili.data.net.FavFolder
import com.ytstyle.bili.data.net.FavResource
import com.ytstyle.bili.data.net.FollowApi
import com.ytstyle.bili.data.net.SpaceApi
import com.ytstyle.bili.data.net.SpaceLikedVideo
import com.ytstyle.bili.data.net.SpaceProfile
import com.ytstyle.bili.data.net.SpaceVideo
import com.ytstyle.bili.data.net.parseDynamicPage
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import com.ytstyle.bili.data.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The space tabs. A tab is only rendered once its data actually loaded. */
enum class SpaceTab(val label: String) {
    Home("主页"),
    Dynamics("动态"),
    Videos("投稿"),
    Favorites("收藏"),
    Bangumi("追番"),
}

/** One followed 番剧/剧集, flattened for the space page's 追番 section. */
data class SpaceBangumi(
    val seasonId: Long = 0,
    val title: String = "",
    val cover: String = "",
    /** "更新至第12话" / "全38话", already display-ready. */
    val progress: String = "",
)

data class SpaceUiState(
    val mid: Long = 0,
    val profile: SpaceProfile? = null,
    /** Space banner, empty when the payload has none (the header then paints a flat surface). */
    val banner: String = "",
    /** "IP属地" as reported by the payload; empty means "unknown", so the line is skipped. */
    val ipLocation: String = "",
    /** Null while unknown / after a failed call, rendered as a dash rather than 0. */
    val followers: Long? = null,
    val following: Long? = null,
    val likes: Long? = null,
    val videos: List<SpaceVideo> = emptyList(),
    /** Total upload count reported by the listing's `page.count`, not just the loaded page. */
    val videosTotal: Int = 0,
    val videosLoaded: Boolean = false,
    val folders: List<FavFolder> = emptyList(),
    /**
     * Folder id -> cover URL.
     *
     * The folder endpoints frequently return an empty `cover`, so a folder
     * without one borrows the cover of its most recently favourited video —
     * which is what "不会加载封面" was about.
     */
    val folderCovers: Map<Long, String> = emptyMap(),
    val foldersLoaded: Boolean = false,
    /** Non-null while one folder's contents are shown inside the 收藏 tab. */
    val openFolder: FavFolder? = null,
    val folderItems: List<FavResource> = emptyList(),
    val folderItemsLoading: Boolean = false,
    /** 动态 from `feed/space` — also the source of the 主页's 图文 section. */
    val dynamics: List<DynamicEntry> = emptyList(),
    val dynamicsLoaded: Boolean = false,
    val bangumi: List<SpaceBangumi> = emptyList(),
    val bangumiLoaded: Boolean = false,
    /** 最近点赞的视频, archive-shaped so the shared grid can render it. */
    val likedVideos: List<SpaceLikedVideo> = emptyList(),
    val likedVideosLoaded: Boolean = false,
    /**
     * The owner keeps their likes private.
     *
     * `x/space/like/video` answers `53013` for those accounts, which is a
     * different fact from "has not liked anything" and is shown as such.
     */
    val likedVideosPrivate: Boolean = false,
    val isFollowing: Boolean = false,
    /** True when the viewed mid equals the signed-in account. */
    val isSelf: Boolean = false,
    val tab: SpaceTab = SpaceTab.Home,
    val isLoading: Boolean = true,
    /** Only a profile failure is fatal: nothing can be drawn without a header. */
    val error: String? = null,
    val notice: String? = null,
    val noticeIsError: Boolean = false,
) {
    /** Tabs that can actually be shown: an unloadable section is dropped, not faked. */
    val tabs: List<SpaceTab>
        get() = buildList {
            add(SpaceTab.Home)
            if (dynamicsLoaded) add(SpaceTab.Dynamics)
            if (videosLoaded) add(SpaceTab.Videos)
            if (foldersLoaded) add(SpaceTab.Favorites)
            if (bangumiLoaded) add(SpaceTab.Bangumi)
        }

    /** Dynamics that carry images — the 主页's 图文 preview. */
    val illustrated: List<DynamicEntry>
        get() = dynamics.filter { it.imageUrls.isNotEmpty() || it.kind == DynamicKind.IMAGES }
}

/**
 * Loads one user's space.
 *
 * Every section is an independent call, so a rejected/failing endpoint only
 * removes its own section. The profile is the only required piece: it gates the
 * whole screen.
 */
@HiltViewModel
class SpaceViewModel @Inject constructor(
    private val repository: BiliRepository,
    private val session: SessionStore,
    private val spaceApi: SpaceApi,
    private val dynamicApi: DynamicApi,
    private val followApi: FollowApi,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val mid: Long = savedStateHandle.get<String>("mid")?.toLongOrNull() ?: 0L

    private val _state = MutableStateFlow(SpaceUiState(mid = mid))
    val state: StateFlow<SpaceUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (mid <= 0) {
            _state.update { it.copy(isLoading = false, error = "无效的用户 ID") }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            // Following yourself is rejected by the API; hide the button instead
            // of letting the user hit that error.
            val ownMid = runCatching { session.midFlow.first() }.getOrDefault(0L)
            _state.update { it.copy(isSelf = ownMid != 0L && ownMid == mid) }

            // Fire everything at once: the header renders as soon as the
            // profile lands, sections fill in whenever their call returns.
            val profileDeferred = async { repository.spaceInfo(mid) }
            val statDeferred = async { repository.relationStat(mid) }
            val videosDeferred = async { repository.spaceVideos(mid) }
            val foldersDeferred = async { repository.favFolders(mid) }
            val coversDeferred = async { runCatching { spaceApi.folderCovers(mid) }.getOrNull() }
            val upstatDeferred = async { runCatching { spaceApi.upstat(mid) }.getOrNull() }
            val followDeferred = async { repository.isFollowing(mid) }
            // 动态 and 追番 are each optional sections of the 主页; neither can
            // fail the page.
            val dynamicsDeferred = async {
                runCatching { dynamicApi.feedSpace(hostMid = mid) }.getOrNull()
            }
            val bangumiDeferred = async {
                // type 1 = 追番 (2 would be 追剧); the same call 追更 makes.
                runCatching { followApi.followBangumi(vmid = mid, type = 1) }.getOrNull()
            }
            // Optional like the rest: a private list must not fail the page.
            val likedDeferred = async { runCatching { spaceApi.likedVideos(mid) }.getOrNull() }

            var headerFromProfile = false
            when (val profile = profileDeferred.await()) {
                is Result.Ok -> {
                    // The signed profile already carries the header and the IP
                    // location, so this is the normal path. Reading them here
                    // also removes a whole request in the common case.
                    val value = profile.value
                    headerFromProfile = value.topPhoto.isNotBlank() ||
                        value.ipLocation.isNotBlank()
                    _state.update {
                        it.copy(
                            profile = value,
                            banner = value.topPhoto.ifBlank { it.banner },
                            ipLocation = value.ipLocation.ifBlank { it.ipLocation },
                        )
                    }
                }

                is Result.Err -> _state.update { it.copy(error = profile.message) }
            }

            when (val stat = statDeferred.await()) {
                is Result.Ok -> _state.update {
                    it.copy(followers = stat.value.follower, following = stat.value.following)
                }

                is Result.Err -> Unit
            }

            // `likes` stays null when the call fails or the payload has no such
            // field, so the counter renders a dash instead of a wrong zero.
            upstatDeferred.await()?.let { response ->
                if (response.code == 0) {
                    response.data?.likes?.let { likes -> _state.update { it.copy(likes = likes) } }
                }
            }

            // Only fall back to the legacy unsigned endpoint when the signed
            // payload had neither field. It is requested here rather than up
            // front with the others so the common case does not spend a request
            // on a value the signed response already carried — the log showed
            // this endpoint being hit on every single space visit.
            if (!headerFromProfile) {
                runCatching { spaceApi.accInfo(mid) }.getOrNull()?.data?.let { extra ->
                    _state.update {
                        it.copy(
                            banner = extra.topPhoto.ifBlank { it.banner },
                            ipLocation = extra.ipLocation.ifBlank { it.ipLocation },
                        )
                    }
                }
            }

            when (val videos = videosDeferred.await()) {
                is Result.Ok -> {
                    val page = videos.value.list?.vlist.orEmpty()
                    _state.update {
                        it.copy(
                            videos = page,
                            videosTotal = videos.value.page?.count?.takeIf { count -> count > 0 }
                                ?: page.size,
                            videosLoaded = true,
                        )
                    }
                }

                is Result.Err -> Unit
            }

            // Covers are decoration: a failure here just leaves the placeholder.
            coversDeferred.await()?.let { response ->
                if (response.code == 0) {
                    val covers = response.data?.list.orEmpty().mapNotNull { folder ->
                        val key = if (folder.id > 0) folder.id else folder.fid
                        if (key > 0 && folder.cover.isNotBlank()) key to folder.cover else null
                    }.toMap()
                    if (covers.isNotEmpty()) {
                        _state.update { it.copy(folderCovers = covers) }
                    }
                }
            }

            when (val folders = foldersDeferred.await()) {
                is Result.Ok -> _state.update {
                    it.copy(folders = folders.value.list, foldersLoaded = true)
                }

                is Result.Err -> Unit
            }

            // Fall back to the newest video's cover for folders the API left
            // blank. Capped: each lookup is one request, and a user with 40
            // folders must not turn one screen into 40 calls.
            val blank = _state.value.folders.filter { coverOf(it, _state.value.folderCovers).isBlank() }
            if (blank.isNotEmpty()) {
                val fetched = blank.take(MAX_COVER_LOOKUPS).mapNotNull { folder ->
                    val id = folderKey(folder)
                    if (id <= 0) return@mapNotNull null
                    val cover = (repository.favResources(id) as? Result.Ok)
                        ?.value?.medias?.firstOrNull()?.cover
                        .orEmpty()
                    cover.takeIf { it.isNotBlank() }?.let { id to it }
                }.toMap()
                if (fetched.isNotEmpty()) {
                    _state.update { it.copy(folderCovers = it.folderCovers + fetched) }
                }
            }

            dynamicsDeferred.await()?.let { response ->
                if (response.code == 0) {
                    val page = parseDynamicPage(response.data)
                    _state.update {
                        it.copy(dynamics = page.entries, dynamicsLoaded = true)
                    }
                }
            }

            bangumiDeferred.await()?.let { response ->
                if (response.code == 0) {
                    // Partial success is still success: a season with no title
                    // is skipped, not turned into an empty section.
                    val items = response.data?.list.orEmpty().mapNotNull { item ->
                        if (item.seasonId <= 0L && item.title.isBlank()) return@mapNotNull null
                        SpaceBangumi(
                            seasonId = item.seasonId,
                            title = item.title.ifBlank { "未命名" },
                            cover = item.cover,
                            progress = item.newEp?.desc.orEmpty().trim(),
                        )
                    }
                    _state.update { it.copy(bangumi = items, bangumiLoaded = true) }
                }
            }

            likedDeferred.await()?.let { response ->
                when (response.code) {
                    0 -> _state.update {
                        it.copy(
                            likedVideos = response.data?.list.orEmpty(),
                            likedVideosLoaded = true,
                            likedVideosPrivate = false,
                        )
                    }

                    // 隐私设置未公开 is a real answer, not a failure: it says the
                    // section is hidden, which is worth telling the reader.
                    LIKED_PRIVATE_CODE -> _state.update {
                        it.copy(
                            likedVideos = emptyList(),
                            likedVideosLoaded = true,
                            likedVideosPrivate = true,
                        )
                    }

                    else -> Unit
                }
            }

            when (val following = followDeferred.await()) {
                is Result.Ok -> _state.update { it.copy(isFollowing = following.value) }
                is Result.Err -> Unit
            }

            _state.update { it.copy(isLoading = false) }
        }
    }

    fun selectTab(tab: SpaceTab) {
        if (tab !in _state.value.tabs) return
        _state.update { it.copy(tab = tab) }
    }

    /**
     * Opens one folder inside the 收藏 tab.
     *
     * In place rather than on a new screen: the folder belongs to the profile
     * being looked at, and pushing a route would lose which space it came from.
     */
    fun openFolder(folder: FavFolder) {
        val id = folderKey(folder)
        if (id <= 0) return
        _state.update {
            it.copy(
                // The folder's contents are rendered inside the 收藏 tab, so
                // opening one from the 主页 tab has to move there. Without this
                // the tap filled a view nobody was looking at, which is exactly
                // the reported "点了没反应".
                tab = SpaceTab.Favorites,
                openFolder = folder,
                folderItems = emptyList(),
                folderItemsLoading = true,
            )
        }
        viewModelScope.launch {
            when (val result = repository.favResources(id)) {
                is Result.Ok -> _state.update {
                    it.copy(folderItems = result.value.medias, folderItemsLoading = false)
                }
                is Result.Err -> _state.update {
                    it.copy(
                        folderItemsLoading = false,
                        notice = result.message,
                        noticeIsError = true,
                    )
                }
            }
        }
    }

    fun closeFolder() = _state.update {
        it.copy(openFolder = null, folderItems = emptyList(), folderItemsLoading = false)
    }

    /**
     * Opens a favourited video. Rows carry an `aid` and usually a `bvid`; the
     * lookup is only needed for the older rows that do not.
     */
    fun openFavourite(media: FavResource, onResolved: (String) -> Unit) {
        val direct = media.bvid.ifBlank { media.bvId }
        if (direct.isNotEmpty()) {
            onResolved(direct)
            return
        }
        viewModelScope.launch {
            when (val detail = repository.viewDetailByAid(media.id)) {
                is Result.Ok -> onResolved(detail.value.bvid)
                is Result.Err -> _state.update {
                    it.copy(notice = detail.message, noticeIsError = true)
                }
            }
        }
    }

    fun toggleFollow() {
        if (mid <= 0 || _state.value.isSelf) return
        val target = !_state.value.isFollowing
        _state.update { it.copy(isFollowing = target) }
        viewModelScope.launch {
            when (val result = repository.modifyRelation(mid, if (target) 1 else 2)) {
                is Result.Err -> _state.update {
                    it.copy(isFollowing = !target, notice = result.message, noticeIsError = true)
                }

                is Result.Ok -> {
                    // A successful follow can change the follower counter by one;
                    // the local bump keeps the header consistent without a refetch.
                    _state.update { current ->
                        current.copy(
                            notice = if (target) "已关注" else "已取消关注",
                            noticeIsError = false,
                            followers = current.followers?.let { it + (if (target) 1 else -1) },
                        )
                    }
                }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun roomId(): Long = _state.value.profile?.liveRoom?.roomid ?: 0L

    fun isLive(): Boolean = _state.value.profile?.liveRoom?.liveStatus == 1

    /** Folders come back with either `id` or `fid` set, depending on the call. */
    private fun folderKey(folder: FavFolder): Long =
        if (folder.id > 0) folder.id else folder.fid

    private fun coverOf(folder: FavFolder, covers: Map<Long, String>): String =
        covers[folder.id] ?: covers[folder.fid] ?: ""

    private companion object {
        /**
         * How many folder-cover lookups one space load may make. Each is a
         * request, and a user with 40 folders must not turn one screen into 40.
         */
        const val MAX_COVER_LOOKUPS = 6

        /** `x/space/like/video`: 用户隐私设置未公开. */
        const val LIKED_PRIVATE_CODE = 53013
    }
}
