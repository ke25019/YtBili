package com.ytstyle.bili.ui.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.danmaku.DanmakuItem
import com.ytstyle.bili.data.danmaku.DanmakuParser
import com.ytstyle.bili.data.log.AppLog
import com.ytstyle.bili.data.net.DashTrack
import com.ytstyle.bili.data.net.NetworkKind
import com.ytstyle.bili.data.net.NetworkStatus
import com.ytstyle.bili.data.net.EmoteItem
import com.ytstyle.bili.data.net.FavFolder
import com.ytstyle.bili.data.net.Owner
import com.ytstyle.bili.data.net.PageList
import com.ytstyle.bili.data.net.PgcEpisode
import com.ytstyle.bili.data.net.PgcSeason
import com.ytstyle.bili.data.net.PlayUrlData
import com.ytstyle.bili.data.net.RecommendItem
import com.ytstyle.bili.data.net.Reply
import com.ytstyle.bili.data.net.Stat
import com.ytstyle.bili.data.net.ViewDetail
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.REPLY_PAGE_SIZE
import com.ytstyle.bili.data.repo.Result
import com.ytstyle.bili.data.session.SessionStore
import com.ytstyle.bili.data.settings.AudioPreference
import com.ytstyle.bili.data.settings.AutoplayMode
import com.ytstyle.bili.data.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import javax.inject.Inject

/**
 * Comment ordering. The numbers are Bilibili's own `mode` values.
 *
 * `hot` returns the pinned/heat-ordered list the official client opens with;
 * `new` is chronological.
 */
enum class ReplySort(val mode: Int, val label: String) {
    Hot(3, "最热"),
    New(2, "最新"),
}

/** Quality tiers Bilibili exposes, in the order the picker shows them. */
enum class Quality(val qn: Int, val label: String) {
    Q1080P60(116, "1080P60"),
    Q1080P(80, "1080P"),
    Q720P(64, "720P"),
    Q480P(32, "480P"),
    Q360P(16, "360P");

    companion object {
        /** Maps a stored preference back to a tier; unknown values fall to 1080P. */
        fun fromQn(qn: Int?): Quality = entries.firstOrNull { it.qn == qn } ?: Q1080P
    }
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
    /**
     * Concurrent viewers, 0 when unknown. Only the landscape player shows it,
     * and it stays hidden at 0 because the endpoint is unreliable.
     */
    val onlineCount: Int = 0,
    val danmakuEnabled: Boolean = true,
    val currentQuality: Quality = Quality.Q1080P,
    val availableQualities: List<Quality> = emptyList(),
    val audioOnly: Boolean = false,
    /** Mirrors the settings store; the surface starts paused when it is off. */
    val autoplay: Boolean = true,
    /**
     * False until the settings store has actually been read. The player waits
     * for this before touching `playWhenReady`, so a stored "don't autoplay"
     * cannot be preceded by a burst of sound from the default value.
     */
    val autoplayResolved: Boolean = false,
    /** The policy behind [autoplay], so the UI can explain a paused start. */
    val autoplayMode: AutoplayMode = AutoplayMode.Always,
    /** True when [autoplay] was suppressed by the metered-link rule. */
    val autoplayBlockedByNetwork: Boolean = false,
    val replyText: String = "",
    val isSendingReply: Boolean = false,
    /** Whether a session exists; drives the comment composer's affordances. */
    val isLoggedIn: Boolean = false,
    /**
     * Cookie header forwarded to the media CDN. Higher quality tiers are tied
     * to the session, and Bilibili's edge hosts reject unauthenticated
     * requests for them.
     */
    val mediaCookie: String = "",
    /** Coins given to this video, so the button can show "已投N币". */
    val coinsGiven: Int = 0,
    /** `[token] -> image`, used to render emotes inside comments. */
    val emotes: Map<String, EmoteItem> = emptyMap(),
    /** One-shot message, e.g. explaining that casting is unavailable. */
    val notice: String? = null,
    /** Danmaku appearance, mirroring the settings store. */
    val danmakuConfig: DanmakuConfig = DanmakuConfig(),
    /** True once the video is in the watch-later list. */
    val isInWatchLater: Boolean = false,
    /**
     * Single-file URL handed to a DLNA renderer, resolved on demand.
     *
     * Playback uses DASH — two tracks and no `durl` — and a television plays
     * exactly one URL, so the progressive MP4 is a separate request. Until it
     * arrives the cast sheet has nothing to send.
     */
    val castUrl: String? = null,
    /** True while [castUrl] is being resolved, so the sheet can say so. */
    val isResolvingCast: Boolean = false,
    /**
     * 追番 state for the season being watched.
     *
     * Read from the season payload's `user_status.follow`, so the button shows
     * the truth on the first frame instead of after a round trip.
     */
    val seasonFollowed: Boolean = false,

    // ---- interaction accuracy -------------------------------------------------
    //
    // The page's `stat` block is a snapshot taken when the page loaded. Tapping
    // like/coin/favourite changes the *server's* number, not this snapshot, so
    // without tracking what this session did the counter stays frozen while the
    // icon flips — which is exactly what "数据不准确" described. The deltas below
    // are applied on top of the snapshot instead of mutating it, so a re-fetch
    // of the same video cannot double-count.
    val likeDelta: Int = 0,
    val coinDelta: Int = 0,
    val favDelta: Int = 0,

    // ---- favourites ----------------------------------------------------------
    val favFolders: List<FavFolder> = emptyList(),
    /** Drives the folder picker sheet. */
    val showFavPicker: Boolean = false,
    /** Folders the video was confirmed to be in when the page loaded. */
    val favFolderIds: List<Long> = emptyList(),

    // ---- replies -------------------------------------------------------------
    /** Non-null while the composer is answering somebody's comment. */
    val replyTargetName: String? = null,
    val replyTargetRpid: Long = 0,
    val replyTargetRoot: Long = 0,
    /** Page of top-level comments already loaded; the server pages at 20. */
    val replyPage: Int = 1,
    /**
     * Cursor for the next page of comments.
     *
     * `x/v2/reply/wbi/main` ignores `pn` and advances only by this opaque
     * `pagination_str` offset, so it is the real paging state.
     */
    val replyOffset: String = "",
    /** 最热 (default) or 最新. */
    val replySort: ReplySort = ReplySort.Hot,
    /** Preferred audio track, from 设置 → 播放 → 音画设置. */
    val audioPreference: AudioPreference = AudioPreference.Auto,
    val isLoadingMoreReplies: Boolean = false,
    /** True while the server reports more top-level comments than are loaded. */
    val hasMoreReplies: Boolean = false,
    /** Total top-level comments, from the endpoint's `page.count`. */
    val replyTotalCount: Int = 0,
    /**
     * rpids the server returned in `top_replies`, i.e. the pinned comments.
     *
     * The payload distinguishes them only by which array they arrive in, so the
     * ids are remembered here to draw the TOP badge on the right rows.
     */
    val topReplyIds: Set<Long> = emptySet(),
    /** Threads whose full reply list has been fetched, so the button hides. */
    val expandedThreads: Set<Long> = emptySet(),
    /**
     * Feedback shown directly under the composer. A floating strip at the
     * bottom of the screen was invisible whenever the keyboard was up, which is
     * exactly when somebody is trying to post a comment.
     */
    val commentFeedback: String? = null,
    val commentFeedbackIsError: Boolean = false,

