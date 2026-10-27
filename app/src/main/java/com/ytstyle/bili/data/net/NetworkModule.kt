package com.ytstyle.bili.data.net

import com.ytstyle.bili.data.session.SessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val API_BASE = "https://api.bilibili.com/"

    /**
     * A desktop-browser UA is required: Bilibili serves degraded payloads
     * (and blocks some endpoints outright) to unknown/Android UAs.
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private const val REFERER = "https://www.bilibili.com/"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    /**
     * Attaches the session cookie, UA and Referer to every request.
     * Referer is mandatory for playurl/danmaku; without it Bilibili returns 403.
     */
    /**
     * Captures `Set-Cookie` headers from responses.
     *
     * The QR login flow returns the session cookies on the *poll response*
     * itself rather than in the JSON body, so they have to be scraped from the
     * headers and handed to [SessionStore].
     */
    @Provides
    @Singleton
    fun provideCookieCapture(): CookieCapture = CookieCapture()

    @Provides
    @Singleton
    fun provideSessionInterceptor(
        session: SessionStore,
        cookieCapture: CookieCapture,
    ): Interceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Origin", "https://www.bilibili.com")
            .header("Accept", "application/json, text/plain, */*")

        // Login session plus the buvid device fingerprint. Both are needed:
        // without buvid a share of requests is answered with HTTP 412.
        val cookie = session.requestCookie()
        if (cookie.isNotEmpty()) {
            builder.header("Cookie", cookie)
        }

        val response = chain.proceed(builder.build())
        cookieCapture.absorb(response.headers("Set-Cookie"))
        response
    }

    @Provides
    @Singleton
    fun provideOkHttp(interceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    @Provides
    @Singleton
    @ApiRetrofit
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(API_BASE)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideBiliApi(@ApiRetrofit retrofit: Retrofit): BiliApi =
        retrofit.create(BiliApi::class.java)

}
