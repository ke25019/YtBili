package com.ytstyle.bili.data.net

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Query
import javax.inject.Singleton

/**
 * Writing to a 动态: the 点赞 endpoint.
 *
 * Separate from [DynamicApi] because that file is read-only and this one needs
 * a CSRF token on the query string.
 */
interface DynamicActionApi {

    /**
     * Likes or unlikes one dynamic.
     *
     * [DynamicThumbBody.up] is explicit rather than a toggle: `0` asks the
     * server to flip whatever state it holds, which makes an optimistic UI
     * unrecoverable when the local state and the server's disagree. `1` likes,
     * `2` unlikes.
     *
     * Documented failures: `-101` 未登录, `-111` csrf 校验失败, `4100001` 参数错误.
     */
    @POST("x/dynamic/feed/dyn/thumb")
    suspend fun thumb(
        @Query("csrf") csrf: String,
        @Body body: DynamicThumbBody,
    ): BiliResponse<JsonElement>
}

/** Body of [DynamicActionApi.thumb]; `dyn_id_str` is what the feed hands out. */
@Serializable
data class DynamicThumbBody(
    @SerialName("dyn_id_str") val dynIdStr: String,
    /** 1 = 点赞, 2 = 取消点赞. */
    val up: Int,
)

/**
 * 转发 (repost) still lives on the legacy VC host: the newer
 * `x/dynamic/feed/create/dyn` only publishes original posts, its `dyn_req`
 * carrying no field for the forwarded dynamic.
 *
 * The body is form-encoded and needs the CSRF token twice (`csrf` and
 * `csrf_token`), which is what the old clients send.
 */
interface VcDynamicApi {

    @FormUrlEncoded
    @POST("dynamic_svr/v1/dynamic_svr/create")
    suspend fun repost(
        /** The forwarded dynamic's id. */
        @Field("dynamic_id") dynamicId: String,
        /** 1 = 转发 (`4` would publish an original text post). */
        @Field("type") type: Int,
        @Field("rid") rid: Long,
        /** The user's own comment on the repost; may be empty. */
        @Field("content") content: String,
        @Field("up_choose_comment") upChooseComment: Int,
        @Field("up_close_comment") upCloseComment: Int,
        @Field("csrf_token") csrfToken: String,
        @Field("csrf") csrf: String,
    ): BiliResponse<JsonElement>
}

@Module
@InstallIn(SingletonComponent::class)
object DynamicActionApiModule {

    @Provides
    @Singleton
    fun provideDynamicActionApi(@ApiRetrofit retrofit: Retrofit): DynamicActionApi =
        retrofit.create(DynamicActionApi::class.java)

    @Provides
    @Singleton
    fun provideVcDynamicApi(@VcRetrofit retrofit: Retrofit): VcDynamicApi =
        retrofit.create(VcDynamicApi::class.java)
}
