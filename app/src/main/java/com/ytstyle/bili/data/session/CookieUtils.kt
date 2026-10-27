package com.ytstyle.bili.data.session

/**
 * Cookie header parsing, split out of [SessionStore] so it can be unit tested.
 *
 * Bilibili's web session is carried entirely in cookies, and a malformed header
 * silently degrades to "not logged in", so this parsing is worth testing
 * directly.
 */
object CookieUtils {

    /**
     * Normalises a pasted cookie string into a canonical `k=v; k=v` header.
     *
     * Accepts input with arbitrary whitespace, trailing semicolons, and either
     * `\n` or `;` separators, because users paste these out of browser devtools
     * in several different shapes. Later duplicates win, which matches how a
     * browser resolves an overwritten cookie.
     */
    fun normalize(raw: String): String {
        val seen = LinkedHashMap<String, String>()
        raw.split(';', '\n', '\r')
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach { pair ->
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=').trim()
                if (name.isNotEmpty()) {
                    // Re-inserting moves the key to the end, so the last
                    // occurrence wins while ordering stays deterministic.
                    seen.remove(name)
                    seen[name] = value
                }
            }
        return seen.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** Returns the value of [key] in a normalised cookie header, or "". */
    fun value(cookie: String, key: String): String =
        cookie.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.trim()
            .orEmpty()

    /** True when the header carries a usable session token. */
    fun hasSession(cookie: String): Boolean = value(cookie, "SESSDATA").isNotEmpty()
}
