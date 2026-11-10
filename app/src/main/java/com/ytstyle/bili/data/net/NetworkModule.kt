package com.ytstyle.bili.data.net

import com.ytstyle.bili.data.log.AppLog
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
        val host = original.url.host

        // Each host family checks where the request came from, so the Referer is
        // chosen per site rather than forced to the video site's:
        //
        //  - Login lives on `passport.bilibili.com`, and sending the video site's
        //    Referer there is one way credential logins come back as a
        //    parameter/risk-control error while the same body works from the
        //    passport page itself.
        //  - The live APIs (`api.live.bilibili.com`) reject requests carrying
        //    `www.bilibili.com` with `code = -352 风控校验失败` — confirmed from a
        //    device log, where every `getDanmuInfo` returned HTTP 200 with -352
        //    and the danmaku socket therefore never authenticated.
        val sameSite = when {
            host.endsWith("passport.bilibili.com") -> "https://passport.bilibili.com"
            host.endsWith("live.bilibili.com") -> "https://live.bilibili.com"
            else -> "https://www.bilibili.com"
        }
        val referer = if (sameSite.endsWith("passport.bilibili.com")) {
            "$sameSite/login"
        } else {
            "$sameSite/"
        }

        val builder = original.newBuilder()
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Origin", sameSite)
            .header("Accept", "application/json, text/plain, */*")

        // Login session plus the buvid device fingerprint. Both are needed:
        // without buvid a share of requests is answered with HTTP 412.
        val cookie = session.requestCookie()
        if (cookie.isNotEmpty()) {
            builder.header("Cookie", cookie)
        }

        // Logged as method + path + status only. The query string is dropped on
        // purpose: Bilibili's carries signatures and tokens, and the log is
        // meant to be shareable.
        val startedAt = System.currentTimeMillis()
        val response = try {
            chain.proceed(builder.build())
        } catch (e: Exception) {
            AppLog.e(
                "http",
                "${original.method} ${original.url.encodedPath} failed after " +
                    "${System.currentTimeMillis() - startedAt}ms",
                e,
            )
            throw e
        }

        val elapsed = System.currentTimeMillis() - startedAt
        val line = "${original.method} ${original.url.encodedPath} -> " +
            "${response.code} (${elapsed}ms)"
        if (response.isSuccessful) {
            AppLog.d("http", line)
        } else {
            // HTTP-level failures are worth keeping even in release builds.
            AppLog.w("http", line)
        }

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
