package com.ytstyle.bili.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.download.DownloadStore
import com.ytstyle.bili.data.net.NavData
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import com.ytstyle.bili.data.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountUiState(
    val isLoggedIn: Boolean = false,
    val uname: String = "",
    val avatarUrl: String = "",
    val mid: Long = 0,
    val coins: Double = 0.0,
    val level: Int = 0,
    val currentExp: Int = 0,
    val nextExp: Int = 0,
    val moral: Int = 0,
    val isVip: Boolean = false,
    val vipLabel: String = "",
    val following: Int = 0,
    val followers: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** One-shot message shown as a strip, e.g. the casting explanation. */
    val notice: String? = null,
    /**
     * Cover images for the 资源库 cards, by entry.
     *
     * Missing entries fall back to a tinted icon panel, so a failed lookup
     * costs a picture rather than a card.
     */
    val libraryCovers: Map<LibraryEntry, String> = emptyMap(),
)

/**
 * The four destinations that used to be separate rows and are now one 资源库.
 *
 * The screen owns the click handling; this only names them and supplies the
 * cover each one is fetched with.
 */
enum class LibraryEntry(val title: String, val subtitle: String) {
    History("观看记录", "继续观看"),
    Favorites("我的收藏", "收藏夹与订阅"),
    WatchLater("稍后再看", "待观看"),
    Downloads("离线缓存", "断网也能看"),
}

/**
 * The default favourite folder's title.
 *
 * Matched by name because the listing does not flag it structurally; when B站
 * renames it in another locale the lookup falls back to the first folder, which
 * is the same one in practice.
 */
private const val DEFAULT_FAVOURITES_TITLE = "默认收藏夹"

