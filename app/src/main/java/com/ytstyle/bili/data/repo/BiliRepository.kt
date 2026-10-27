package com.ytstyle.bili.data.repo

import com.ytstyle.bili.data.net.ArticleSearchData
import com.ytstyle.bili.data.net.BiliApi
import com.ytstyle.bili.data.net.BiliResponse
import com.ytstyle.bili.data.net.CookieCapture
import com.ytstyle.bili.data.net.EmoteItem
import com.ytstyle.bili.data.net.FavFolderList
import com.ytstyle.bili.data.net.FavResourceList
import com.ytstyle.bili.data.net.HistoryData
import com.ytstyle.bili.data.net.HotwordItem
import com.ytstyle.bili.data.net.LivePlayData
import com.ytstyle.bili.data.net.LiveRoomInfo
import com.ytstyle.bili.data.net.LiveRoomSearchData
import com.ytstyle.bili.data.net.LoginCaptcha
import com.ytstyle.bili.data.net.LoginKey
import com.ytstyle.bili.data.net.MediaSearchData
import com.ytstyle.bili.data.net.NavData
import com.ytstyle.bili.data.net.PasswordLoginResult
import com.ytstyle.bili.data.net.RelationStat
import com.ytstyle.bili.data.net.SmsSendResult
import com.ytstyle.bili.data.net.SpaceProfile
import com.ytstyle.bili.data.net.SuggestTag
import com.ytstyle.bili.data.net.UserSearchData
import com.ytstyle.bili.data.net.PlayUrlData
import com.ytstyle.bili.data.net.RecommendItem
import com.ytstyle.bili.data.net.Reply
import com.ytstyle.bili.data.net.ReplyListData
import com.ytstyle.bili.data.net.SearchResultData
import com.ytstyle.bili.data.net.SpaceVideoList
import com.ytstyle.bili.data.net.ViewDetail
import com.ytstyle.bili.data.net.WatchLaterData
import com.ytstyle.bili.data.net.WbiSigner
import com.ytstyle.bili.data.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bilibili's video-search sort orders. Values are the `order` query parameter;
 * each was confirmed to return results against the live endpoint.
 */
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
        Result.Err(message.ifBlank { "请求失败 (code=$code)" }, code)
    }
}

@Singleton
class BiliRepository @Inject constructor(
    private val api: BiliApi,
    private val session: SessionStore,
    private val wbi: WbiSigner,
    private val cookieCapture: CookieCapture,
) {

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
     */
    suspend fun playUrl(bvid: String, cid: Long, quality: Int = 80): Result<PlayUrlData> =
        safe {
            val params = signed(
                mapOf(
                    "bvid" to bvid,
                    "cid" to cid.toString(),
                    "qn" to quality.toString(),
                    "fnval" to "4048",
                    "fourk" to "1",
                ),
            )
            api.playUrl(params).toResult()
        }

    suspend fun replies(oid: Long, page: Int = 1): Result<ReplyListData> =
        safe {
            val params = signed(
                mapOf(
                    "oid" to oid.toString(),
                    "type" to "1",
                    "mode" to "3",
                    "ps" to "20",
                    "pn" to page.toString(),
                ),
            )
            api.replies(params).toResult()
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
        api.addWatchLater(bvid = bvid, csrf = csrf).toResult()
    }

    suspend fun removeWatchLater(aid: Long): Result<Unit> = withCsrf { csrf ->
        api.removeWatchLater(aid = aid, csrf = csrf).toResult()
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
            token = token,
            challenge = challenge,
            validate = validate,
            seccode = seccode,
        ).toResult()
    }

    suspend fun smsLogin(
        tel: String,
        cid: Int,
        code: String,
        token: String,
        challenge: String,
        validate: String,
        seccode: String,
    ): Result<PasswordLoginResult> = safe {
        api.smsLogin(
            tel = tel,
            cid = cid,
            code = code,
            token = token,
            challenge = challenge,
            validate = validate,
            seccode = seccode,
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

    suspend fun like(bvid: String, like: Boolean): Result<Unit> = withCsrf { csrf ->
        api.like(bvid = bvid, like = if (like) 1 else 2, csrf = csrf).toResult()
    }

    suspend fun coin(bvid: String, count: Int, alsoLike: Boolean): Result<Unit> =
        withCsrf { csrf ->
            api.coin(
                bvid = bvid,
                multiply = count,
                selectLike = if (alsoLike) 1 else 0,
                csrf = csrf,
            ).toResult()
        }

    suspend fun favorite(aid: Long, addIds: List<Long>, delIds: List<Long>): Result<Unit> =
        withCsrf { csrf ->
            api.favorite(
                rid = aid,
                type = 2,
                addMediaIds = addIds.joinToString(","),
                delMediaIds = delIds.joinToString(","),
                csrf = csrf,
            ).toResult()
        }

    suspend fun addReply(oid: Long, message: String): Result<Reply> = withCsrf { csrf ->
        api.addReply(oid = oid, type = 1, message = message, csrf = csrf).toResult()
    }

    suspend fun likeReply(oid: Long, rpid: Long, liked: Boolean): Result<Unit> =
        withCsrf { csrf ->
            api.likeReply(
                oid = oid,
                type = 1,
                rpid = rpid,
                action = if (liked) 1 else 0,
                csrf = csrf,
            ).toResult()
        }

    /** act: 1 = follow, 2 = unfollow. */
    suspend fun modifyRelation(mid: Long, act: Int): Result<Unit> = withCsrf { csrf ->
        api.modifyRelation(fid = mid, act = act, reSrc = 11, csrf = csrf).toResult()
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
            token = token,
            challenge = challenge,
            validate = validate,
            seccode = seccode,
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
                Result.Err(e.message ?: "网络异常，请稍后重试")
            }
        }
}
