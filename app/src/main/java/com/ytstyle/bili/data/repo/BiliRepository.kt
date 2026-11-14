package com.ytstyle.bili.data.repo

import com.ytstyle.bili.data.log.AppLog
import com.ytstyle.bili.data.net.ArticleSearchData
import com.ytstyle.bili.data.net.BiliApi
import com.ytstyle.bili.data.net.BiliResponse
import com.ytstyle.bili.data.net.CookieCapture
import com.ytstyle.bili.data.net.EmoteItem
import com.ytstyle.bili.data.net.FavFolderList
import com.ytstyle.bili.data.net.FavResourceList
import com.ytstyle.bili.data.net.HistoryData
import com.ytstyle.bili.data.net.HotwordItem
import com.ytstyle.bili.data.net.LiveDanmuInfo
import com.ytstyle.bili.data.net.LivePlayData
import com.ytstyle.bili.data.net.LiveRoomInfo
import com.ytstyle.bili.data.net.LiveRoomSearchData
import com.ytstyle.bili.data.net.LoginCaptcha
import com.ytstyle.bili.data.net.LoginKey
import com.ytstyle.bili.data.net.MediaSearchData
import com.ytstyle.bili.data.net.NavData
import com.ytstyle.bili.data.net.PasswordLoginResult
import com.ytstyle.bili.data.net.PgcResponse
import com.ytstyle.bili.data.net.PgcSeason
import com.ytstyle.bili.data.net.PlayerProgressData
import com.ytstyle.bili.data.net.RelationListData
import com.ytstyle.bili.data.net.RelationStat
import com.ytstyle.bili.data.net.SmsSendResult
import com.ytstyle.bili.data.net.SpaceProfile
import com.ytstyle.bili.data.net.SuggestTag
import com.ytstyle.bili.data.net.UserSearchData
import com.ytstyle.bili.data.net.PlayUrlData
import com.ytstyle.bili.data.net.DynamicActionApi
import com.ytstyle.bili.data.net.DynamicThumbBody
import com.ytstyle.bili.data.net.RecommendItem
import com.ytstyle.bili.data.net.Reply
import com.ytstyle.bili.data.net.ReplyListData
import com.ytstyle.bili.data.net.SearchResultData
import com.ytstyle.bili.data.net.SpaceVideoList
import com.ytstyle.bili.data.net.VcDynamicApi
import com.ytstyle.bili.data.net.VcMessageApi
import com.ytstyle.bili.data.net.directMessageFields
import com.ytstyle.bili.data.net.ViewDetail
import com.ytstyle.bili.data.net.WatchLaterData
import com.ytstyle.bili.data.net.WbiSigner
import com.ytstyle.bili.data.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bilibili's video-search sort orders. Values are the `order` query parameter;
 * each was confirmed to return results against the live endpoint.
 */
/** The server's page size for comment lists; also used to detect a full page. */
const val REPLY_PAGE_SIZE = 20

object SearchOrder {
    const val DEFAULT = "totalrank"
    const val MOST_PLAYED = "click"
    const val NEWEST = "pubdate"
    const val MOST_DANMAKU = "dm"
    const val MOST_FAVORITED = "stow"
}

/** Uniform result so the UI never has to inspect HTTP/JSON error shapes itself. */
sealed interface Result<out T> {
    data class Ok<T>(val value: T) : Result<T>
    data class Err(val message: String, val code: Int = -1) : Result<Nothing>
}

/**
 * Reads the payload into a local first so the null check smart-casts `T?` to
 * `T`, avoiding an unchecked cast.
 */
private fun <T> BiliResponse<T>.toResult(): Result<T> {
    val payload = data
    return if (code == 0 && payload != null) {
        Result.Ok(payload)
    } else {
        // Logged because "the server said no" and "the payload was not the shape
        // the model expected" look identical from the UI: both an empty screen.
        // The code is the only thing that tells them apart afterwards.
        AppLog.w("api", "code=$code message=${message.ifBlank { "(blank)" }} payload=${payload != null}")
        Result.Err(message.ifBlank { "请求失败 (code=$code)" }, code)
    }
}

/**
 * Same idea for the PGC endpoints, which answer with `result` instead of
 * `data`. Kept separate rather than unified behind reflection so the two
 * envelopes stay visible in the type system.
 */
private fun <T> PgcResponse<T>.toResult(): Result<T> {
    val payload = result
    return if (code == 0 && payload != null) {
        Result.Ok(payload)
    } else {
        Result.Err(message.ifBlank { "请求失败 (code=$code)" }, code)
    }
}

