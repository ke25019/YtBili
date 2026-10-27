package com.ytstyle.bili.data.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionDataStore by preferencesDataStore(name = "ytbili_session")

/**
 * Persists the Bilibili web session (cookies) plus the derived csrf token.
 *
 * Bilibili's web API authenticates purely by cookie: `SESSDATA` identifies the
 * account, `bili_jct` doubles as the CSRF token for every write request, and
 * `DedeUserID` carries the mid. We store the raw cookie header verbatim so a
 * session captured elsewhere can be pasted in for testing.
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val COOKIE = stringPreferencesKey("cookie_header")
        val CSRF = stringPreferencesKey("csrf")
        val MID = stringPreferencesKey("mid")
        val UNAME = stringPreferencesKey("uname")
        val FACE = stringPreferencesKey("face")

        /** Device fingerprint cookies, separate from the *login* session. */
        val BUVID = stringPreferencesKey("buvid_header")
    }

    val cookieFlow: Flow<String> = context.sessionDataStore.data.map { it[Keys.COOKIE] ?: "" }
    val csrfFlow: Flow<String> = context.sessionDataStore.data.map { it[Keys.CSRF] ?: "" }
    val midFlow: Flow<Long> = context.sessionDataStore.data.map { it[Keys.MID]?.toLongOrNull() ?: 0L }
    val unameFlow: Flow<String> = context.sessionDataStore.data.map { it[Keys.UNAME] ?: "" }

    /** Synchronous read for the OkHttp interceptor, which runs off the main flow. */
    @Volatile
    var cachedCookie: String = ""
        private set

    @Volatile
    var cachedCsrf: String = ""
        private set

    /**
     * `buvid3`/`buvid4`, kept apart from [cachedCookie] so that logging out does
     * not discard the device fingerprint (and vice versa).
     */
    @Volatile
    var cachedBuvid: String = ""
        private set

    val buvidFlow: Flow<String> =
        context.sessionDataStore.data.map { it[Keys.BUVID] ?: "" }

    suspend fun currentBuvid(): String = buvidFlow.first()

    suspend fun saveBuvid(b3: String, b4: String) {
        if (b3.isEmpty()) return
        val header = buildString {
            append("buvid3=").append(b3)
            if (b4.isNotEmpty()) append("; buvid4=").append(b4)
        }
        context.sessionDataStore.edit { it[Keys.BUVID] = header }
        cachedBuvid = header
    }

    suspend fun currentCookie(): String = cookieFlow.first()

    suspend fun currentCsrf(): String = csrfFlow.first()

    /** Load persisted values into the in-memory cache the interceptor reads. */
    suspend fun prime() {
        cachedCookie = cookieFlow.first()
        cachedCsrf = csrfFlow.first()
        cachedBuvid = buvidFlow.first()
    }

    /** Full request cookie: login session plus device fingerprint. */
    fun requestCookie(): String =
        listOf(cachedCookie, cachedBuvid).filter { it.isNotEmpty() }.joinToString("; ")

    suspend fun save(rawCookie: String) {
        val normalized = CookieUtils.normalize(rawCookie)
        val csrf = CookieUtils.value(normalized, "bili_jct")
        val mid = CookieUtils.value(normalized, "DedeUserID")
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.COOKIE] = normalized
            prefs[Keys.CSRF] = csrf
            prefs[Keys.MID] = mid
        }
        cachedCookie = normalized
        cachedCsrf = csrf
    }

    suspend fun saveProfile(uname: String, face: String) {
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.UNAME] = uname
            prefs[Keys.FACE] = face
        }
    }

    /**
     * Clears the *login* session only. The buvid fingerprint is device-scoped,
     * not account-scoped, and is deliberately retained.
     */
    suspend fun clear() {
        val buvid = cachedBuvid
        context.sessionDataStore.edit {
            it.clear()
            if (buvid.isNotEmpty()) it[Keys.BUVID] = buvid
        }
        cachedCookie = ""
        cachedCsrf = ""
        cachedBuvid = buvid
    }

}