    // ---- danmaku composer (the pill in the player header) --------------------
    /** Colour for outgoing danmaku; forced to white when the account cannot. */
    val danmakuColor: Long = 0xFFFFFF,
    /**
     * True when the selected swatch is the 大会员 专属渐变彩色.
     *
     * That one is not a colour: it is requested as `colorful=60001` and the
     * plain `color` goes along as white.
     */
    val danmakuVipGradient: Boolean = false,
    /** `mode` for the outgoing danmaku: 1 滚动, 5 置顶, 4 置底. */
    val danmakuMode: Int = DanmakuSendMode.SCROLL,
    /** `fontsize` for the outgoing danmaku: 25 默认, 18 较小. */
    val danmakuFontSize: Int = DanmakuSendSize.NORMAL,
    /**
     * True when the signed-in account may post **coloured** danmaku.
     *
     * Bilibili's 权益与等级 table unlocks 彩色弹幕 at **Lv2** — it is not a 大会员
     * privilege. Treating it as one meant a non-paying Lv2+ account could not
     * pick a colour the server would have accepted.
     */
    val canSendColorDanmaku: Boolean = false,
    /** True only for 大会员: the tier that additionally gets 专属渐变彩色. */
    val canSendVipColorful: Boolean = false,
    val danmakuText: String = "",
    val isSendingDanmaku: Boolean = false,
    /** Result of the last danmaku post, shown under the composer. */
    val danmakuFeedback: String? = null,
    val danmakuFeedbackIsError: Boolean = false,

    /** Related videos for the rail under the player. */
    val related: List<RecommendItem> = emptyList(),

    // ---- PGC (番剧 / 影视) ----------------------------------------------------
    val isPgc: Boolean = false,
    val episodes: List<PgcEpisode> = emptyList(),
    val currentEpId: Long = 0,
    val seasonId: Long = 0,
    /** e.g. 番剧 / 国创 / 电影, shown where an archive shows the uploader. */
    val seasonTypeName: String = "",

    /**
     * Where playback should start, in milliseconds.
     *
     * Comes from Bilibili's own watch-progress record, so opening a video on
     * this device continues from where *any* device left off. 0 means "start at
     * the beginning".
     */
    val startPositionMs: Long = 0L,