/**
 * The single source of truth for "am I signed in".
 *
 * There used to be two instances of this view model — one owned by the app
 * shell and one created inside the account screen's navigation entry — so the
 * top bar and the 我的 tab could disagree, which is what "应用显示未登录"
 * described. The screen now receives this instance's state instead of creating
 * its own, and the state follows the **persisted cookie** rather than a single
 * network call:
 *
 *  - the cookie is what actually authenticates every request, so it decides
 *    logged-in vs logged-out;
 *  - `nav` only ever *enriches* that with a name, avatar and level, so failing
 *    to read it (offline, or a payload shape change) can no longer look like a
 *    logout;
 *  - a cookie the server rejects is dropped, so no screen is left claiming a
 *    session that no longer exists.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val repository: BiliRepository,
    private val session: SessionStore,
    /** Local download store: the 离线缓存 card's cover comes from here. */
    private val downloadStore: DownloadStore,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // collectLatest: a rapid login→logout pair cancels the in-flight
            // refresh instead of letting a stale response overwrite the state.
            session.cookieFlow.collectLatest { cookie ->
                if (cookie.isEmpty()) {
                    // Keep any message (e.g. "登录状态已过期") that was set
                    // immediately before the session was dropped.
                    _state.update { AccountUiState(notice = it.notice) }
                    return@collectLatest
                }

                // Seed from the persisted profile first: the avatar and name are
                // then correct on the first frame, before the network answers.
                val mid = session.midFlow.first()
                val uname = session.unameFlow.first()
                _state.update {
                    it.copy(isLoggedIn = true, mid = mid, uname = uname)
                }

                // Called directly rather than through `refresh()`, so this
                // collectLatest body actually suspends: a login immediately
                // followed by a logout then cancels the in-flight request
                // instead of letting its response overwrite the newer state.
                refreshNow()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    private suspend fun refreshNow() {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = repository.nav()) {
            is Result.Ok -> {
                apply(result.value)
                // Covers are for the 资源库 cards and nothing else, so they are
                // fetched after the profile is already on screen.
                loadLibraryCovers(result.value.mid)
            }
            // A failed call is not a logout: say so, keep the session.
            is Result.Err -> _state.update {
                it.copy(isLoading = false, error = result.message)
            }
        }

        // Follower/following counts are a separate, non-critical call.
        when (val stat = repository.navStat()) {
            is Result.Ok -> _state.update {
                it.copy(
                    following = stat.value.following,
                    followers = stat.value.follower,
                )
            }
            is Result.Err -> Unit
        }
    }

    /**
     * One cover per 资源库 card, from whichever endpoint already owns that list.
     *
     * Every lookup is independent and optional: the cards are navigation, not
     * content, so a failure here must not blank the screen or surface an error.
     * 离线缓存 is deliberately absent — its thumbnails live in the local
     * download store, not behind an endpoint, so that card keeps the fallback
     * panel rather than a wrong picture.
     */
    private fun loadLibraryCovers(mid: Long) {
        viewModelScope.launch {
            val covers = mutableMapOf<LibraryEntry, String>()

            val history = repository.history()
            if (history is Result.Ok) {
                history.value.list.firstOrNull()?.cover?.takeIf { it.isNotEmpty() }
                    ?.let { covers[LibraryEntry.History] = it }
            }

            val later = repository.watchLater()
            if (later is Result.Ok) {
                later.value.list.firstOrNull()?.pic?.takeIf { it.isNotEmpty() }
                    ?.let { covers[LibraryEntry.WatchLater] = it }
            }

            // 我的收藏: the newest video **inside** the default folder, not the
            // folder's own cover. `FavFolder` carries no cover of its own and the
            // default folder in particular has none, so the card used to fall back
            // to the generic icon panel despite the folder being full of videos.
            // `x/v3/fav/resource/list` is ordered newest first, so the first item
            // is the most recent 收藏.
            val folders = repository.favFolders(mid)
            if (folders is Result.Ok) {
                val list = folders.value.list
                val folder = list.firstOrNull { it.title == DEFAULT_FAVOURITES_TITLE }
                    ?: list.firstOrNull()
                if (folder != null && folder.id > 0) {
                    when (val items = repository.favResources(folder.id)) {
                        // `FavResourceList` calls its rows `medias`, not `list`.
                        is Result.Ok -> items.value.medias
                            .firstOrNull()
                            ?.cover
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { covers[LibraryEntry.Favorites] = it }

                        // The card keeps its icon panel; a folder read failure is
                        // not worth an error on a navigation tile.
                        is Result.Err -> Unit
                    }
                }
            }

            // 离线缓存: the most recently *downloaded* video. This lives in the
            // local store rather than behind an endpoint — `DownloadStore` already
            // sorts newest-first — so it needs no network call and is right even
            // when offline.
            runCatching { downloadStore.items.first().firstOrNull() }
                .getOrNull()
                ?.cover
                ?.takeIf { it.isNotEmpty() }
                ?.let { covers[LibraryEntry.Downloads] = it }

            if (covers.isNotEmpty()) {
                _state.update { it.copy(libraryCovers = it.libraryCovers + covers) }
            }
        }
    }

    private suspend fun apply(nav: NavData) {
        if (!nav.isLogin) {
            // The server does not accept the stored cookie any more. Dropping it
            // makes every screen agree that nobody is signed in; keeping it left
            // the top bar and the settings page claiming a live session.
            if (session.currentCookie().isNotEmpty()) {
                _state.update { it.copy(notice = "登录状态已过期，请重新登录") }
                session.clear()
            } else {
                _state.update { it.copy(isLoggedIn = false, isLoading = false) }
            }
            return
        }

        // Keep the persisted profile in sync so the avatar survives restart.
        session.saveProfile(nav.uname, nav.face)
        _state.update {
            it.copy(
                isLoggedIn = true,
                uname = nav.uname,
                avatarUrl = nav.face,
                mid = nav.mid,
                coins = nav.money,
                level = nav.level_info?.currentLevel ?: 0,
                currentExp = nav.level_info?.currentMin ?: 0,
                nextExp = nav.level_info?.nextExp ?: 0,
                moral = nav.moral,
                isVip = nav.vipStatus == 1,
                vipLabel = nav.vipLabel,
                isLoading = false,
                error = null,
            )
        }
    }

    /**
     * Explains why the cast button cannot just work.
     *
     * Bilibili's cast protocol is private; the reachable route is DLNA to their
     * TV app plus a local proxy that re-signs requests. Saying so is better than
     * a button that silently does nothing.
     */
    fun showCastNotice() {
        _state.update {
            it.copy(
                notice = "投屏：官方投屏协议未公开。可行路线是 DLNA 发现 + 本机代理" +
                    "（补 Referer 并转发），可投到云视听小电视；尚未实现。" +
                    "可先用分享把链接发到电视端 App。",
            )
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun logout() {
        viewModelScope.launch { session.clear() }
    }
}
