package com.ytstyle.bili.data.net

import java.util.concurrent.ConcurrentHashMap

/**
 * Collects the session cookies Bilibili hands back in `Set-Cookie` headers.
 *
 * The QR login flow is unusual: the poll endpoint returns `code: 0` in its
 * body and sets `SESSDATA`, `bili_jct` and `DedeUserID` as response headers on
 * that same call. Those headers are not visible to Retrofit, so the OkHttp
 * interceptor scrapes them into this holder, and the login flow drains it once
 * the scan is confirmed.
 */
class CookieCapture {

    private val cookies = ConcurrentHashMap<String, String>()

    /** Only these carry authentication; the rest are tracking noise. */
    private val relevant = setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")

    fun absorb(setCookieHeaders: List<String>) {
        for (header in setCookieHeaders) {
            // A Set-Cookie value is "name=value; Path=/; ..."; take the pair.
            val pair = header.substringBefore(';').trim()
            val name = pair.substringBefore('=', "").trim()
            if (name in relevant) {
                cookies[name] = pair.substringAfter('=', "")
            }
        }
    }

    fun hasSession(): Boolean = cookies.containsKey("SESSDATA")

    /** Returns a `k=v; k=v` header, or null when nothing useful was captured. */
    fun drain(): String? {
        if (!hasSession()) return null
        val header = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        cookies.clear()
        return header
    }

    fun clear() = cookies.clear()
}
