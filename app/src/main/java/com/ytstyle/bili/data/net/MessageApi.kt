package com.ytstyle.bili.data.net

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the `api.vc.bilibili.com` Retrofit instance.
 *
 * The direct-message endpoints live on the VC host, not on `api.bilibili.com`,
 * so they need their own instance. Declared here rather than in Qualifiers.kt
 * because only the two VC-bound services below use it. Without it Dagger would
 * see three providers of the same `Retrofit` type and fail to build.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VcRetrofit

/**
 * Envelope shared by every message-centre endpoint.
 *
 * The payload is kept as a raw [JsonElement] on purpose: the four feeds have
 * genuinely different item shapes (a reply carries `reply.content.message`, a
 * like carries a `users` array, a system notice carries `title`/`text`), and
 * they occasionally gain or change fields. Parsing the envelope here and the
 * items by hand in the UI layer means a missing or oddly typed field degrades
 * to a blank row instead of a thrown serialization error.
 */
@Serializable
data class MsgEnvelope(
    val code: Int = -1,
    val message: String = "",
    val data: JsonElement? = null,
)

/**
 * The native message centre (the msgfeed endpoints).
 *
 * All five calls require a logged-in session: without the cookie the server
 * answers HTTP 200 with `code = -101` ("账号未登录").
 *
 * Paging is cursor based. The first page is requested with no `id`/`time`; each
 * response returns `data.cursor = { is_end, id, time }` and those two values
 * are sent back to fetch the next page.
 */
interface MessageApi {

    /**
     * Display names and faces for a comma-separated uid list (max 50).
     *
     * This is the **documented** card endpoint — `data` is an object keyed by the
     * mid as a string, each value carrying `{mid, name, face}` — so it is the one
     * whose shape the parser can be written against with confidence.
     *
     * Reachability checked anonymously before use: HTTP 200 with
     * `{"code":-101,"message":"账号未登录"}`, i.e. the path exists and only the
     * session is missing.
     */
    @GET("x/polymer/pc-electron/v1/user/cards")
    suspend fun userCardsPolymer(@Query("uids") uids: String): MsgEnvelope

    /** Unread counters: `data.unread.{reply, at, like, sys_msg}`. */
    @GET("x/msgfeed/unread")
    suspend fun unread(): MsgEnvelope

    /** Replies to my comments and videos. */
    @GET("x/msgfeed/reply")
    suspend fun replies(
        @Query("id") id: Long? = null,
        @Query("time") time: Long? = null,
        @Query("reply_time") replyTime: Long? = null,
        @Query("ps") ps: Int = 20,
    ): MsgEnvelope

    /** Mentions of me. */
    @GET("x/msgfeed/at")
    suspend fun mentions(
        @Query("id") id: Long? = null,
        @Query("time") time: Long? = null,
        @Query("at_time") atTime: Long? = null,
        @Query("ps") ps: Int = 20,
    ): MsgEnvelope

    /** Likes received. */
    @GET("x/msgfeed/like")
    suspend fun likes(
        @Query("id") id: Long? = null,
        @Query("time") time: Long? = null,
        @Query("like_time") likeTime: Long? = null,
        @Query("ps") ps: Int = 20,
    ): MsgEnvelope

    /** System notifications. */
    @GET("x/msgfeed/sys_msg")
    suspend fun systemMessages(
        @Query("id") id: Long? = null,
        @Query("time") time: Long? = null,
        @Query("ps") ps: Int = 20,
    ): MsgEnvelope
}

/**
 * The direct-message half of the message centre, served by
 * `https://api.vc.bilibili.com/`.
 *
 * These are the endpoints the 消息 page's 私信会话 list comes from. All three
 * need the login cookie: anonymously the server answers HTTP 200 with
 * `code = -101` ("账号未登录"). `get_sessions` can additionally answer
 * `-509 请求过于频繁` under rate limiting; the repository retries that once.
 */
interface VcMessageApi {

    /**
     * Unread counters on the VC host.
     *
     * Same `data.unread.{reply, at, like, sys_msg}` shape as the msgfeed one,
     * kept separate because it is a different host and is not used yet.
     */
    @GET("x/im/web/msgfeed/unread")
    suspend fun unreadVc(): MsgEnvelope

