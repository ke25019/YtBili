package com.ytstyle.bili.data.net

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.JsonElement
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import javax.inject.Singleton

/**
 * The 动态 (feed of followed uploaders) endpoints.
 *
 * `x/polymer/web-dynamic/v1/feed/all` is the one the official clients use. It
 * was previously assumed to be unreachable, but a request without a session
 * answers HTTP 200 with `code = -101 账号未登录` rather than 404 — the path is
 * live, it just needs the cookie the app already attaches.
 *
 * No WBI signature is documented for any of these paths (`认证方式: Cookie`), so
 * none is sent. Responses are kept as raw [JsonElement]: the item schema is
 * wide, versioned (`itemOpusStyle`) and drifts, so it is walked by hand in
 * [parseDynamicPage] instead of being bound to a strict data class.
 */
interface DynamicApi {

    /**
     * `type` is `all` (全部), `video` (投稿), `pgc` (番剧/影视) or `article`
     * (专栏). There is **no** `up` type despite the official tab bar showing
     * one, so this app does not offer that tab.
     *
     * Paging is by opaque cursor: send [offset] empty for the first page, then
     * whatever `data.offset` the previous response returned.
     */
    @GET("x/polymer/web-dynamic/v1/feed/all")
    suspend fun feedAll(
        @Query("type") type: String = "all",
        @Query("offset") offset: String = "",
        @Query("timezone_offset") timezoneOffset: Int = -480,
        @Query("platform") platform: String = "web",
    ): BiliResponse<JsonElement>

    /**
     * One uploader's dynamics. Unlike [feedAll] this is **public**: it was
     * verified to answer `code = 0` with real items while signed out, which is
     * what lets the rail's `我` and per-UP entries work at all.
     */
    @GET("x/polymer/web-dynamic/v1/feed/space")
    suspend fun feedSpace(
        @Query("host_mid") hostMid: Long,
        @Query("offset") offset: String = "",
        @Query("timezone_offset") timezoneOffset: Int = -480,
    ): BiliResponse<JsonElement>

    /**
     * The user's followings, newest-attention first (`order_type=attention`).
     * Feeds the left rail's avatar column. Login-only.
     */
    @GET("x/relation/followings")
    suspend fun followings(
        @Query("vmid") vmid: Long,
        @Query("ps") ps: Int = 20,
        @Query("pn") pn: Int = 1,
        @Query("order_type") orderType: String = "attention",
    ): BiliResponse<JsonElement>
}

@Module
@InstallIn(SingletonComponent::class)
object DynamicApiModule {

    @Provides
    @Singleton
    fun provideDynamicApi(@ApiRetrofit retrofit: Retrofit): DynamicApi =
        retrofit.create(DynamicApi::class.java)
}
