package com.ytstyle.bili.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.danmaku.DanmakuItem
import com.ytstyle.bili.data.danmaku.DanmakuParser
import com.ytstyle.bili.data.net.EmoteItem
import com.ytstyle.bili.data.net.DashTrack
import com.ytstyle.bili.data.net.PlayUrlData
import com.ytstyle.bili.data.net.Reply
import com.ytstyle.bili.data.net.ViewDetail
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import com.ytstyle.bili.data.session.SessionStore
import com.ytstyle.bili.data.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/** Quality tiers Bilibili exposes, in the order the picker shows them. */
enum class Quality(val qn: Int, val label: String) {
    Q1080P60(116, "1080P60"),
    Q1080P(80, "1080P"),
    Q720P(64, "720P"),
    Q480P(32, "480P"),
    Q360P(16, "360P"),
}

data class PlayerUiState(
    val detail: ViewDetail? = null,
    val playData: PlayUrlData? = null,
    val videoTrack: DashTrack? = null,
    val audioTrack: DashTrack? = null,
    val fallbackUrl: String? = null,
    val danmaku: List<DanmakuItem> = emptyList(),
    val replies: List<Reply> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val isLiked: Boolean = false,
    val isFavorited: Boolean = false,
    val isFollowing: Boolean = false,
    val danmakuEnabled: Boolean = true,
    val currentQuality: Quality = Quality.Q1080P,
    val availableQualities: List<Quality> = emptyList(),
    val audioOnly: Boolean = false,
    val replyText: String = "",
    val isSendingReply: Boolean = false,
    /**
     * Cookie header forwarded to the media CDN. Higher quality tiers are tied
     * to the session, and Bilibili's edge hosts reject unauthenticated
     * requests for them.
     */
    val mediaCookie: String = "",
    /** Coins given this session, so the button can show "已投N币". */
    val coinsGiven: Int = 0,
    /** `[token] -> image`, used to render emotes inside comments. */
    val emotes: Map<String, EmoteItem> = emptyMap(),
    /** One-shot message, e.g. explaining that casting is unavailable. */
    val notice: String? = null,
    /** Danmaku appearance, mirroring the settings store. */
    val danmakuConfig: DanmakuConfig = DanmakuConfig(),
    /** True once the video is in the watch-later list. */
    val isInWatchLater: Boolean = false,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: BiliRepository,
    private val session: SessionStore,
    private val settings: SettingsStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val bvid: String = savedStateHandle.get<String>("bvid").orEmpty()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Exposed to the player so progress can be reported back for resume. */
    var lastPositionMs: Long = 0L
        private set

    init {
        load()
        observeDanmakuSettings()
    }

    /**
     * Danmaku appearance comes from the settings store, so changing it in
     * settings takes effect on the next frame without re-entering the player.
     */
    private fun observeDanmakuSettings() {
        // `combine` has typed overloads only up to five flows, so the five
        // appearance values are folded first and enabled is combined after.
        val appearance = combine(
            settings.danmakuScale,
            settings.danmakuOpacity,
            settings.danmakuArea,
            settings.danmakuSpeed,
            settings.danmakuTypes,
        ) { scale, opacity, area, speed, types ->
            DanmakuConfig(
                scale = scale,
                opacity = opacity,
                area = area,
                traverseSeconds = speed,
                types = types,
            )
        }

        viewModelScope.launch {
            combine(settings.danmakuEnabled, appearance) { enabled, base ->
                base.copy(enabled = enabled)
            }.collect { config ->
                _state.update { it.copy(danmakuConfig = config, danmakuEnabled = config.enabled) }
            }
        }
    }

    fun load() {
        if (bvid.isEmpty()) {
            _state.update { it.copy(isLoading = false, error = "无效的视频 ID") }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            // The media data source needs the session cookie as well as the
            // web API client, so read it once here.
            _state.update { it.copy(mediaCookie = session.currentCookie()) }

            // Emote images for the comment list. Anonymous callers get an empty
            // panel, which is fine: reply payloads carry their own emote map.
            viewModelScope.launch {
                val panel = repository.emotePanel()
                if (panel is Result.Ok) {
                    _state.update { current ->
                        current.copy(emotes = panel.value.associateBy { it.text })
                    }
                }
            }

            when (val detailResult = repository.viewDetail(bvid)) {
                is Result.Err -> _state.update {
                    it.copy(isLoading = false, error = detailResult.message)
                }

                is Result.Ok -> {
                    val detail = detailResult.value
                    val firstCid = detail.pages.firstOrNull()?.cid ?: detail.cid
                    _state.update { it.copy(detail = detail) }

                    // Detail, streams and comments are independent; fetch them
                    // together so the screen is not gated on the slowest call.
                    val playDeferred = async { repository.playUrl(bvid, firstCid) }
                    val danmakuDeferred = async { loadDanmaku(firstCid) }
                    val repliesDeferred = async { repository.replies(detail.aid) }
                    val likedDeferred = async { repository.hasLiked(bvid) }

                    applyPlayData(playDeferred.await())
                    applyDanmaku(danmakuDeferred.await())
                    applyReplies(repliesDeferred.await())
                    (likedDeferred.await() as? Result.Ok)?.let { liked ->
                        _state.update { it.copy(isLiked = liked.value) }
                    }

                    // Reflect the watch-later list so the button starts correct.
                    syncWatchLaterState()
                }
            }
        }
    }

    private fun applyPlayData(result: Result<PlayUrlData>) {
        when (result) {
            is Result.Err -> _state.update {
                it.copy(isLoading = false, error = result.message)
            }
            is Result.Ok -> {
                val data = result.value
                val dash = data.dash
                // Prefer the highest-bandwidth track at or below the chosen tier.
                val video = dash?.video?.maxByOrNull { it.bandwidth }
                val audio = dash?.audio?.maxByOrNull { it.bandwidth }
                val qualities = Quality.entries.filter { it.qn in data.accept_quality }

                _state.update {
                    it.copy(
                        isLoading = false,
                        playData = data,
                        videoTrack = video,
                        audioTrack = audio,
                        fallbackUrl = if (video == null) data.durl.firstOrNull()?.url else null,
                        availableQualities = qualities.ifEmpty { listOf(Quality.Q1080P) },
                        error = null,
                    )
                }
            }
        }
    }

    private fun applyDanmaku(result: Result<List<DanmakuItem>>) {
        if (result is Result.Ok) {
            _state.update { it.copy(danmaku = result.value) }
        }
    }

    private fun applyReplies(result: Result<com.ytstyle.bili.data.net.ReplyListData>) {
        if (result is Result.Ok) {
            val all = buildList {
                result.value.topReplies?.let(::addAll)
                addAll(result.value.replies)
            }
            _state.update { it.copy(replies = all) }
        }
    }

    /** Danmaku arrive as 6-minute Protobuf segments; fetch the first two. */
    private suspend fun loadDanmaku(cid: Long): Result<List<DanmakuItem>> =
        withContext(Dispatchers.IO) {
            try {
                val items = ArrayList<DanmakuItem>()
                for (segment in 1..2) {
                    val body = runCatching { repository.danmakuSegment(cid, segment) }
                        .getOrNull() ?: continue
                    items += DanmakuParser.parse(body.byteStream())
                }
                Result.Ok(items)
            } catch (e: Exception) {
                Result.Err(e.message ?: "弹幕加载失败")
            }
        }

    fun onPositionChanged(positionMs: Long) {
        lastPositionMs = positionMs
    }

    fun toggleDanmaku() = _state.update { it.copy(danmakuEnabled = !it.danmakuEnabled) }

    fun toggleAudioOnly() = _state.update { it.copy(audioOnly = !it.audioOnly) }

    fun selectQuality(quality: Quality) {
        _state.update { it.copy(currentQuality = quality) }
        val cid = _state.value.detail?.pages?.firstOrNull()?.cid ?: return
        viewModelScope.launch {
            applyPlayData(repository.playUrl(bvid, cid, quality.qn))
        }
    }

    // ---- interactions ----

    fun toggleLike() {
        val target = !_state.value.isLiked
        // Optimistic: Bilibili's like endpoint is slow and the tap should feel instant.
        _state.update { it.copy(isLiked = target) }
        viewModelScope.launch {
            val result = repository.like(bvid, target)
            if (result is Result.Err) {
                _state.update { it.copy(isLiked = !target, error = result.message) }
            }
        }
    }

    /**
     * Bilibili caps a video at two coins, and coins are additive across calls,
     * so the running total is tracked to render "已投N币" and to stop the user
     * from attempting a third.
     */
    fun addCoin(count: Int, alsoLike: Boolean) {
        val alreadyGiven = _state.value.coinsGiven
        val remaining = MAX_COINS - alreadyGiven
        if (remaining <= 0) {
            _state.update { it.copy(notice = "同一视频最多投 2 个硬币") }
            return
        }
        val actual = count.coerceAtMost(remaining)

        viewModelScope.launch {
            when (val result = repository.coin(bvid, actual, alsoLike)) {
                is Result.Ok -> _state.update {
                    it.copy(
                        coinsGiven = alreadyGiven + actual,
                        isLiked = if (alsoLike) true else it.isLiked,
                        notice = "已投 $actual 个硬币",
                    )
                }
                is Result.Err -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    /**
     * Casting.
     *
     * The official app's cast protocol is private, so a third-party client
     * cannot speak it. What is reachable is Bilibili's own TV app (云视听小电视),
     * which is a standard DLNA renderer — so the path is DLNA discovery plus a
     * local proxy that re-signs requests with the Referer the CDN expects.
     * That last part is why a plain "send the URL to the TV" implementation
     * fails: the links are time-limited and some edges reject a bare request.
     *
     * Until that is built, this surfaces the options instead of pretending.
     */
    fun onCastRequested() {
        _state.update {
            it.copy(
                notice = "投屏：官方投屏协议未公开。可行路线是 DLNA 发现 + 本机代理" +
                    "（补 Referer 并转发），可投到云视听小电视；该功能尚未实现。" +
                    "可先用「分享」把链接发到电视端 App。",
            )
        }
    }

    /** Adds or removes this video from 稍后再看. */
    fun toggleWatchLater() {
        val detail = _state.value.detail ?: return
        val adding = !_state.value.isInWatchLater
        _state.update { it.copy(isInWatchLater = adding) }

        viewModelScope.launch {
            val result = if (adding) {
                repository.addWatchLater(detail.bvid)
            } else {
                repository.removeWatchLater(detail.aid)
            }
            when (result) {
                is Result.Ok -> _state.update {
                    it.copy(notice = if (adding) "已加入稍后再看" else "已从稍后再看移除")
                }
                is Result.Err -> _state.update {
                    it.copy(isInWatchLater = !adding, error = result.message)
                }
            }
        }
    }

    /** Marks the state so the button reflects the watch-later list. */
    fun syncWatchLaterState() {
        val detail = _state.value.detail ?: return
        viewModelScope.launch {
            val list = repository.watchLater()
            if (list is Result.Ok) {
                val present = list.value.list.any { it.bvid == detail.bvid }
                _state.update { it.copy(isInWatchLater = present) }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** Share target for the current video. */
    fun shareUrl(): String = "https://www.bilibili.com/video/$bvid"

    fun shareTitle(): String = _state.value.detail?.title.orEmpty()

    private companion object {
        const val MAX_COINS = 2
    }

    /**
     * Favourites need a target folder, and the video page has no folder picker.
     * The user's first real folder is resolved on demand and cached, which
     * matches the common single-default-folder case.
     */
    private var defaultFolderId: Long? = null

    fun toggleFavorite() {
        val aid = _state.value.detail?.aid ?: return
        val adding = !_state.value.isFavorited
        _state.update { it.copy(isFavorited = adding) }

        viewModelScope.launch {
            val folderId = defaultFolderId ?: resolveDefaultFolder()
            if (folderId == null) {
                _state.update {
                    it.copy(isFavorited = !adding, error = "没有可用的收藏夹，请先登录")
                }
                return@launch
            }

            val result = if (adding) {
                repository.favorite(aid, addIds = listOf(folderId), delIds = emptyList())
            } else {
                repository.favorite(aid, addIds = emptyList(), delIds = listOf(folderId))
            }
            if (result is Result.Err) {
                _state.update { it.copy(isFavorited = !adding, error = result.message) }
            }
        }
    }

    private suspend fun resolveDefaultFolder(): Long? {
        val mid = session.midFlow.first()
        if (mid == 0L) return null
        val folders = (repository.favFolders(mid) as? Result.Ok)?.value?.list.orEmpty()
        // Folder id 0 is Bilibili's placeholder for "default"; real folders
        // always carry a positive media id.
        val resolved = folders.firstOrNull { it.id > 0 }?.id
        defaultFolderId = resolved
        return resolved
    }

    fun toggleFollow() {
        val mid = _state.value.detail?.owner?.mid ?: return
        val following = !_state.value.isFollowing
        _state.update { it.copy(isFollowing = following) }
        viewModelScope.launch {
            val result = repository.modifyRelation(mid, if (following) 1 else 2)
            if (result is Result.Err) {
                _state.update { it.copy(isFollowing = !following, error = result.message) }
            }
        }
    }

    fun onReplyTextChanged(text: String) = _state.update { it.copy(replyText = text) }

    fun sendReply() {
        val text = _state.value.replyText.trim()
        val oid = _state.value.detail?.aid ?: return
        if (text.isEmpty() || _state.value.isSendingReply) return

        _state.update { it.copy(isSendingReply = true) }
        viewModelScope.launch {
            when (val result = repository.addReply(oid, text)) {
                is Result.Ok -> {
                    _state.update {
                        it.copy(
                            replies = listOf(result.value) + it.replies,
                            replyText = "",
                            isSendingReply = false,
                        )
                    }
                }
                is Result.Err -> _state.update {
                    it.copy(isSendingReply = false, error = result.message)
                }
            }
        }
    }

    fun likeReply(rpid: Long) {
        val oid = _state.value.detail?.aid ?: return
        viewModelScope.launch { repository.likeReply(oid, rpid, liked = true) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** mid of the uploader, for navigating to their space. */
    fun uploaderMid(): Long = _state.value.detail?.owner?.mid ?: 0L
}