    /**
     * One page of direct-message sessions, newest first.
     *
     * [sessionType] is the *request* filter: 1 用户与系统, 2 未关注人,
     * 3 粉丝团, 4 所有, 7 所有系统消息, 9 关注的人与系统. (The per-row
     * `session_type` in the response means something else: 1 用户, 2 粉丝团.)
     * `sort_rule = 2` is "by session time, descending"; `size` is capped at 100.
     */
    @GET("session_svr/v1/session_svr/get_sessions")
    suspend fun sessions(
        @Query("session_type") sessionType: Int = 4,
        @Query("group_fold") groupFold: Int = 1,
        @Query("unfollow_fold") unfollowFold: Int = 1,
        @Query("sort_rule") sortRule: Int = 2,
        @Query("size") size: Int = 20,
        @Query("build") build: Int = 0,
        @Query("mobi_app") mobiApp: String = "web",
    ): MsgEnvelope

    /**
     * Display names and faces for a comma-separated uid list.
     *
     * The only optional call here: a failure just leaves the session rows
     * nameless instead of hiding the list.
     */
    @GET("account/v1/user/cards")
    suspend fun userCards(@Query("uids") uids: String): MsgEnvelope

    /**
     * 发送一条私信.
     *
     * **WBI-signed**, which is why the signature and its `w_sender_uid` /
     * `w_receiver_id` / `w_dev_id` mirrors travel in the query string while the
     * message itself is form-encoded `msg[...]` fields — that split is the
     * endpoint's own shape, not a choice made here.
     *
     * The endpoint accepts only `msg[msg_type]` of 1, 2 or 5, and answers
     * `{"code":0}` with no `data`. A successful send also marks the session
     * read, which is a side effect worth knowing about before calling it.
     */
    @FormUrlEncoded
    @POST("web_im/v1/web_im/send_msg")
    suspend fun sendMessage(
        @QueryMap signed: Map<String, String>,
        @FieldMap fields: Map<String, String>,
    ): MsgEnvelope

    /**
     * One page of a session's message history, newest first.
     *
     * Paging walks *backwards* in time: the previous page's `min_seqno` is sent
     * as [endSeqno] and the response ends just before it. `size` is capped at
     * 2000 but a value of `0` returns nothing but the system prompt, so the
     * default here is a real page size.
     *
     * `mobi_app=web` matters: it is what makes the response carry the current
     * emoji pack names rather than the pre-2019 aliases.
     *
     * Reading does **not** mark the session read; that needs [updateAck].
     */
    @GET("svr_sync/v1/svr_sync/fetch_session_msgs")
    suspend fun sessionMessages(
        @Query("talker_id") talkerId: Long,
        /** 1 = 用户, 2 = 粉丝团 — the row's own kind, not the list filter. */
        @Query("session_type") sessionType: Int,
        @Query("size") size: Int = SESSION_MESSAGE_PAGE_SIZE,
        @Query("end_seqno") endSeqno: Long? = null,
        @Query("sender_device_id") senderDeviceId: Int = 1,
        @Query("build") build: Int = 0,
        @Query("mobi_app") mobiApp: String = "web",
    ): MsgEnvelope

    /**
     * Marks a session read up to [ackSeqno] (null = the newest message).
     *
     * Best effort: a failure only leaves the unread badge in place.
     */
    @FormUrlEncoded
    @POST("session_svr/v1/session_svr/update_ack")
    suspend fun updateAck(
        @Field("talker_id") talkerId: Long,
        @Field("session_type") sessionType: Int,
        @Field("ack_seqno") ackSeqno: Long?,
        @Field("csrf_token") csrfToken: String,
        @Field("csrf") csrf: String,
        @Field("build") build: Int = 0,
        @Field("mobi_app") mobiApp: String = "web",
    ): MsgEnvelope

    companion object {
        /** Comfortably above one screen, far below the endpoint's cap of 2000. */
        const val SESSION_MESSAGE_PAGE_SIZE = 30
    }
}

/**
 * Binds the message services to their Retrofit instances.
 *
 * A separate module rather than another method on NetworkModule: these services
 * are only used by the message screen, and that file is shared ground. The VC
 * instance reuses the shared [OkHttpClient] (cookie/UA/Referer interceptor) and
 * [Json] so the two hosts cannot drift apart.
 */
@Module
@InstallIn(SingletonComponent::class)
object MessageApiModule {

    private const val VC_API_BASE = "https://api.vc.bilibili.com/"

    @Provides
    @Singleton
    fun provideMessageApi(@ApiRetrofit retrofit: Retrofit): MessageApi =
        retrofit.create(MessageApi::class.java)

    @Provides
    @Singleton
    @VcRetrofit
    fun provideVcRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(VC_API_BASE)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideVcMessageApi(@VcRetrofit retrofit: Retrofit): VcMessageApi =
        retrofit.create(VcMessageApi::class.java)
}
