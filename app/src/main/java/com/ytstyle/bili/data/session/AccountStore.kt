package com.ytstyle.bili.data.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.accountDataStore by preferencesDataStore(name = "ytbili_accounts")

/**
 * One remembered login.
 *
 * The cookie is kept verbatim because that *is* the session: Bilibili has no
 * refresh token, so being able to switch back to an account later means having
 * kept its cookie.
 */
@Serializable
data class SavedAccount(
    val mid: Long = 0,
    val uname: String = "",
    val face: String = "",
    /** Raw cookie header, as captured at sign-in. */
    val cookie: String = "",
    val savedAt: Long = 0,
)

/**
 * Remembers every account that has signed in on this device.
 *
 * `SessionStore` holds **one** active session, which is all the network layer
 * needs. Switching accounts used to mean starting over — sign out, sign in
 * again, captcha and all — because the previous cookie was thrown away. This
 * keeps them, so switching is instant. Cookies still expire on Bilibili's side;
 * the list is a convenience, not a way around signing in.
 *
 * Stored as one JSON string under a single preference key (the list is tiny and
 * there is nothing to migrate if the shape changes).
 */
@Singleton
class AccountStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val ACCOUNTS = stringPreferencesKey("accounts")
    }

    /** `ignoreUnknownKeys` keeps an older entry readable after a field is added. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Newest sign-in first. */
    val accounts: Flow<List<SavedAccount>> =
        context.accountDataStore.data.map { prefs -> decode(prefs[Keys.ACCOUNTS]) }

    /**
     * Adds or refreshes the account behind [cookie].
     *
     * The mid comes from the cookie itself: it is the only identifier available
     * before any API call, and `DedeUserID` is where Bilibili puts it.
     */
    suspend fun remember(cookie: String, uname: String = "", face: String = "") {
        val mid = CookieUtils.value(cookie, "DedeUserID").toLongOrNull() ?: return
        if (mid <= 0 || cookie.isEmpty()) return

        context.accountDataStore.edit { prefs ->
            val current = decode(prefs[Keys.ACCOUNTS])
            val existing = current.firstOrNull { it.mid == mid }
            val entry = SavedAccount(
                mid = mid,
                uname = uname.ifEmpty { existing?.uname.orEmpty() },
                face = face.ifEmpty { existing?.face.orEmpty() },
                cookie = cookie,
                savedAt = existing?.savedAt ?: System.currentTimeMillis(),
            )
            // Refreshing an existing account must not reorder the list, or the
            // one the user is using would jump to the top on every profile read.
            prefs[Keys.ACCOUNTS] = encode(
                if (existing == null) listOf(entry) + current else current.map {
                    if (it.mid == mid) entry else it
                },
            )
        }
    }

    /** Refreshes the cached name/avatar of an account that is already stored. */
    suspend fun updateProfile(mid: Long, uname: String, face: String) {
        if (mid <= 0 || (uname.isEmpty() && face.isEmpty())) return
        context.accountDataStore.edit { prefs ->
            val current = decode(prefs[Keys.ACCOUNTS])
            if (current.none { it.mid == mid }) return@edit
            prefs[Keys.ACCOUNTS] = encode(
                current.map {
                    if (it.mid == mid) {
                        it.copy(uname = uname.ifEmpty { it.uname }, face = face.ifEmpty { it.face })
                    } else {
                        it
                    }
                },
            )
        }
    }

    /** Forgets one account; its cookie stops being switchable-to. */
    suspend fun forget(mid: Long) {
        context.accountDataStore.edit { prefs ->
            val remaining = decode(prefs[Keys.ACCOUNTS]).filterNot { it.mid == mid }
            if (remaining.isEmpty()) {
                prefs.remove(Keys.ACCOUNTS)
            } else {
                prefs[Keys.ACCOUNTS] = encode(remaining)
            }
        }
    }

    /** The cookie to restore for [mid], or null when it is not remembered. */
    suspend fun cookieOf(mid: Long): String? =
        accounts.first().firstOrNull { it.mid == mid }?.cookie?.takeIf { it.isNotEmpty() }

    private fun decode(raw: String?): List<SavedAccount> =
        raw?.let { runCatching { json.decodeFromString<List<SavedAccount>>(it) }.getOrNull() }
            .orEmpty()
            .filter { it.mid > 0 && it.cookie.isNotEmpty() }

    private fun encode(list: List<SavedAccount>): String = json.encodeToString(list)
}