    /**
     * Identifies the thing being played (`bvid:cid`).
     *
     * The player compares this across media-source rebuilds: the same key means
     * a quality or audio switch, which must **not** reset the position; a
     * different key means another video or episode, which must.
     */
    val contentKey: String = "",
) {
    /**
     * Counts the UI shows: the snapshot plus this session's changes. Clamped at
     * zero so a failed optimistic revert can never render a negative count.
     */
    val likeCount: Long get() = ((detail?.stat?.like ?: 0L) + likeDelta).coerceAtLeast(0L)
    val coinCount: Long get() = ((detail?.stat?.coin ?: 0L) + coinDelta).coerceAtLeast(0L)
    val favCount: Long get() = ((detail?.stat?.favorite ?: 0L) + favDelta).coerceAtLeast(0L)

    /**
     * What comments are attached to. Archives are keyed by `aid`; bangumi is
     * keyed by its **season** id — verified against the live endpoint, where
     * `oid=<ep_id>` returns an empty list while `oid=<season_id>` returns the
     * episode's comments.
     */
    val commentOid: Long get() = if (isPgc) seasonId else (detail?.aid ?: 0L)

    /** Video id the danmaku segments are keyed by. */
    val currentCid: Long get() = detail?.cid ?: 0L
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: BiliRepository,
    private val session: SessionStore,
    private val settings: SettingsStore,
    /** Read-only: the autoplay rule has to know whether this link is metered. */
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /**
     * Either a `bvid`, or a PGC handle: `ss<seasonId>` for a whole season or
     * `ep<epId>` for one episode. Routing bangumi through the same screen and
     * the same player is what replaced "open it in a browser".
     */
    private val videoId: String = savedStateHandle.get<String>("bvid").orEmpty()

    private val isPgcRequest: Boolean
        get() = videoId.startsWith("ss") || videoId.startsWith("ep")

    private val _state = MutableStateFlow(PlayerUiState(isPgc = isPgcRequest))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Exposed to the player so progress can be reported back for resume. */
    var lastPositionMs: Long = 0L
        private set

    /** Cached so the favourite picker does not refetch the folder list. */
    private var cachedFolders: List<FavFolder> = emptyList()

    /** Last position handed to the progress endpoint, for the throttle. */
    private var lastReportedMs = 0L

    init {
        load()
        observeDanmakuSettings()
        observeAutoplay()
        observeSession()
        observePrivileges()
    }

    /**
     * Tracks whether a session exists so the composer can say "登录后可评论"
     * instead of accepting text and failing on submit.
     */
    private fun observeSession() {
        viewModelScope.launch {
            session.cookieFlow.collect { cookie ->
                _state.update { it.copy(isLoggedIn = cookie.isNotEmpty()) }
                if (cookie.isEmpty()) {
                    // A signed-out account cannot post colour either.
                    _state.update {
                        it.copy(
                            canSendColorDanmaku = false,
                            canSendVipColorful = false,
                            danmakuVipGradient = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * Reads which danmaku privileges this account actually has.
     *
     * `nav` is the only endpoint that reports VIP status and account level on
     * this screen, and it also refreshes the WBI keys, so the extra call is not
     * wasted. One call per player instance.
     *
     * Two separate tiers, because Bilibili has two: **彩色弹幕** (any solid
     * colour) unlocks at Lv2 per its 权益与等级 table, while the **专属渐变彩色**
     * is the 大会员 tier of the same control and is requested as
     * `colorful=60001`.
     */
    private fun observePrivileges() {
        viewModelScope.launch {
            when (val nav = repository.nav()) {
                is Result.Ok -> {
                    val isVip = nav.value.vipStatus == 1
                    val level = nav.value.level_info?.currentLevel ?: 0
                    _state.update {
                        it.copy(
                            canSendColorDanmaku = isVip || level >= COLOR_DANMAKU_MIN_LEVEL,
                            canSendVipColorful = isVip,
                            // Losing 大会员 must not leave the gradient selected
                            // on an account that can no longer send it.
                            danmakuVipGradient = it.danmakuVipGradient && isVip,
                        )
                    }
                    AppLog.d("player", "danmaku privileges vip=$isVip level=$level")
                }
                is Result.Err -> Unit
            }
        }
    }

    /** Selecting a solid swatch also clears a previously chosen gradient. */
    fun setDanmakuColor(color: Long) =
        _state.update { it.copy(danmakuColor = color, danmakuVipGradient = false) }

    /** The 大会员 gradient; ignored for accounts that cannot send it. */
    fun setDanmakuVipGradient() {
        if (!_state.value.canSendVipColorful) return
        _state.update { it.copy(danmakuVipGradient = true) }
    }

    fun setDanmakuMode(mode: Int) = _state.update { it.copy(danmakuMode = mode) }

    fun setDanmakuFontSize(size: Int) = _state.update { it.copy(danmakuFontSize = size) }

    // ---- 弹幕设置, editable from the player ----
    //
    // These write to the same store the settings page uses, so a change made
    // mid-playback is both immediate (the state flow re-emits) and persistent.
    // The panel is `DanmakuSettingsPanel`, shared with 设置 → 弹幕设置.

    fun setDanmakuScale(value: Float) = viewModelScope.launch { settings.setDanmakuScale(value) }

    fun setDanmakuOpacity(value: Float) = viewModelScope.launch { settings.setDanmakuOpacity(value) }

    fun setDanmakuArea(value: Float) = viewModelScope.launch { settings.setDanmakuArea(value) }

    fun setDanmakuSpeed(value: Float) = viewModelScope.launch { settings.setDanmakuSpeed(value) }

    /** Adds or removes one mode from the visible set; an empty set means all. */
    fun toggleDanmakuType(mode: Int) {
        val current = _state.value.danmakuConfig.types
        val next = if (mode in current) current - mode else current + mode
        viewModelScope.launch { settings.setDanmakuTypes(next) }
    }

    fun resetDanmakuSettings() {
        viewModelScope.launch {
            settings.setDanmakuScale(1f)
            settings.setDanmakuOpacity(1f)
            settings.setDanmakuArea(1f)
            settings.setDanmakuSpeed(8f)
            settings.setDanmakuTypes(DEFAULT_DANMAKU_TYPES)
        }
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

    /**
     * Turns the stored policy plus the *current* link into a yes/no answer.
     *
     * The network is sampled when the policy is read rather than watched: this
     * decides whether playback starts at page load, and a mid-playback switch
     * from Wi-Fi to mobile data must not pause a video the user is watching.
     */
    private fun observeAutoplay() {
        viewModelScope.launch {
            settings.autoplayMode.collect { mode ->
                val kind = NetworkStatus.current(appContext)
                val allowed = when (mode) {
                    AutoplayMode.Always -> true
                    AutoplayMode.WifiOnly -> kind.isUnmetered
                    AutoplayMode.Never -> false
                }
                _state.update {
                    it.copy(
                        autoplayMode = mode,
                        autoplay = allowed,
                        // Only a metered link is worth explaining; "never" is a
                        // choice the user made and needs no note.
                        autoplayBlockedByNetwork = !allowed &&
                            mode == AutoplayMode.WifiOnly &&
                            kind == NetworkKind.Cellular,
                        autoplayResolved = true,
                    )
                }
            }
        }
    }

    fun load() {
        if (videoId.isEmpty()) {
            _state.update { it.copy(isLoading = false, error = "无效的视频 ID") }
            return
        }
        // A new video or episode invalidates the cast URL with it: the old one
        // is signed for the previous cid and would 403 on the television.
        _state.update { it.copy(isLoading = true, error = null, castUrl = null) }

        viewModelScope.launch {
            // The media data source needs the session cookie as well as the
            // web API client, so read it once here.
            _state.update { it.copy(mediaCookie = session.currentCookie()) }

            // 音画设置 has to be read *before* the first playurl call — the
            // quality is a request parameter, not something that can be applied
            // after the stream has been resolved.
            _state.update {
                it.copy(
                    currentQuality = Quality.fromQn(settings.defaultQuality.first()),
                    audioPreference = settings.audioPreference.first(),
                )
            }

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

            if (isPgcRequest) loadPgc() else loadArchive()
        }
    }

    /** Retry entry point used by the error banner. */
    fun retry() = load()

    // ---- archive (normal video) ----

    private suspend fun loadArchive() {
        when (val detailResult = repository.viewDetail(videoId)) {
            is Result.Err -> _state.update {
                it.copy(isLoading = false, error = detailResult.message)
            }

            is Result.Ok -> {
                val detail = detailResult.value
                _state.update { it.copy(detail = detail) }

                // Cross-device resume. This runs *before* the cid is chosen
                // because the answer can name a different part of a multi-part
                // video (`last_play_cid`), and starting on the wrong part would
                // then resume at a timestamp that belongs to another one.
                val progress = (repository.playerProgress(videoId, detail.cid) as? Result.Ok)?.value
                val resumeCid = progress?.lastPlayCid ?: 0L
                val resumeMs = progress?.lastPlayTime ?: 0L
                val firstCid = detail.pages.firstOrNull { it.cid == resumeCid }?.cid
                    ?: detail.pages.firstOrNull()?.cid
                    ?: detail.cid

                val resumeFrom = resumeMs.takeIf { ms -> ms >= RESUME_MIN_MS } ?: 0L
                _state.update {
                    it.copy(
                        startPositionMs = resumeFrom,
                        contentKey = "$videoId:$firstCid",
                        // Say so, otherwise a jump to the middle looks like a
                        // bug rather than the resume the user asked for.
                        notice = if (resumeFrom > 0L) {
                            "已从 %d:%02d 继续播放".format(
                                resumeFrom / 60_000,
                                (resumeFrom / 1000) % 60,
                            )
                        } else {
                            it.notice
                        },
                    )
                }

                // Detail, streams and comments are independent; fetch them
                // together so the screen is not gated on the slowest call.
                // `coroutineScope` is what gives this suspend function the
                // receiver `async` needs.
                coroutineScope {
                    val playDeferred = async {
                        repository.playUrl(videoId, firstCid, _state.value.currentQuality.qn)
                    }
                    val danmakuDeferred = async { loadDanmaku(firstCid, detail.duration) }
                    val repliesDeferred = async { repository.replies(detail.aid) }
                    val relatedDeferred = async { repository.related(videoId) }

                    applyPlayData(playDeferred.await())
                    applyDanmaku(danmakuDeferred.await())
                    applyReplies(repliesDeferred.await())
                    applyRelated(relatedDeferred.await())
                }

                syncInteractionState(detail)
                syncWatchLaterState()
            }
        }
    }

    /**
     * Reads the interactions that the page payload cannot tell us: whether this
     * session already liked / coined / favourited / followed. All four are
     * best-effort — an anonymous caller is answered with `-101`, which must not
     * surface as an error on a perfectly playable video.
     */
    private suspend fun syncInteractionState(detail: ViewDetail) = coroutineScope {
        val liked = async { repository.hasLiked(detail.bvid) }
        val coins = async { repository.coinsGiven(detail.bvid) }
        val favourited = async { repository.isFavourited(detail.aid) }
        val following = async {
            val mid = detail.owner?.mid ?: 0L
            if (mid > 0) repository.isFollowing(mid) else Result.Err("无 UP 主")
        }

        val likeValue = liked.await()
        val coinValue = coins.await()
        val favValue = favourited.await()
        val followValue = following.await()

        _state.update { current ->
            current.copy(
                isLiked = (likeValue as? Result.Ok)?.value ?: current.isLiked,
                coinsGiven = (coinValue as? Result.Ok)?.value ?: current.coinsGiven,
                isFavorited = (favValue as? Result.Ok)?.value ?: current.isFavorited,
                isFollowing = (followValue as? Result.Ok)?.value ?: current.isFollowing,
            )
        }

        // "N人正在看", for the landscape player only. Kept out of the parallel
        // block above because it is genuinely optional: the endpoint answered
        // `-404` throughout development, and a missing count must leave the line
        // hidden rather than showing a zero.
        repository.onlineCount(detail.bvid, detail.cid).let { result ->
            when (result) {
                is Result.Ok -> if (result.value > 0) {
                    _state.update { it.copy(onlineCount = result.value) }
                }
                is Result.Err -> AppLog.d("player", "online_count code=${result.code}")
            }
        }
    }

    // ---- PGC (番剧 / 影视) ----

    /**
     * Resolves a season from either handle, picks the episode to play, and
     * then reuses the archive playback path for streams, danmaku and comments.
     */
    private suspend fun loadPgc() {
        val seasonId = videoId.removePrefix("ss").removePrefix("ep").toLongOrNull() ?: 0L
        val asEpisode = videoId.startsWith("ep")

        val seasonResult = if (asEpisode) {
            repository.pgcSeason(epId = seasonId)
        } else {
            repository.pgcSeason(seasonId = seasonId)
        }

        when (seasonResult) {
            is Result.Err -> _state.update {
                it.copy(isLoading = false, error = seasonResult.message)
            }

            is Result.Ok -> {
                val season = seasonResult.value
                val episode = pickEpisode(season, if (asEpisode) seasonId else 0L)
                if (episode == null) {
                    _state.update {
                        it.copy(isLoading = false, error = "该剧集暂无可播放的内容")
                    }
                    return
                }
                _state.update { it.copy(detail = pgcDetail(season, episode)) }
                applyPgcSeason(season, episode)

                // The player page's own stat block comes from the season, so the
                // counters and the episode list arrive together with the streams.
                coroutineScope {
                    val playDeferred = async {
                        repository.pgcPlayUrl(
                            episode.playId,
                            episode.cid,
                            _state.value.currentQuality.qn,
                        )
                    }
                    val danmakuDeferred = async {
                        loadDanmaku(episode.cid, episode.durationSeconds)
                    }
                    val repliesDeferred = async { repository.replies(season.seasonId) }
                    val interactionDeferred = async { syncPgcInteractionState(episode) }

                    applyPlayData(playDeferred.await())
                    applyDanmaku(danmakuDeferred.await())
                    applyReplies(repliesDeferred.await())
                    interactionDeferred.await()
                }
            }
        }
    }

    private fun pickEpisode(season: PgcSeason, epId: Long): PgcEpisode? {
        val episodes = season.episodes
        if (epId > 0) {
            episodes.firstOrNull { it.playId == epId }?.let { return it }
        }
        return episodes.firstOrNull()
    }

    private fun applyPgcSeason(season: PgcSeason, episode: PgcEpisode) {
        _state.update {
            it.copy(
                isPgc = true,
                episodes = season.episodes,
                currentEpId = episode.playId,
                seasonId = season.seasonId,
                seasonTypeName = season.seasonTypeName,
                // PGC resume is not fetched (the episode's own progress API was
                // never verified), but the key still has to identify the
                // episode so switching between them resets the position.
                contentKey = "$videoId:${episode.cid}",
                startPositionMs = 0L,
                // A PGC episode is an archive underneath, so 点赞/投币 do apply —
                // they are addressed by the episode's own bvid/aid (see
                // [archiveBvid]) rather than the `ss…`/`ep…` route id. 收藏 and
                // 稍后再看 still have no PGC equivalent, and 追番 replaces the
                // UP-follow as the meaningful subscription here.
                isFavorited = false,
                isFollowing = false,
                isInWatchLater = false,
                seasonFollowed = season.userStatus?.follow == 1,
            )
        }
    }

    /**
     * 点赞 / 投币 state for a 番剧 episode.
     *
     * Runs the same two reads as [syncInteractionState], but against the
     * episode's own archive ids: the season payload has no per-episode like or
     * coin information at all, so without this the buttons would start from a
     * wrong zero and could offer a third coin.
     */
    private suspend fun syncPgcInteractionState(episode: PgcEpisode) = coroutineScope {
        val bvid = episode.bvid.takeIf { it.startsWith("BV") } ?: return@coroutineScope
        val liked = async { repository.hasLiked(bvid) }
        val coins = async { repository.coinsGiven(bvid) }

        val likeValue = liked.await()
        val coinValue = coins.await()

        _state.update { current ->
            current.copy(
                isLiked = (likeValue as? Result.Ok)?.value ?: current.isLiked,
                coinsGiven = (coinValue as? Result.Ok)?.value ?: current.coinsGiven,
            )
        }
    }

    /**
     * Builds the archive-shaped detail the shared sections render.
     *
     * `duration` and `PageList.duration` are converted from the episode's
     * **milliseconds** into the seconds the archive models use.
     */
    private fun pgcDetail(season: PgcSeason, episode: PgcEpisode): ViewDetail {
        val stat = season.stat
        return ViewDetail(
            bvid = videoId,
            aid = episode.aid,
            cid = episode.cid,
            title = season.title.ifBlank { episode.longTitle },
            pic = episode.cover.ifBlank { season.cover },
            desc = season.evaluate,
            duration = episode.durationSeconds,
            pubdate = episode.pubTime,
            owner = season.upInfo?.let {
                Owner(mid = it.mid, name = it.uname, face = it.avatar)
            },
            stat = Stat(
                view = stat?.views ?: 0,
                like = stat?.likes ?: 0,
                danmaku = stat?.danmakus ?: 0,
                reply = stat?.reply ?: 0,
                favorite = stat?.favorite ?: 0,
                coin = stat?.coins ?: 0,
                share = stat?.share ?: 0,
            ),
            pages = season.episodes.mapIndexed { index, item ->
                PageList(
                    cid = item.cid,
                    page = index + 1,
                    from = item.bvid,
                    part = item.longTitle.ifBlank { item.title },
                    duration = item.durationSeconds,
                )
            },
        )
    }

    /** Switches episodes without leaving the screen. */
    fun selectEpisode(episode: PgcEpisode) {
        if (episode.playId == _state.value.currentEpId) return
        val snapshot = _state.value
        val detail = snapshot.detail ?: return
        val quality = snapshot.currentQuality.qn
        val seasonId = snapshot.seasonId

        _state.update {
            it.copy(
                isLoading = true,
                danmaku = emptyList(),
                replies = emptyList(),
                replyPage = 1,
                replyOffset = "",
                hasMoreReplies = false,
                error = null,
                // Signed for the previous episode's cid; the new one needs its own.
                castUrl = null,
                currentEpId = episode.playId,
                // Keeping the season-level counters: only the stream changes.
                detail = detail.copy(
                    cid = episode.cid,
                    duration = episode.durationSeconds,
                    pubdate = episode.pubTime,
                ),
                // A new episode is new content, so the player must reset the
                // position rather than treat this as a quality switch.
                contentKey = "$videoId:${episode.cid}",
                startPositionMs = 0L,
            )
        }

        viewModelScope.launch {
            val playDeferred = async {
                repository.pgcPlayUrl(episode.playId, episode.cid, quality)
            }
            val danmakuDeferred = async { loadDanmaku(episode.cid, episode.durationSeconds) }
            val repliesDeferred = async { repository.replies(seasonId) }

            applyPlayData(playDeferred.await())
            applyDanmaku(danmakuDeferred.await())
            applyReplies(repliesDeferred.await())
        }
    }

    // ---- streams ----

    private fun applyPlayData(result: Result<PlayUrlData>) {
        when (result) {
            is Result.Err -> _state.update {
                it.copy(isLoading = false, error = result.message)
            }
            is Result.Ok -> {
                val data = result.value
                val dash = data.dash

                // Pick within the tier Bilibili actually returned. Asking for
                // 1080P on a video that only offers 480P silently yields 480P,
                // and blindly taking the highest-bandwidth track would put a
                // different tier on screen from the one the menu claims.
                val wanted = data.quality.takeIf { it > 0 } ?: _state.value.currentQuality.qn
                val video = dash?.video
                    ?.filter { it.id == wanted }
                    ?.maxByOrNull { it.bandwidth }
                    ?: dash?.video?.maxByOrNull { it.bandwidth }
                val audio = pickAudio(dash?.audio)

                // The quality list is intersected with what the payload says is
                // acceptable, so the menu cannot offer an unusable tier.
                val qualities = Quality.entries.filter { it.qn in data.accept_quality }
                val effective = qualities.firstOrNull { it.qn == data.quality }
                    ?: qualities.firstOrNull { it.qn == wanted }
                    ?: qualities.firstOrNull()
                    ?: _state.value.currentQuality

                _state.update {
                    it.copy(
                        isLoading = false,
                        playData = data,
                        videoTrack = video,
                        audioTrack = audio,
                        fallbackUrl = if (video == null) data.progressiveUrl else null,
                        availableQualities = qualities.ifEmpty { listOf(effective) },
                        currentQuality = effective,
                        error = null,
                    )
                }
            }
        }
    }

    /**
     * Chooses the audio track, honouring 音画设置.
     *
     * A requested tier that the video does not offer falls back to the highest
     * bitrate rather than failing: the preference is a wish, not a guarantee.
     */
    private fun pickAudio(tracks: List<DashTrack>?): DashTrack? {
        if (tracks.isNullOrEmpty()) return null
        val preferred = _state.value.audioPreference.id
        if (preferred != 0) {
            tracks.firstOrNull { it.id == preferred }?.let { return it }
        }
        return tracks.maxByOrNull { it.bandwidth }
    }

    private fun applyDanmaku(result: Result<List<DanmakuItem>>) {
        if (result is Result.Ok) {
            _state.update { it.copy(danmaku = result.value) }
        }
    }

    /**
     * Merges a comment page into the list.
     *
     * The endpoint serves 20 top-level comments per page and reports the real
     * total in `page.count`. Before this the screen kept only page one, so any
     * video with more than twenty comments looked like it had stopped loading —
     * which is what "评论区评论加载不完全" was.
     *
     * @param append true for "load more", which de-duplicates by `rpid`;
     *   `topReplies` are only taken from the first page, since Bilibili repeats
     *   the pinned comments on every page.
     */
    private fun applyReplies(
        result: Result<com.ytstyle.bili.data.net.ReplyListData>,
        append: Boolean = false,
    ) {
        if (result !is Result.Ok) return
        val data = result.value
        val incoming = buildList {
            if (!append) data.topReplies?.let(::addAll)
            addAll(data.replies)
        }

        _state.update { current ->
            val merged = if (append) {
                val known = current.replies.mapTo(HashSet()) { it.rpid }
                current.replies + incoming.filter { known.add(it.rpid) }
            } else {
                incoming
            }
            val added = merged.size - current.replies.size
            val total = data.page?.count ?: merged.size
            val cursor = data.cursor
            val nextOffset = cursor?.paginationReply?.nextOffset.orEmpty()
            current.copy(
                replies = merged,
                replyTotalCount = total,
                // Only the first page carries `top_replies`.
                topReplyIds = if (append) {
                    current.topReplyIds
                } else {
                    data.topReplies.orEmpty().mapTo(HashSet()) { it.rpid }
                },
                // The cursor is the only thing that advances this endpoint.
                // Falling back to the old size heuristic keeps older responses
                // (and the reply-thread endpoint, which still pages by `pn`)
                // working.
                replyOffset = nextOffset.ifBlank { current.replyOffset },
                hasMoreReplies = if (cursor != null) {
                    !cursor.isEnd && nextOffset.isNotBlank() && added > 0
                } else {
                    //  * the page actually contributed something new;
                    //  * either the page was full (so a further page may exist
                    //    even if `count` under-reports) or the total says so.
                    added > 0 &&
                        (data.replies.size >= REPLY_PAGE_SIZE || merged.size < total)
                },
                isLoadingMoreReplies = false,
            )
        }
    }

    /**
     * Switches the comment list between 最热 and 最新.
     *
     * `mode=3` is heat, `mode=2` is time — the two options the official client
     * offers. Changing it restarts from page one, because the pages of one
     * ordering do not continue into the other.
     */
    fun setReplySort(sort: ReplySort) {
        if (sort == _state.value.replySort) return
        val oid = _state.value.commentOid
        _state.update {
            it.copy(
                replySort = sort,
                replies = emptyList(),
                replyPage = 1,
                replyOffset = "",
                hasMoreReplies = false,
                isLoadingMoreReplies = false,
            )
        }
        if (oid == 0L) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMoreReplies = true) }
            applyReplies(repository.replies(oid, page = 1, mode = sort.mode))
        }
    }

    /** Fetches the next page of top-level comments. */
    fun loadMoreReplies() {
        val current = _state.value
        if (current.isLoadingMoreReplies || !current.hasMoreReplies) return
        val oid = current.commentOid
        if (oid == 0L) return

        _state.update { it.copy(isLoadingMoreReplies = true) }
        viewModelScope.launch {
            val next = current.replyPage + 1
            // The cursor is what actually moves the window; `pn` is sent for
            // compatibility but the endpoint ignores it.
            val result = repository.replies(
                oid = oid,
                page = next,
                mode = current.replySort.mode,
                offset = current.replyOffset,
            )
            when (result) {
                is Result.Ok -> {
                    _state.update { it.copy(replyPage = next) }
                    applyReplies(result, append = true)
                }
                is Result.Err -> _state.update {
                    it.copy(
                        isLoadingMoreReplies = false,
                        commentFeedback = result.message,
                        commentFeedbackIsError = true,
                    )
                }
            }
        }
    }

    /**
     * Fetches every reply inside one comment thread.
     *
     * The main list only embeds a two-or-three reply preview per comment, so a
     * thread labelled "共 37 条回复" was previously unreadable past the first
     * few. The thread is remembered as expanded so the button disappears rather
     * than re-fetching on every tap.
     */
    fun expandThread(reply: Reply) {
        if (reply.rpid in _state.value.expandedThreads) return
        val oid = _state.value.commentOid
        if (oid == 0L) return

        viewModelScope.launch {
            when (val result = repository.replyThread(oid, reply.rpid)) {
                is Result.Ok -> _state.update { current ->
                    current.copy(
                        expandedThreads = current.expandedThreads + reply.rpid,
                        replies = current.replies.map { top ->
                            if (top.rpid == reply.rpid) {
                                top.copy(replies = result.value.replies)
                            } else {
                                top
                            }
                        },
                    )
                }
                is Result.Err -> _state.update {
                    it.copy(commentFeedback = result.message, commentFeedbackIsError = true)
                }
            }
        }
    }

    fun dismissCommentFeedback() = _state.update {
        it.copy(commentFeedback = null, commentFeedbackIsError = false)
    }

    private fun applyRelated(result: Result<List<RecommendItem>>) {
        if (result is Result.Ok) {
            _state.update {
                it.copy(
                    // The current video is sometimes echoed back in its own
                    // related list; showing "up next: this video" is pointless.
                    related = result.value.filter { item ->
                        item.bvid.isNotEmpty() && item.bvid != videoId
                    },
                )
            }
        }
    }

    // ---- 发弹幕 ----

    fun onDanmakuTextChanged(text: String) = _state.update {
        it.copy(danmakuText = text, danmakuFeedback = null, danmakuFeedbackIsError = false)
    }

    /**
     * Posts the composed danmaku at the current playback position.
     *
     * `oid` for this endpoint is the **cid**, and for a bangumi episode the
     * `bvid` it wants is the episode's own archive id — which is why the
     * episode's bvid is kept in `pages[].from` when a season is loaded.
     */
    fun sendDanmaku() {
        val current = _state.value
        val text = current.danmakuText.trim()
        if (text.isEmpty() || current.isSendingDanmaku) return

        if (!current.isLoggedIn) {
            _state.update {
                it.copy(danmakuFeedback = "请先登录后再发送弹幕", danmakuFeedbackIsError = true)
            }
            return
        }
        val cid = current.currentCid
        if (cid == 0L) {
            _state.update {
                it.copy(danmakuFeedback = "视频还没准备好，稍后再试", danmakuFeedbackIsError = true)
            }
            return
        }

        _state.update { it.copy(isSendingDanmaku = true, danmakuFeedback = null) }
        viewModelScope.launch {
            // The gradient is a *style*, not a colour: it goes in `colorful`
            // and the ordinary colour travels as white. A solid colour is only
            // sent when the account is allowed one, so a downgraded account
            // cannot have the server silently keep it white after the UI
            // promised otherwise.
            val gradient = current.danmakuVipGradient && current.canSendVipColorful
            val solid = if (current.canSendColorDanmaku) current.danmakuColor else 0xFFFFFF
            val result = repository.sendDanmaku(
                bvid = archiveBvid(),
                cid = cid,
                message = text,
                progressMs = lastPositionMs,
                mode = current.danmakuMode,
                fontSize = current.danmakuFontSize,
                color = if (gradient) 0xFFFFFF else solid,
                colorful = if (gradient) DANMAKU_COLORFUL_VIP else null,
            )
            when (result) {
                is Result.Ok -> _state.update {
                    it.copy(
                        isSendingDanmaku = false,
                        danmakuText = "",
                        danmakuFeedback = "弹幕已发送",
                        danmakuFeedbackIsError = false,
                    )
                }
                is Result.Err -> _state.update {
                    it.copy(
                        isSendingDanmaku = false,
                        danmakuFeedback = result.message,
                        danmakuFeedbackIsError = true,
                    )
                }
            }
        }
    }

    fun dismissDanmakuFeedback() = _state.update {
        it.copy(danmakuFeedback = null, danmakuFeedbackIsError = false)
    }

    /**
     * The bvid the danmaku endpoint expects.
     *
     * This is **not** `pages[].from`: on the archive endpoint that field is the
     * page's *source* (`"vupload"`, `"bilibili"`, `"hdvb"`), not a video id. It
     * only holds a bvid on the PGC path, where the season loader puts the
     * episode's own id there. Sending `bvid=vupload` is why 发弹幕 came back as
     * `-400 参数错误` for every normal video.
     *
     * Used by every write that speaks "archive": 发弹幕, 点赞 and 投币. For a
     * 番剧 episode the route id is `ss…`/`ep…`, which none of those endpoints
     * can resolve, while the episode's own bvid works with all three.
     */
    private fun archiveBvid(): String {
        val detail = _state.value.detail ?: return videoId
        val candidate = detail.pages.firstOrNull { it.cid == detail.cid }?.from.orEmpty()
        return if (candidate.startsWith("BV")) candidate else videoId
    }

    /**
     * Danmaku arrive as 6-minute Protobuf segments, so a long video needs more
     * than the first one or two. The segment count is derived from the video's
     * duration instead of being hard-coded, and the segments are fetched
     * concurrently because a two-hour video is twenty of them.
     *
     * Failures per segment are swallowed: a missing tail must not empty the
     * overlay for the part that did load.
     */
    private suspend fun loadDanmaku(cid: Long, durationSeconds: Int): Result<List<DanmakuItem>> =
        withContext(Dispatchers.IO) {
            try {
                val segments = ceil(durationSeconds / SEGMENT_SECONDS.toFloat())
                    .toInt()
                    .coerceIn(1, MAX_DANMAKU_SEGMENTS)

                val bodies = coroutineScope {
                    (1..segments).map { index ->
                        async { runCatching { repository.danmakuSegment(cid, index) }.getOrNull() }
                    }.awaitAll()
                }

                val items = ArrayList<DanmakuItem>()
                bodies.forEach { body ->
                    if (body != null) items += DanmakuParser.parse(body.byteStream())
                }
                Result.Ok(items)
            } catch (e: Exception) {
                Result.Err(e.message ?: "弹幕加载失败")
            }
        }

    fun onPositionChanged(positionMs: Long) {
        lastPositionMs = positionMs
        // Throttled inside; the player calls this ten times a second.
        reportProgress()
    }

    /**
     * Posts the current position so the account can continue on another device.
     *
     * Bilibili's own clients report every few seconds; this reports once per
     * [REPORT_INTERVAL_MS] of *movement*, so a paused video does not keep
     * posting the same number.
     *
     * @param force post regardless of the throttle — used when playback pauses,
     *   which is the moment a user is most likely to leave and resume elsewhere.
     */
    fun reportProgress(force: Boolean = false) {
        val current = _state.value
        // Only archives: the PGC report endpoint was never verified, and sending
        // an episode's aid to the archive endpoint would be a guess.
        if (current.isPgc) return
        val detail = current.detail ?: return
        if (detail.aid <= 0 || !current.isLoggedIn) return

        val position = lastPositionMs
        if (position <= 0) return
        if (!force && kotlin.math.abs(position - lastReportedMs) < REPORT_INTERVAL_MS) return

        lastReportedMs = position
        viewModelScope.launch {
            repository.reportProgress(detail.aid, detail.cid, position)
        }
    }

    fun toggleDanmaku() = _state.update { it.copy(danmakuEnabled = !it.danmakuEnabled) }

    fun toggleAudioOnly() = _state.update { it.copy(audioOnly = !it.audioOnly) }

    fun selectQuality(quality: Quality) {
        _state.update { it.copy(currentQuality = quality, isLoading = true, error = null) }
        viewModelScope.launch {
            val current = _state.value
            val result = if (current.isPgc) {
                val episode = current.episodes.firstOrNull { it.playId == current.currentEpId }
                if (episode == null) {
                    Result.Err("剧集信息缺失")
                } else {
                    repository.pgcPlayUrl(episode.playId, episode.cid, quality.qn)
                }
            } else {
                val cid = current.detail?.pages?.firstOrNull()?.cid
                    ?: current.detail?.cid
                    ?: return@launch
                repository.playUrl(videoId, cid, quality.qn)
            }
            applyPlayData(result)
        }
    }

    // ---- interactions ----

    fun toggleLike() {
        // 番剧 included: an episode is an archive underneath, so the like goes
        // to the same endpoint, addressed by the episode's own bvid.
        if (!_state.value.isLoggedIn) {
            _state.update { it.copy(notice = "请先登录后再点赞") }
            return
        }
        val target = !_state.value.isLiked
        // Optimistic: Bilibili's like endpoint is slow and the tap should feel
        // instant. The delta is what moves the counter with the icon.
        _state.update {
            it.copy(
                isLiked = target,
                likeDelta = it.likeDelta + if (target) 1 else -1,
            )
        }
        viewModelScope.launch {
            val result = repository.like(archiveBvid(), target)
            if (result is Result.Err) {
                _state.update {
                    it.copy(
                        isLiked = !target,
                        likeDelta = it.likeDelta + if (target) -1 else 1,
                        notice = result.message,
                    )
                }
            } else {
                // Confirms the write: the icon and counter already moved, but a
                // sentence removes any doubt about whether it was saved.
                _state.update {
                    it.copy(notice = if (target) "已点赞" else "已取消点赞")
                }
            }
        }
    }

    /**
     * Bilibili caps a video at two coins, and coins are additive across calls,
     * so the running total is tracked to render "已投N币" and to stop the user
     * from attempting a third. The total is seeded from the server on load.
     */
    fun addCoin(count: Int, alsoLike: Boolean) {
        // 番剧 included, for the same reason as 点赞: the episode's own bvid is
        // a valid archive for this endpoint. `coinsGiven` is seeded per episode
        // by `syncPgcInteractionState`, so the two-coin cap still holds.
        if (!_state.value.isLoggedIn) {
            _state.update { it.copy(notice = "请先登录后再投币") }
            return
        }
        val alreadyGiven = _state.value.coinsGiven
        val remaining = MAX_COINS - alreadyGiven
        if (remaining <= 0) {
            _state.update { it.copy(notice = "同一视频最多投 2 个硬币") }
            return
        }
        val actual = count.coerceAtMost(remaining)
        val wasLiked = _state.value.isLiked

        viewModelScope.launch {
            when (val result = repository.coin(archiveBvid(), actual, alsoLike)) {
                is Result.Ok -> {
                    val liking = alsoLike && !wasLiked
                    _state.update {
                        it.copy(
                            coinsGiven = alreadyGiven + actual,
                            coinDelta = it.coinDelta + actual,
                            // 投币时顺带点赞 only moves the like state once.
                            isLiked = if (liking) true else it.isLiked,
                            likeDelta = it.likeDelta + if (liking) 1 else 0,
                            notice = "已投 $actual 个硬币",
                        )
                    }
                }
                is Result.Err -> _state.update { it.copy(notice = result.message) }
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
     * The sheet itself is opened by the screen; this only makes sure a castable
     * URL exists by the time a device is picked.
     */
    fun onCastRequested() = ensureCastSource()

    /**
     * Resolves the progressive URL casting needs, once per video/episode.
     *
     * The playing stream is DASH, which carries no `durl` at all, so a cast used
     * to be handed a blank source and could only answer "这个视频没有可投屏的
     * 单流地址". This asks the same playurl endpoint for `fnval=1` (one MP4)
     * instead — verified against the live endpoint to return exactly one `durl`.
     *
     * 720P is the tier asked for: progressive MP4 is not offered above 1080P and
     * many archives stop at 720P, so requesting higher would silently degrade
     * anyway.
     */
    fun ensureCastSource() {
        val current = _state.value
        if (current.castUrl != null || current.isResolvingCast) return
        _state.update { it.copy(isResolvingCast = true) }

        viewModelScope.launch {
            val result = if (current.isPgc) {
                val episode = current.episodes.firstOrNull { it.playId == current.currentEpId }
                if (episode == null) {
                    Result.Err("剧集信息缺失")
                } else {
                    repository.pgcPlayUrl(
                        episode.playId,
                        episode.cid,
                        CAST_QUALITY_QN,
                        progressive = true,
                    )
                }
            } else {
                val cid = current.detail?.pages?.firstOrNull()?.cid ?: current.detail?.cid
                if (cid == null) {
                    Result.Err("视频信息缺失")
                } else {
                    repository.playUrl(videoId, cid, CAST_QUALITY_QN, progressive = true)
                }
            }

            when (result) {
                is Result.Ok -> {
                    val url = result.value.progressiveUrl
                    AppLog.d("cast", "progressive source resolved=${!url.isNullOrBlank()}")
                    _state.update {
                        it.copy(
                            isResolvingCast = false,
                            castUrl = url,
                            notice = if (url.isNullOrBlank()) {
                                "这个视频没有可投屏的单流地址"
                            } else {
                                null
                            },
                        )
                    }
                }
                is Result.Err -> {
                    AppLog.w("cast", "progressive source failed code=${result.code}")
                    _state.update {
                        it.copy(
                            isResolvingCast = false,
                            castUrl = null,
                            notice = "无法获取投屏地址：${result.message}",
                        )
                    }
                }
            }
        }
    }

    /** Adds or removes this video from 稍后再看. */
    fun toggleWatchLater() {
        val detail = _state.value.detail ?: return
        if (_state.value.isPgc) {
            _state.update { it.copy(notice = "番剧请使用「追番」功能，稍后再看仅支持普通视频") }
            return
        }
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
                    it.copy(isInWatchLater = !adding, notice = result.message)
                }
            }
        }
    }

    /** Marks the state so the button reflects the watch-later list. */
    fun syncWatchLaterState() {
        val detail = _state.value.detail ?: return
        if (_state.value.isPgc) return
        viewModelScope.launch {
            val list = repository.watchLater()
            if (list is Result.Ok) {
                val present = list.value.list.any { it.bvid == detail.bvid }
                _state.update { it.copy(isInWatchLater = present) }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Share target for the current video. */
    fun shareUrl(): String = if (_state.value.isPgc) {
        val ep = _state.value.currentEpId
        if (ep > 0) {
            "https://www.bilibili.com/bangumi/play/ep$ep"
        } else {
            "https://www.bilibili.com/bangumi/play/ss${_state.value.seasonId}"
        }
    } else {
        "https://www.bilibili.com/video/$videoId"
    }

    fun shareTitle(): String = _state.value.detail?.title.orEmpty()

    // ---- favourites ----------------------------------------------------------

    /**
     * Opens the folder picker, loading the user's folders on first use.
     *
     * The folder has to be chosen rather than guessed: the previous build
     * silently dropped the video into whichever folder happened to be first,
     * which is why "可自定义视频放到哪个收藏夹" was asked for.
     */
    fun openFavouritePicker() {
        _state.update { it.copy(showFavPicker = true) }
        if (cachedFolders.isNotEmpty()) {
            _state.update { it.copy(favFolders = cachedFolders) }
            return
        }
        viewModelScope.launch {
            val mid = session.midFlow.first()
            if (mid == 0L) {
                _state.update {
                    it.copy(showFavPicker = false, notice = "请先登录后再收藏")
                }
                return@launch
            }
            when (val result = repository.favFolders(mid)) {
                is Result.Ok -> {
                    // Folder id 0 is Bilibili's placeholder for "default".
                    val folders = result.value.list.filter { it.id > 0 }
                    cachedFolders = folders
                    _state.update {
                        it.copy(
                            favFolders = folders,
                            showFavPicker = folders.isNotEmpty(),
                            notice = if (folders.isEmpty()) "没有可用的收藏夹" else it.notice,
                        )
                    }
                }
                is Result.Err -> _state.update {
                    it.copy(showFavPicker = false, notice = result.message)
                }
            }
        }
    }

    fun dismissFavouritePicker() = _state.update { it.copy(showFavPicker = false) }

    /**
     * Adds the video to [folderId], or removes it when it is already in one of
     * the folders the server reported. The result is reported through the
     * delta so the counter moves with the icon.
     */
    fun toggleFavouriteIn(folderId: Long) {
        val aid = _state.value.detail?.aid ?: return
        val current = _state.value
        val removing = current.isFavorited && folderId in current.favFolderIds

        _state.update {
            it.copy(
                showFavPicker = false,
                isFavorited = !removing,
                favDelta = it.favDelta + if (removing) -1 else 1,
                favFolderIds = if (removing) {
                    it.favFolderIds - folderId
                } else {
                    (it.favFolderIds + folderId).distinct()
                },
            )
        }

        viewModelScope.launch {
            val result = if (removing) {
                repository.favorite(aid, addIds = emptyList(), delIds = listOf(folderId))
            } else {
                repository.favorite(aid, addIds = listOf(folderId), delIds = emptyList())
            }
            if (result is Result.Err) {
                _state.update {
                    it.copy(
                        isFavorited = removing,
                        favDelta = it.favDelta + if (removing) 1 else -1,
                        favFolderIds = if (removing) {
                            (it.favFolderIds + folderId).distinct()
                        } else {
                            it.favFolderIds - folderId
                        },
                        notice = result.message,
                    )
                }
            } else {
                val folder = cachedFolders.firstOrNull { it.id == folderId }
                _state.update {
                    it.copy(
                        notice = if (removing) {
                            "已取消收藏"
                        } else {
                            "已收藏到「${folder?.title ?: "收藏夹"}」"
                        },
                    )
                }
            }
        }
    }

    /**
     * 关注 the uploader, or 追番 the season.
     *
     * For a 番剧 the same control means the season: following the studio's
     * account is not what anybody wants from the play page, and the two are
     * different relations server-side. The button's label follows suit.
     */
    fun toggleFollow() {
        if (_state.value.isPgc) {
            toggleSeasonFollow()
            return
        }
        if (!_state.value.isLoggedIn) {
            _state.update { it.copy(notice = "请先登录后再关注") }
            return
        }
        val mid = _state.value.detail?.owner?.mid ?: return
        val following = !_state.value.isFollowing
        _state.update { it.copy(isFollowing = following) }
        viewModelScope.launch {
            val result = repository.modifyRelation(mid, if (following) 1 else 2)
            if (result is Result.Err) {
                _state.update { it.copy(isFollowing = !following, notice = result.message) }
            } else {
                _state.update {
                    it.copy(notice = if (following) "已关注" else "已取消关注")
                }
            }
        }
    }

    /** 追番 / 取消追番 for the season on screen. */
    private fun toggleSeasonFollow() {
        if (!_state.value.isLoggedIn) {
            _state.update { it.copy(notice = "请先登录后再追番") }
            return
        }
        val seasonId = _state.value.seasonId
        if (seasonId <= 0) return

        val following = !_state.value.seasonFollowed
        _state.update { it.copy(seasonFollowed = following) }
        viewModelScope.launch {
            val result = repository.setSeasonFollow(seasonId, following)
            if (result is Result.Err) {
                _state.update {
                    it.copy(seasonFollowed = !following, notice = result.message)
                }
            } else {
                _state.update {
                    it.copy(notice = if (following) "已追番" else "已取消追番")
                }
            }
        }
    }

    // ---- comments ----

    fun onReplyTextChanged(text: String) = _state.update { it.copy(replyText = text) }

    /**
     * Points the composer at a comment.
     *
     * `root` is the thread's top-level comment: when the target is already a
     * reply, its own `root` is reused, otherwise the target *is* the root. That
     * distinction is what keeps a reply attached to the right thread.
     */
    fun startReplyTo(reply: Reply) {
        val name = reply.member?.uname.orEmpty()
        _state.update {
            it.copy(
                replyTargetName = name.ifEmpty { "该评论" },
                replyTargetRpid = reply.rpid,
                replyTargetRoot = if (reply.root > 0) reply.root else reply.rpid,
                notice = null,
            )
        }
    }

    fun cancelReply() = _state.update {
        it.copy(replyTargetName = null, replyTargetRpid = 0, replyTargetRoot = 0)
    }

    fun sendReply() {
        val current = _state.value
        val text = current.replyText.trim()
        val oid = current.commentOid
        if (text.isEmpty() || current.isSendingReply || oid == 0L) return

        // Checked before the request so the answer is instant and lands in the
        // composer's own feedback line, rather than coming back as `-101` from
        // the server and appearing in a strip the keyboard is covering.
        if (!current.isLoggedIn) {
            _state.update {
                it.copy(
                    commentFeedback = "请先登录后再发表评论",
                    commentFeedbackIsError = true,
                )
            }
            return
        }

        _state.update {
            it.copy(isSendingReply = true, commentFeedback = null, commentFeedbackIsError = false)
        }

        viewModelScope.launch {
            val target = current.replyTargetRpid
            when (
                val result = repository.addReply(
                    oid = oid,
                    message = text,
                    root = current.replyTargetRoot,
                    parent = target,
                )
            ) {
                is Result.Ok -> _state.update {
                    val posted = result.value
                    it.copy(
                        // A reply belongs inside its thread, not at the top of the
                        // list, so it is attached to the comment it answers
                        // instead of being prepended to the whole list.
                        replies = if (target > 0) {
                            it.replies.map { existing ->
                                if (existing.rpid == current.replyTargetRoot ||
                                    existing.rpid == target
                                ) {
                                    existing.copy(
                                        replies = (existing.replies.orEmpty() + posted).distinctBy { r -> r.rpid },
                                    )
                                } else {
                                    existing
                                }
                            }
                        } else {
                            listOf(posted) + it.replies
                        },
                        replyText = "",
                        isSendingReply = false,
                        replyTargetName = null,
                        replyTargetRpid = 0,
                        replyTargetRoot = 0,
                        // Inline, right under the composer, where the user is
                        // already looking.
                        commentFeedback = if (target > 0) "回复已发送" else "评论已发送",
                        commentFeedbackIsError = false,
                    )
                }
                is Result.Err -> _state.update {
                    it.copy(
                        isSendingReply = false,
                        commentFeedback = result.message,
                        commentFeedbackIsError = true,
                    )
                }
            }
        }
    }

    /**
     * Likes or unlikes a comment.
     *
     * The previous version always sent `action=1`, so a second tap incremented
     * the count again while the server did nothing — one of the "数据不准确"
     * cases. The toggle now reads the reply's own `action` flag.
     */
    fun likeReply(rpid: Long) {
        val oid = _state.value.commentOid
        if (oid == 0L) return
        if (!_state.value.isLoggedIn) {
            _state.update {
                it.copy(commentFeedback = "请先登录后再点赞评论", commentFeedbackIsError = true)
            }
            return
        }
        val target = findReply(rpid) ?: return
        val liking = target.action != 1

        _state.update { current ->
            current.copy(replies = current.replies.map { it.withLike(rpid, liking) })
        }
        viewModelScope.launch { repository.likeReply(oid, rpid, liked = liking) }
    }

    /** Depth-first lookup: replies to replies live one level down. */
    private fun findReply(rpid: Long): Reply? {
        val top = _state.value.replies
        top.firstOrNull { it.rpid == rpid }?.let { return it }
        return top.asSequence()
            .flatMap { it.replies.orEmpty().asSequence() }
            .firstOrNull { it.rpid == rpid }
    }

    /** Applies an optimistic like/unlike to one comment, wherever it sits. */
    private fun Reply.withLike(target: Long, liking: Boolean): Reply {
        val moved = (like + if (liking) 1 else -1).coerceAtLeast(0)
        return when {
            rpid == target -> copy(like = moved, action = if (liking) 1 else 0)
            replies.isNullOrEmpty() -> this
            else -> copy(replies = replies.map { it.withLike(target, liking) })
        }
    }

    /**
     * Dislikes a comment, or withdraws the dislike.
     *
     * A dislike and a like are mutually exclusive on Bilibili, so liking picks
     * `action = 1` and clears any dislike, and vice versa.
     */
    fun dislikeReply(rpid: Long) {
        val oid = _state.value.commentOid
        if (oid == 0L) return
        if (!_state.value.isLoggedIn) {
            _state.update {
                it.copy(commentFeedback = "请先登录后再点踩", commentFeedbackIsError = true)
            }
            return
        }
        val target = findReply(rpid) ?: return
        val disliking = target.action != 2

        _state.update { current ->
            current.copy(replies = current.replies.map { it.withAction(rpid, if (disliking) 2 else 0) })
        }
        viewModelScope.launch {
            val result = repository.dislikeReply(oid, rpid, if (disliking) 2 else 0)
            if (result is Result.Err) {
                // Put the previous reaction back rather than leaving a state the
                // server never accepted.
                _state.update { current ->
                    current.copy(
                        replies = current.replies.map { it.withAction(rpid, target.action) },
                        commentFeedback = result.message,
                        commentFeedbackIsError = true,
                    )
                }
            }
        }
    }

    /** Sets a comment's reaction flag (0 none, 1 like, 2 dislike). */
    private fun Reply.withAction(target: Long, action: Int): Reply = when {
        rpid == target -> copy(action = action)
        replies.isNullOrEmpty() -> this
        else -> copy(replies = replies.map { it.withAction(target, action) })
    }

    /** mid of the uploader, for navigating to their space. */
    fun uploaderMid(): Long = _state.value.detail?.owner?.mid ?: 0L

    private companion object {
        const val MAX_COINS = 2

        /** What 重置弹幕设置 restores: every mode visible. */
        val DEFAULT_DANMAKU_TYPES = setOf(
            com.ytstyle.bili.ui.player.DanmakuMode.SCROLL,
            com.ytstyle.bili.ui.player.DanmakuMode.BOTTOM,
            com.ytstyle.bili.ui.player.DanmakuMode.TOP,
        )

        /**
         * Below this, a stored position is ignored and playback starts at 0.
         * Resuming a few seconds in is not what anybody means by "continue".
         */
        const val RESUME_MIN_MS = 5_000L

        /** How far playback must advance before another progress report. */
        const val REPORT_INTERVAL_MS = 15_000L

        /** Bilibili stores danmaku in 6-minute Protobuf segments. */
        const val SEGMENT_SECONDS = 360

        /** Ceiling so a very long video cannot queue dozens of requests. */
        const val MAX_DANMAKU_SEGMENTS = 20

        /**
         * Tier asked for when resolving a cast stream: 720P.
         *
         * Progressive MP4 stops at 1080P and plenty of archives never had it, so
         * asking for more would be answered with a silent downgrade anyway.
         */
        const val CAST_QUALITY_QN = 64

        /**
         * 彩色弹幕 unlocks at Lv2, per Bilibili's own 权益与等级 table; the
         * gradient below stays a 大会员 privilege.
         */
        const val COLOR_DANMAKU_MIN_LEVEL = 2

        /** `colorful=60001`: 专属渐变彩色, the 大会员 tier of the colour control. */
        const val DANMAKU_COLORFUL_VIP = 60001
    }
}
