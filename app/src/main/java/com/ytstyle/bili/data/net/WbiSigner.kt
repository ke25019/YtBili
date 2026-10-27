package com.ytstyle.bili.data.net

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bilibili's WBI request signing.
 *
 * A growing set of web endpoints (`x/player/wbi/playurl`, `x/v2/reply/wbi/main`,
 * `x/web-interface/wbi/search/type`, ...) reject unsigned requests with `-403`.
 * Signing works like this:
 *
 *  1. Fetch `img_url`/`sub_url` from `x/web-interface/nav` and take the file
 *     stem of each as `img_key` and `sub_key`.
 *  2. Concatenate them and reorder the 64 characters through a fixed
 *     permutation table, then keep the **first 32** as `mixin_key`.
 *  3. Sort the request params by name, append `wts` (unix seconds), url-encode,
 *     then `w_rid = md5(query + mixin_key)`.
 *
 * The keys rotate daily, so the cached keys are refreshed on a timer.
 */
@Singleton
class WbiSigner @Inject constructor() {

    @Volatile private var mixinKey: String = ""
    @Volatile private var fetchedAt: Long = 0

    /** Fixed character permutation published in Bilibili's web bundle. */
    private val mixinKeyEncTab = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52,
    )

    val isReady: Boolean get() = mixinKey.isNotEmpty()

    /** Builds the mixin key from the two nav keys. */
    fun updateKeys(imgUrl: String, subUrl: String) {
        val imgKey = imgUrl.substringAfterLast('/').substringBefore('.')
        val subKey = subUrl.substringAfterLast('/').substringBefore('.')
        val raw = imgKey + subKey
        if (raw.length < 64) return

        // The permutation table reads all 64 characters of `raw`, but Bilibili
        // keeps only the first 32 as the mixin key. Using all 64 still yields a
        // well-formed 32-hex `w_rid` that the server rejects with -403, so this
        // truncation is load-bearing.
        mixinKey = mixinKeyEncTab
            .joinToString("") { raw[it].toString() }
            .take(MIXIN_KEY_LENGTH)

        fetchedAt = System.currentTimeMillis() / 1000
    }

    /** Exposed so unit tests can independently rebuild the expected signature. */
    internal val mixinKeyForTest: String get() = mixinKey

    /** Keys are rotated daily; refresh a little before that. */
    fun isStale(): Boolean =
        mixinKey.isEmpty() || (System.currentTimeMillis() / 1000 - fetchedAt) > 12 * 3600

    /**
     * Returns [params] plus `wts` and `w_rid`. If the mixin key is unavailable
     * the params are returned untouched, so callers degrade to a `-403` from
     * the server rather than crashing locally.
     *
     * [nowSeconds] is injectable so signatures can be asserted against fixed
     * vectors in tests.
     */
    fun sign(
        params: Map<String, String>,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Map<String, String> {
        val key = mixinKey
        if (key.isEmpty()) return params

        val wts = nowSeconds.toString()
        val sorted = (params + ("wts" to wts)).toSortedMap()
        val query = sorted.entries.joinToString("&") { (k, v) ->
            "${k.urlEncode()}=${v.urlEncode()}"
        }
        return sorted + ("w_rid" to md5(query + key))
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** Bilibili keeps only the first 32 characters of the permuted key. */
        const val MIXIN_KEY_LENGTH = 32
    }
}

/**
 * Bilibili's JS uses `encodeURIComponent`, which leaves `!'()*` unescaped.
 * Java's URLEncoder escapes them and encodes space as `+`, which produces a
 * wrong signature, so we undo both. Internal so tests can assert parity with
 * `encodeURIComponent`.
 */
internal fun String.urlEncode(): String =
    java.net.URLEncoder.encode(this, "UTF-8")
        .replace("+", "%20")
        .replace("%21", "!")
        .replace("%27", "'")
        .replace("%28", "(")
        .replace("%29", ")")
        .replace("%2A", "*")
        .replace("%7E", "~")