@Singleton
class BiliRepository @Inject constructor(
    private val api: BiliApi,
    private val session: SessionStore,
    private val wbi: WbiSigner,
    private val cookieCapture: CookieCapture,
    private val dynamicActionApi: DynamicActionApi,
    private val vcDynamicApi: VcDynamicApi,
    /** The VC host, which is where 私信 lives (and where they are sent from). */
    private val vcMessageApi: VcMessageApi,
) {

    /**
     * A device id for the 私信 endpoint.
     *
     * The docs require a UUID v4 here and nothing more. It is generated once and
     * reused for the process's lifetime: a value that changed per message would
     * present a new device on every send, and persisting it belongs in the
     * settings store rather than here.
     */
    private val messageDeviceId: String by lazy { java.util.UUID.randomUUID().toString() }

    /**
     * Fetches login state and, as a side effect, refreshes the WBI signing
     * keys carried in `wbi_img`. Every signed endpoint depends on this having
     * run at least once per key rotation.
     */
    suspend fun nav(): Result<NavData> = safe {
        val response = api.nav()
        response.data?.let { data ->
            wbi.updateKeys(data.wbiImg?.imgUrl.orEmpty(), data.wbiImg?.subUrl.orEmpty())
        }
        response.toResult()
    }

    /**
     * Fetches and persists the device fingerprint cookies if absent.
     *
     * Called once at startup. Failure is non-fatal: the app still works without
     * buvid, it is just more likely to be rate-limited.
     */
    suspend fun ensureBuvid(): Result<Unit> = safe {
        if (session.currentBuvid().isNotEmpty()) return@safe Result.Ok(Unit)
        when (val result = api.fingerSpi().toResult()) {
            is Result.Ok -> {
                session.saveBuvid(result.value.b3, result.value.b4)
                Result.Ok(Unit)
            }
            is Result.Err -> result
        }
    }

    /** Signs [params] when possible, and transparently fetches keys first. */
    private suspend fun signed(params: Map<String, String>): Map<String, String> {
        if (wbi.isStale()) {
            runCatching { api.nav() }.getOrNull()?.data?.wbiImg?.let {
                wbi.updateKeys(it.imgUrl, it.subUrl)
            }
        }
        return wbi.sign(params)
    }

    suspend fun recommend(pageSize: Int = 20, freshIdx: Int = 1): Result<List<RecommendItem>> =
        safe {
            when (val r = api.recommendFeed(pageSize, freshIdx).toResult()) {
                is Result.Ok -> Result.Ok(r.value.item.filter { it.bvid.isNotEmpty() })
                is Result.Err -> r
            }
        }

    suspend fun viewDetail(bvid: String): Result<ViewDetail> =
        safe { api.viewDetail(bvid = bvid).toResult() }

    /** Favourite and history rows carry an aid; the player routes on bvid. */
    suspend fun viewDetailByAid(aid: Long): Result<ViewDetail> =
        safe { api.viewDetail(aid = aid).toResult() }

    /**
     * Resolves DASH streams. Falls back to the progressive `durl` list when the
     * DASH payload is empty, which happens for some older/limited archives.
     *
     * @param progressive ask for a single-file container instead of DASH. DASH
     *   separates video from audio and carries **no** `durl` at all, so anything
     *   that can only play one URL (DLNA casting) has nothing to hand over. With
     *   `fnval=1` the same endpoint answers with one MP4 `durl`. Measured on the
     *   live endpoint: `fnval=4048` → `durl=0, dash.video=6`; `fnval=1` →
     *   `durl=1, format=mp4720`.
     */
    suspend fun playUrl(
        bvid: String,
        cid: Long,
        quality: Int = 80,
        progressive: Boolean = false,
    ): Result<PlayUrlData> =
        safe {
            val params = signed(
                mapOf(
                    "bvid" to bvid,
                    "cid" to cid.toString(),
                    "qn" to quality.toString(),
                    "fnval" to if (progressive) "1" else "4048",
                    "fourk" to if (progressive) "0" else "1",
                ),
            )
            api.playUrl(params).toResult()
        }

    /**
     * One page of top-level comments.
     *
     * Paged by **cursor**, not by `pn`: `x/v2/reply/wbi/main` accepts `pn` but
     * ignores it — measured on a 1686-comment video, `pn=1/2/3` all returned the
     * same first rpid. [offset] is the `cursor.pagination_reply.next_offset` of
     * the previous page, empty for the first. `mode=3` is heat order and `ps=20`
     * is the server's own ceiling.
     */
    suspend fun replies(
        oid: Long,
        page: Int = 1,
        mode: Int = 3,
        type: Int = 1,
        offset: String = "",
    ): Result<ReplyListData> =
        safe {
            val params = signed(
                mapOf(
                    "oid" to oid.toString(),
                    "type" to type.toString(),
                    // 3 = 最热, 2 = 最新.
                    "mode" to mode.toString(),
                    "ps" to REPLY_PAGE_SIZE.toString(),
                    "pn" to page.toString(),
                    "plat" to "1",
                    "web_location" to "1315871",
                    "pagination_str" to """{"offset":"$offset"}""",
                ),
            )
            api.replies(params).toResult()
        }

    /**
     * One page of comments under a 动态.
     *
     * Same endpoint as video comments, with `type = 17` (动态) and the dynamic id
     * as the oid. The WBI signature is already applied inside [replies], which is
     * required by `x/v2/reply/wbi/main` regardless of the object type.
     */
    suspend fun dynamicReplies(dynId: String, page: Int = 1): Result<ReplyListData> {
        val oid = dynId.toLongOrNull() ?: return Result.Err("动态 id 无法解析")
        return replies(oid = oid, page = page, mode = 3, type = 17)
    }

    /**
     * Every reply inside one comment thread.
     *
     * @param rootRpid the thread's top-level `rpid`. Note this is the *thread*
     *   root, not the comment being answered — passing a nested reply's own id
     *   returns an empty list.
     */
    suspend fun replyThread(oid: Long, rootRpid: Long, page: Int = 1): Result<ReplyListData> =
        safe {
            val params = signed(
                mapOf(
                    "oid" to oid.toString(),
                    "type" to "1",
                    "root" to rootRpid.toString(),
                    "ps" to "20",
                    "pn" to page.toString(),
                ),
            )
            api.replyThread(params).toResult()
        }

    // ---- PGC (番剧 / 影视) ----

    /**
     * Resolves a season, either from its own id or from a single episode's id.
     *
     * The endpoint tolerates a signature but does not need one, so the plain
     * parameters are sent.
     */
    suspend fun pgcSeason(seasonId: Long = 0, epId: Long = 0): Result<PgcSeason> = safe {
        val params = buildMap {
            if (seasonId > 0) put("season_id", seasonId.toString())
            if (epId > 0) put("ep_id", epId.toString())
        }
        if (params.isEmpty()) return@safe Result.Err("缺少番剧参数")
        api.pgcSeason(params).toResult()
    }

    /**
     * Bangumi streams, keyed by episode.
     *
     * Verified against the live endpoint: an anonymous caller asking for 4K is
     * answered with `quality = 32` (480P) and a six-track DASH manifest, i.e.
     * Bilibili silently degrades rather than failing, so the caller must read
     * back `quality` instead of assuming the tier it asked for.
     */
    suspend fun pgcPlayUrl(
        epId: Long,
        cid: Long,
        quality: Int = 80,
        progressive: Boolean = false,
    ): Result<PlayUrlData> =
        safe {
            api.pgcPlayUrl(
                mapOf(
                    "ep_id" to epId.toString(),
                    "cid" to cid.toString(),
                    "qn" to quality.toString(),
                    "fnval" to if (progressive) "1" else "4048",
                    "fourk" to if (progressive) "0" else "1",
                ),
            ).toResult()
        }

    /**
     * Video search. [order] and [duration] are Bilibili's own sort and length
     * filters; both were verified against the live endpoint.
     *
     * @param order one of [SearchOrder] values.
     * @param duration 0 = any length, 1 = <10min, 2 = 10-30min, 3 = 30-60min,
     *   4 = >60min.
     */
    suspend fun search(
        keyword: String,
        page: Int = 1,
        order: String = SearchOrder.DEFAULT,
        duration: Int = 0,
    ): Result<SearchResultData> =
        safe {
            val params = signed(
                buildMap {
                    put("keyword", keyword)
                    put("search_type", "video")
                    put("page", page.toString())
                    put("order", order)
                    if (duration > 0) put("duration", duration.toString())
                },
            )
            api.search(params).toResult()
        }

    /** Search restricted to a partition. [searchType] is Bilibili's `search_type`. */
    suspend fun searchPartition(
        keyword: String,
        searchType: String,
        page: Int = 1,
        order: String = SearchOrder.DEFAULT,
        duration: Int = 0,
    ): Result<SearchResultData> =
        safe {
            api.search(
                signed(
                    buildMap {
                        put("keyword", keyword)
                        put("search_type", searchType)
                        put("page", page.toString())
                        put("order", order)
                        if (duration > 0) put("duration", duration.toString())
                    },
                ),
            ).toResult()
        }

    suspend fun searchUsers(keyword: String, page: Int = 1): Result<UserSearchData> =
        safe {
            api.searchUsers(
                signed(
                    mapOf(
                        "keyword" to keyword,
                        "search_type" to "bili_user",
                        "page" to page.toString(),
                    ),
                ),
            ).toResult()
        }

    suspend fun searchArticles(keyword: String, page: Int = 1): Result<ArticleSearchData> =
        safe {
            api.searchArticles(
                signed(
                    mapOf(
                        "keyword" to keyword,
                        "search_type" to "article",
                        "page" to page.toString(),
                    ),
                ),
            ).toResult()
        }

    /**
     * PGC (番剧 / 影视) search.
     *
     * @param searchType `media_bangumi` for 番剧·国创, `media_ft` for 影视.
     */
    suspend fun searchMedia(
        keyword: String,
        searchType: String,
        page: Int = 1,
    ): Result<MediaSearchData> =
        safe {
            api.searchMedia(
                signed(
                    mapOf(
                        "keyword" to keyword,
                        "search_type" to searchType,
                        "page" to page.toString(),
                    ),
                ),
            ).toResult()
        }

    suspend fun searchLiveRooms(keyword: String, page: Int = 1): Result<LiveRoomSearchData> =
        safe {
            api.searchLiveRooms(
                signed(
                    mapOf(
                        "keyword" to keyword,
                        "search_type" to "live_room",
                        "page" to page.toString(),
                    ),
                ),
            ).toResult()
        }

    // ---- user space ----

    suspend fun spaceInfo(mid: Long): Result<SpaceProfile> =
        safe { api.spaceInfo(signed(mapOf("mid" to mid.toString()))).toResult() }

    suspend fun relationStat(mid: Long): Result<RelationStat> =
        safe { api.relationStat(mid).toResult() }

    // ---- live ----

    suspend fun liveRoomInfo(roomId: Long): Result<LiveRoomInfo> =
        safe { api.liveRoomInfo(roomId).toResult() }

    /**
     * The live danmaku credentials (token + servers) for one room.
     *
     * The legacy endpoint is tried first, because the modern
     * `xlive/web-room/v1/index/getDanmuInfo` is now risk-controlled into
     * `-352 风控校验失败` — confirmed on a logged-in phone and from a development
     * machine alike, across every header shape tried. `room/v1/Danmu/getConf`
     * still answers, and its token was verified to authenticate over WSS, so it
     * is the primary path; the modern call stays as a fallback for the day the
     * older endpoint is retired in turn.
     */
    suspend fun liveDanmuCredentials(roomId: Long): Result<LiveDanmuCredentials> {
        when (val legacy = safe { api.danmuConf(roomId).toResult() }) {
            is Result.Ok -> return Result.Ok(
                LiveDanmuCredentials(
                    token = legacy.value.token,
                    hostList = legacy.value.hosts,
                    source = DANMU_SOURCE_LEGACY,
                ),
            )
            is Result.Err -> Unit
        }
        when (val modern = safe { api.liveDanmuInfo(roomId).toResult() }) {
            is Result.Ok -> return Result.Ok(
                LiveDanmuCredentials(
                    token = modern.value.token,
                    hostList = modern.value.hostList,
                    source = DANMU_SOURCE_MODERN,
                ),
            )
            // Both paths failed. The legacy code is the actionable one: that is
            // the endpoint that should have answered.
            is Result.Err -> return modern
        }
    }

    /**
     * Resolves a playable live URL.
     *
     * `protocol=0,1` asks for both FLV and HLS; HLS is preferred by
     * [com.ytstyle.bili.ui.live.pickLiveStream] because Media3 handles live HLS
     * far more reliably than an unbounded FLV stream.
     */
    suspend fun livePlayInfo(roomId: Long): Result<LivePlayData> =
        safe {
            api.livePlayInfo(
                mapOf(
                    "room_id" to roomId.toString(),
                    "protocol" to "0,1",
                    "format" to "0,1,2",
                    "codec" to "0,1",
                    "qn" to "10000",
                    "platform" to "web",
                    "ptype" to "8",
                    "dolby" to "5",
                ),
            ).toResult()
        }

    // ---- watch later ----

    suspend fun watchLater(): Result<WatchLaterData> = safe { api.watchLater().toResult() }

    suspend fun addWatchLater(bvid: String): Result<Unit> = withCsrf { csrf ->
        api.addWatchLater(bvid = bvid, csrf = csrf).toAckResult()
    }

    suspend fun removeWatchLater(aid: Long): Result<Unit> = withCsrf { csrf ->
        api.removeWatchLater(aid = aid, csrf = csrf).toAckResult()
    }

    // ---- emotes ----

    /** Empty `packages` when signed out; treat that as "no extra emotes". */
    suspend fun emotePanel(): Result<List<EmoteItem>> = safe {
        when (val result = api.emotePanel().toResult()) {
            is Result.Ok -> Result.Ok(result.value.packages.flatMap { it.emotes })
            is Result.Err -> result
        }
    }

    // ---- SMS code login ----

    /**
     * Blank captcha fields are normalised to `null` here rather than at every
     * call site: Bilibili answers `-400 参数错误` when the form carries an empty
     * `challenge`/`validate`/`seccode`, and Retrofit omits null fields.
     */
    private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

    suspend fun smsSend(
        tel: String,
        cid: Int,
        token: String,
        challenge: String,
        validate: String,
        seccode: String,
    ): Result<SmsSendResult> = safe {
        api.smsSend(
            tel = tel,
            cid = cid,
            token = token.orNullIfBlank(),
            challenge = challenge.orNullIfBlank(),
            validate = validate.orNullIfBlank(),
            seccode = seccode.orNullIfBlank(),
        ).toResult()
    }

    suspend fun smsLogin(
        tel: String,
        cid: Int,
        code: String,
        captchaKey: String,
        token: String,
        challenge: String,
        validate: String,
        seccode: String,
    ): Result<PasswordLoginResult> = safe {
        api.smsLogin(
            tel = tel,
            cid = cid,
            code = code,
            captchaKey = captchaKey.orNullIfBlank(),
            token = token.orNullIfBlank(),
            challenge = challenge.orNullIfBlank(),
            validate = validate.orNullIfBlank(),
            seccode = seccode.orNullIfBlank(),
        ).toResult()
    }

    /** Trending search terms shown before the user types anything. */
    suspend fun hotSearchWords(): Result<List<HotwordItem>> = safe {
        val response = api.hotSearchWords()
        if (response.code == 0) Result.Ok(response.list) else Result.Err("热搜加载失败", response.code)
    }

    /** Typeahead suggestions for a partial query. */
    suspend fun searchSuggest(term: String): Result<List<SuggestTag>> = safe {
        val response = api.searchSuggest(term = term)
        if (response.code == 0) {
            Result.Ok(response.result?.tags.orEmpty())
        } else {
            Result.Err("联想词加载失败", response.code)
        }
    }

    /**
     * Danmaku are stored as 6-minute Protobuf segments. This returns the raw
     * body because the parser lives in the danmaku package and the repository
     * should not depend on it.
     */
    suspend fun danmakuSegment(cid: Long, segmentIndex: Int): okhttp3.ResponseBody =
        withContext(Dispatchers.IO) {
            api.danmakuSeg(cid = cid, segmentIndex = segmentIndex)
        }

    suspend fun history(): Result<HistoryData> = safe { api.history().toResult() }

    suspend fun spaceVideos(mid: Long, page: Int = 1): Result<SpaceVideoList> =
        safe {
            val params = signed(
                mapOf(
                    "mid" to mid.toString(),
                    "pn" to page.toString(),
                    "ps" to "30",
                ),
            )
            api.spaceVideosSigned(params).toResult()
        }

    suspend fun favFolders(mid: Long): Result<FavFolderList> =
        safe { api.favFolders(mid = mid).toResult() }

    suspend fun favResources(mediaId: Long, page: Int = 1): Result<FavResourceList> =
        safe { api.favResources(mediaId = mediaId, page = page).toResult() }

    // ---- authenticated writes ----
    //
    // Every write goes through `toAckResult()` rather than `toResult()`.
    //
    // This matters: `toResult()` treats a null `data` as failure, but Bilibili's
    // write endpoints routinely answer `{"code":0,"message":"0","ttl":1}` with
    // no `data` key at all. A like that actually succeeded was therefore
    // reported as "请求失败 (code=0)", the optimistic UI change was reverted,
    // and the button appeared to do nothing — the "点了点赞但是 ui 没有变化"
    // bug. For a write, `code == 0` *is* the success signal.

    /** `code == 0` means the write was accepted, regardless of the payload. */
    private fun BiliResponse<*>.toAckResult(): Result<Unit> =
        if (code == 0) {
            Result.Ok(Unit)
        } else {
            Result.Err(message.ifBlank { "请求失败 (code=$code)" }, code)
        }

    suspend fun like(bvid: String, like: Boolean): Result<Unit> = withCsrf { csrf ->
        api.like(bvid = bvid, like = if (like) 1 else 2, csrf = csrf).toAckResult()
    }

    suspend fun coin(bvid: String, count: Int, alsoLike: Boolean): Result<Unit> =
        withCsrf { csrf ->
            api.coin(
                bvid = bvid,
                multiply = count,
                selectLike = if (alsoLike) 1 else 0,
                csrf = csrf,
            ).toAckResult()
        }

    /**
     * 追番 / 取消追番.
     *
     * The season, not the uploader: following a 番剧 and following its UP are
     * two different relations, and only this one is what 追番 means.
     */
    suspend fun setSeasonFollow(seasonId: Long, follow: Boolean): Result<Unit> =
        withCsrf { csrf ->
            val response = if (follow) {
                api.followSeason(seasonId = seasonId, csrf = csrf)
            } else {
                api.unfollowSeason(seasonId = seasonId, csrf = csrf)
            }
            response.toAckResult()
        }

    suspend fun favorite(aid: Long, addIds: List<Long>, delIds: List<Long>): Result<Unit> =
        withCsrf { csrf ->
            api.favorite(
                rid = aid,
                type = 2,
                addMediaIds = addIds.joinToString(","),
                delMediaIds = delIds.joinToString(","),
                csrf = csrf,
            ).toAckResult()
        }

    /**
     * Posts a comment, or a reply when [root]/[parent] are given.
     *
     * For a reply to a top-level comment both are that comment's `rpid`. For a
     * reply inside a thread, `root` is the thread's top comment and `parent` is
     * the comment being answered — sending only `parent` nests the reply under
     * the wrong thread.
     */
    suspend fun addReply(
        oid: Long,
        message: String,
        root: Long = 0,
        parent: Long = 0,
        /**
         * The `oid`'s kind: `1` 视频 (every existing caller), `17` 动态. Posting
         * a dynamic comment is the same endpoint with the dynamic's id as the
         * `oid` and this set to 17.
         */
        type: Int = 1,
    ): Result<Reply> = withCsrf { csrf ->
        api.addReply(
            oid = oid,
            type = type,
            message = message,
            root = root.takeIf { it > 0 },
            parent = parent.takeIf { it > 0 },
            csrf = csrf,
        ).toResult()
    }

    suspend fun likeReply(oid: Long, rpid: Long, liked: Boolean): Result<Unit> =
        withCsrf { csrf ->
            api.likeReply(
                oid = oid,
                type = 1,
                rpid = rpid,
                action = if (liked) 1 else 0,
                csrf = csrf,
            ).toAckResult()
        }

    /**
     * Dislikes a comment, or clears an existing reaction.
     *
     * @param action 2 = 点踩, 0 = cancel. `x/v2/reply/like` cannot express a
     *   dislike, hence the separate endpoint.
     */
    suspend fun dislikeReply(oid: Long, rpid: Long, action: Int): Result<Unit> =
        withCsrf { csrf ->
            api.replyAction(oid = oid, type = 1, rpid = rpid, action = action, csrf = csrf)
                .toAckResult()
        }

    /** act: 1 = follow, 2 = unfollow. */
    suspend fun modifyRelation(mid: Long, act: Int): Result<Unit> = withCsrf { csrf ->
        api.modifyRelation(fid = mid, act = act, reSrc = 11, csrf = csrf).toAckResult()
    }

    /**
     * One page of 粉丝.
     *
     * A privacy refusal arrives as `Result.Err` with `code = 22118`, which the
     * screen turns into an explanation rather than an error.
     */
    suspend fun fans(mid: Long, page: Int = 1): Result<RelationListData> = safe {
        api.fans(mid = mid, page = page).toResult()
    }

    /**
     * One page of 关注.
     *
     * A private list is `code = 22115`, or `code = 0` with `list = null`; both
     * are handled by the screen, and neither is an error.
     */
    suspend fun followings(mid: Long, page: Int = 1): Result<RelationListData> = safe {
        api.followings(mid = mid, page = page).toResult()
    }

    /**
     * Posts a danmaku at [progressMs] into the video.
     *
     * @param cid the video's cid, which this endpoint calls `oid`.
     */
    suspend fun sendDanmaku(
        bvid: String,
        cid: Long,
        message: String,
        progressMs: Long,
        mode: Int = 1,
        color: Long = 16777215,
        fontSize: Int = 25,
        /** 60001 for the 大会员 gradient; null for an ordinary solid colour. */
        colorful: Int? = null,
    ): Result<Unit> = withCsrf { csrf ->
        api.sendDanmaku(
            oid = cid,
            message = message,
            bvid = bvid,
            progressMs = progressMs,
            mode = mode,
            color = color,
            fontSize = fontSize,
            colorful = colorful,
            csrf = csrf,
        ).toAckResult()
    }

    /**
     * Sends a text 私信 to [talkerId].
     *
     * The only message type offered: `send_msg` also accepts an image and a card
     * (types 2 and 5), and neither has a UI here. The body is a **JSON document
     * encoded into a form field**, which is what the endpoint means by
     * `msg[content]`; an empty one is answered with `请求错误`, so it is refused
     * locally instead of round-tripping.
     *
     * Needs WBI signing, hence [signed] (which also refreshes stale keys) plus
     * [withCsrf]. A successful send marks the session read as a side effect.
     */
    suspend fun sendDirectMessage(talkerId: Long, text: String): Result<Unit> = withCsrf { csrf ->
        if (talkerId <= 0) return@withCsrf Result.Err("会话信息缺失")

        val senderUid = session.midFlow.first()
        if (senderUid <= 0) return@withCsrf Result.Err("请先登录", -101)

        val body = text.trim()
        if (body.isEmpty()) return@withCsrf Result.Err("消息不能为空")

        val deviceId = messageDeviceId
        // Built by a pure function so its shape is covered by a unit test; see
        // `DirectMessagePayload.kt`.
        val fields = directMessageFields(
            senderUid = senderUid,
            receiverId = talkerId,
            text = body,
            deviceId = deviceId,
            timestampSeconds = System.currentTimeMillis() / 1000,
            csrf = csrf,
        )
        val query = signed(
            mapOf(
                "w_sender_uid" to senderUid.toString(),
                "w_receiver_id" to talkerId.toString(),
                "w_dev_id" to deviceId,
            ),
        )

        // A `MsgEnvelope`, not a `BiliResponse`, so `toAckResult()` does not
        // apply; the code check is the same one it performs.
        val envelope = vcMessageApi.sendMessage(signed = query, fields = fields)
        if (envelope.code == 0) {
            Result.Ok(Unit)
        } else {
            AppLog.w("msg", "send_msg code=${envelope.code}")
            Result.Err(envelope.message.ifBlank { "发送失败 (code=${envelope.code})" }, envelope.code)
        }
    }

    /** Related videos for the rail under the player. */
    suspend fun related(bvid: String): Result<List<RecommendItem>> = safe {
        api.related(bvid).toResult()
    }

    /**
     * Cloud watch progress for one part, so playback can continue from where
     * another device stopped.
     *
     * A failure here is never fatal and never surfaces: an unwatched video has
     * no progress, and a signed-out caller is answered with zeros. Both look the
     * same to the caller, which simply starts at 0.
     */
    suspend fun playerProgress(bvid: String, cid: Long): Result<PlayerProgressData> = safe {
        api.playerProgress(bvid, cid).toResult()
    }

    /**
     * Reports progress to Bilibili, which is what makes resume work on *other*
     * devices — the position is stored server-side, not locally.
     *
     * @param positionMs position in milliseconds; converted to the seconds the
     *   endpoint expects.
     */
    suspend fun reportProgress(aid: Long, cid: Long, positionMs: Long): Result<Unit> =
        withCsrf { csrf ->
            api.reportProgress(
                aid = aid,
                cid = cid,
                progressSeconds = (positionMs / 1000).coerceAtLeast(0L),
                csrf = csrf,
            ).toAckResult()
        }

    suspend fun navStat(): Result<com.ytstyle.bili.data.net.NavStat> =
        safe { api.navStat().toResult() }

    suspend fun isFollowing(mid: Long): Result<Boolean> = safe {
        when (val r = api.relation(mid).toResult()) {
            is Result.Ok -> Result.Ok(r.value.attribute == 2 || r.value.attribute == 6)
            is Result.Err -> r
        }
    }

    suspend fun hasLiked(bvid: String): Result<Boolean> = safe {
        when (val r = api.archiveRelation(bvid).toResult()) {
            is Result.Ok -> Result.Ok(r.value.like)
            is Result.Err -> r
        }
    }

    /**
     * Coins already given to this archive, 0–2.
     *
     * Needed because the coin endpoint is *additive*: without reading this the
     * player cannot know that two coins are already in, and the next tap is
     * rejected by the server with the count left stale on screen.
     */
    suspend fun coinsGiven(bvid: String): Result<Int> = safe {
        when (val r = api.archiveCoins(bvid).toResult()) {
            is Result.Ok -> Result.Ok(r.value.multiply.coerceIn(0, 2))
            is Result.Err -> r
        }
    }

    /**
     * Concurrent viewers for the "N人正在看" line in the landscape player.
     *
     * Failure is expected and harmless: the endpoint is not reliably available,
     * so callers keep the line hidden instead of printing a misleading 0.
     */
    suspend fun onlineCount(bvid: String, cid: Long): Result<Int> = safe {
        when (val r = api.onlineTotal(bvid, cid).toResult()) {
            is Result.Ok -> Result.Ok(r.value.online)
            is Result.Err -> r
        }
    }

    /** Whether this archive sits in any of the signed-in user's folders. */
    suspend fun isFavourited(aid: Long): Result<Boolean> = safe {
        when (val r = api.favoured(aid).toResult()) {
            is Result.Ok -> Result.Ok(r.value.favoured)
            is Result.Err -> r
        }
    }

    // ---- 动态 (write side) ----

    /**
     * Likes or unlikes one dynamic.
     *
     * `up` is sent explicitly — 1 to like, 2 to unlike — rather than the
     * server's toggle mode (`0`), because a toggle leaves the client unable to
     * roll back an optimistic update when the two states disagree.
     */
    suspend fun thumbDynamic(dynId: String, like: Boolean): Result<Unit> = withCsrf { csrf ->
        if (dynId.isBlank()) {
            Result.Err("动态 id 为空")
        } else {
            dynamicActionApi.thumb(
                csrf = csrf,
                body = DynamicThumbBody(dynIdStr = dynId, up = if (like) 1 else 2),
            ).toAckResult()
        }
    }

    /**
     * 转发 one dynamic, optionally with a comment of the user's own.
     *
     * The newer `x/dynamic/feed/create/dyn` cannot forward — its `dyn_req` has
     * no field for the original — so this uses the legacy endpoint with
     * `type = 1`. The response is an ack: it carries no usable payload.
     */
    suspend fun repostDynamic(dynId: String, content: String): Result<Unit> = withCsrf { csrf ->
        if (dynId.isBlank()) {
            Result.Err("动态 id 为空")
        } else {
            vcDynamicApi.repost(
                dynamicId = dynId,
                type = REPOST_TYPE_FORWARD,
                rid = 0L,
                content = content,
                upChooseComment = 0,
                upCloseComment = 0,
                csrfToken = csrf,
                csrf = csrf,
            ).toAckResult()
        }
    }

    /**
     * Comments on a dynamic: the comment endpoint with `type = 17`, which is
     * the kind Bilibili assigns to 动态 `oid`s.
     */
    suspend fun commentDynamic(dynId: String, message: String): Result<Reply> {
        val oid = dynId.toLongOrNull() ?: return Result.Err("动态 id 无法解析")
        return addReply(oid = oid, message = message, type = DYNAMIC_OID_TYPE)
    }

    // ---- login ----

    suspend fun qrGenerate(): Result<Pair<String, String>> = safe {
        when (val r = api.qrGenerate().toResult()) {
            is Result.Ok -> Result.Ok(r.value.url to r.value.qrcodeKey)
            is Result.Err -> r
        }
    }

    suspend fun qrPoll(key: String) = safe { api.qrPoll(key).toResult() }

    // ---- password login ----

    /** Fetches the RSA public key and the salt that prefixes the password. */
    suspend fun loginKey(): Result<LoginKey> = safe { api.loginKey().toResult() }

    /** Fetches the Geetest `gt`/`challenge` pair the WebView must solve. */
    suspend fun loginCaptcha(): Result<LoginCaptcha> = safe { api.loginCaptcha().toResult() }

    /**
     * Submits the credential form. [encryptedPassword] must already be
     * RSA-encrypted by [com.ytstyle.bili.data.auth.PasswordCrypto].
     *
     * The session cookies are set on the response, so the caller must drain
     * them via [captureCookiesFromLastResponse] on success.
     */
    suspend fun passwordLogin(
        username: String,
        encryptedPassword: String,
        token: String,
        challenge: String,
        validate: String,
        seccode: String,
    ): Result<PasswordLoginResult> = safe {
        api.passwordLogin(
            username = username,
            password = encryptedPassword,
            token = token.orNullIfBlank(),
            challenge = challenge.orNullIfBlank(),
            validate = validate.orNullIfBlank(),
            seccode = seccode.orNullIfBlank(),
        ).toResult()
    }

    /**
     * Drains the cookies scraped from the most recent response by
     * [com.ytstyle.bili.data.net.CookieCapture]. Call this only after the QR
     * poll reports success, since that is the response carrying the session.
     */
    fun captureCookiesFromLastResponse(): String? = cookieCapture.drain()

    private suspend fun <T> withCsrf(
        block: suspend (String) -> Result<T>,
    ): Result<T> = safe {
        val csrf = session.currentCsrf()
        if (csrf.isEmpty()) Result.Err("请先登录", -101)
        else block(csrf)
    }

    /** Runs [block] off the main thread and converts exceptions into [Result.Err]. */
    private suspend fun <T> safe(block: suspend () -> Result<T>): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                // The catch-all: a parse failure, a timeout, a TLS problem. The
                // stack trace goes to the log because the message alone ("Expected
                // beginning of the object…") does not say which endpoint broke.
                AppLog.e("repo", e.message ?: e.javaClass.simpleName, e)
                Result.Err(e.message ?: "网络异常，请稍后重试")
            }
        }
}

/** `dynamic_svr/create`'s `type` for a 转发 (a `4` publishes an original post). */
private const val REPOST_TYPE_FORWARD = 1

/** The comment endpoint's `oid` kind for 动态. */
private const val DYNAMIC_OID_TYPE = 17

/**
 * 弹幕 credentials in the shape the player needs, whichever endpoint answered.
 *
 * The two upstream endpoints disagree on field names (`host_server_list` vs
 * `host_list`, plus a region-less `host` fallback), so they are normalised here
 * rather than in the view model.
 */
data class LiveDanmuCredentials(
    val token: String,
    val hostList: List<com.ytstyle.bili.data.net.LiveDanmuHost>,
    /** Which endpoint produced this, for the log line. */
    val source: String,
)

/** `room/v1/Danmu/getConf` answered — the working path. */
private const val DANMU_SOURCE_LEGACY = "getConf"

/** `xlive/web-room/v1/index/getDanmuInfo` answered, i.e. risk control loosened. */
private const val DANMU_SOURCE_MODERN = "getDanmuInfo"
