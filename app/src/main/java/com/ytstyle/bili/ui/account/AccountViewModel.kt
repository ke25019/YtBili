package com.ytstyle.bili.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.net.NavData
import com.ytstyle.bili.data.repo.BiliRepository
import com.ytstyle.bili.data.repo.Result
import com.ytstyle.bili.data.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val repository: BiliRepository,
    private val session: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    private var hasLoaded = false

    /** Called on every recomposition of the shell; only hits the network once. */
    fun refreshIfNeeded() {
        if (!hasLoaded) refresh()
    }

    fun refresh() {
        hasLoaded = true
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.nav()) {
                is Result.Ok -> apply(result.value)
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
    }

    private suspend fun apply(nav: NavData) {
        if (nav.isLogin) {
            // Keep the persisted profile in sync so the avatar survives restart.
            session.saveProfile(nav.uname, nav.face)
        }
        _state.update {
            it.copy(
                isLoggedIn = nav.isLogin,
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

    fun logout() {
        viewModelScope.launch {
            session.clear()
            _state.value = AccountUiState()
            hasLoaded = false
        }
    }
}
