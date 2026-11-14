package com.ytstyle.bili.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.session.AccountStore
import com.ytstyle.bili.data.session.SavedAccount
import com.ytstyle.bili.data.session.SessionStore
import com.ytstyle.bili.data.settings.AudioPreference
import com.ytstyle.bili.data.settings.AutoplayMode
import com.ytstyle.bili.data.settings.SettingsStore
import com.ytstyle.bili.data.settings.ThemeMode
import com.ytstyle.bili.data.update.UpdateChannel
import com.ytstyle.bili.ui.player.DanmakuMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val session: SessionStore,
    private val accountStore: AccountStore,
) : ViewModel() {

    /**
     * Values are read straight from DataStore rather than mirrored into a
     * MutableStateFlow, so the activity and this screen cannot drift apart.
     */
    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.Light)

    val danmakuEnabled: StateFlow<Boolean> = settings.danmakuEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** When the player may start on its own; a policy, not a plain switch. */
    val autoplayMode: StateFlow<AutoplayMode> = settings.autoplayMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, AutoplayMode.Always)

    // ---- danmaku ----

    val danmakuScale: StateFlow<Float> = settings.danmakuScale
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)

    val danmakuOpacity: StateFlow<Float> = settings.danmakuOpacity
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)

    val danmakuArea: StateFlow<Float> = settings.danmakuArea
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)

    val danmakuSpeed: StateFlow<Float> = settings.danmakuSpeed
        .stateIn(viewModelScope, SharingStarted.Eagerly, 8f)

    val danmakuTypes: StateFlow<Set<Int>> = settings.danmakuTypes
        .stateIn(viewModelScope, SharingStarted.Eagerly, setOf(1, 4, 5))

    // ---- 应用内更新 ----

    /** Whether the app checks for updates by itself at launch. */
    val autoUpdate: StateFlow<Boolean> = settings.autoUpdate
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Which releases the updater may offer.
     *
     * The other update axis, the build type, is fixed by the installed package
     * and is therefore not a setting.
     */
    val updateChannel: StateFlow<UpdateChannel> = settings.updateChannel
        .stateIn(viewModelScope, SharingStarted.Eagerly, UpdateChannel.Stable)

    fun setUpdateChannel(channel: UpdateChannel) {
        viewModelScope.launch { settings.setUpdateChannel(channel) }
    }

    fun setAutoUpdate(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoUpdate(enabled) }
    }

    // ---- home feed ----

    val homeGrid: StateFlow<Boolean> = settings.homeGrid
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Experimental: read another client's offline cache. */
    val externalCache: StateFlow<Boolean> = settings.externalCache
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setExternalCache(enabled: Boolean) {
        viewModelScope.launch { settings.setExternalCache(enabled) }
    }

    // ---- 音画设置 ----

    /** Default playback quality (`qn`), 1080P unless changed. */
    val defaultQuality: StateFlow<Int> = settings.defaultQuality
        .stateIn(viewModelScope, SharingStarted.Eagerly, 80)

    val audioPreference: StateFlow<AudioPreference> = settings.audioPreference
        .stateIn(viewModelScope, SharingStarted.Eagerly, AudioPreference.Auto)

    fun setDefaultQuality(qn: Int) = launch { settings.setDefaultQuality(qn) }

    fun setAudioPreference(preference: AudioPreference) =
        launch { settings.setAudioPreference(preference) }

    // ---- account ----

    val isLoggedIn: StateFlow<Boolean> = session.cookieFlow
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Displayed on the account page so the user can see which account is active. */
    val uname: StateFlow<String> = session.unameFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Every account that has signed in here, newest first. */
    val savedAccounts: StateFlow<List<SavedAccount>> = accountStore.accounts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The active account's mid, so the list can mark it. */
    val activeMid: StateFlow<Long> = session.midFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    /**
     * Switches to a remembered account by restoring its cookie.
     *
     * No sign-in is involved: the cookie *is* the session, so writing it back
     * makes every subsequent request act as that account. Accounts whose cookie
     * Bilibili has since expired will simply fail their next call and get
     * dropped by `AccountViewModel`.
     */
    fun switchToAccount(mid: Long) {
        viewModelScope.launch {
            accountStore.cookieOf(mid)?.let { session.save(it) }
        }
    }

    /** Forgets an account, so it can no longer be switched to. */
    fun forgetAccount(mid: Long) {
        viewModelScope.launch {
            accountStore.forget(mid)
            // Forgetting the one in use also signs it out.
            if (mid == activeMid.value) session.clear()
        }
    }

    /** Signs out and keeps local preferences. */
    fun logout() { viewModelScope.launch { session.clear() } }

    fun setThemeMode(mode: ThemeMode) = launch { settings.setThemeMode(mode) }

    fun setDanmakuEnabled(enabled: Boolean) = launch { settings.setDanmakuEnabled(enabled) }

    fun setAutoplayMode(mode: AutoplayMode) = launch { settings.setAutoplayMode(mode) }

    fun setDanmakuScale(value: Float) = launch { settings.setDanmakuScale(value) }

    fun setDanmakuOpacity(value: Float) = launch { settings.setDanmakuOpacity(value) }

    fun setDanmakuArea(value: Float) = launch { settings.setDanmakuArea(value) }

    fun setDanmakuSpeed(value: Float) = launch { settings.setDanmakuSpeed(value) }

    fun toggleDanmakuType(mode: Int) {
        val current = danmakuTypes.value
        val next = if (mode in current) current - mode else current + mode
        // Refuse to end up with nothing selected; the toggle would be a trap.
        if (next.isEmpty()) return
        launch { settings.setDanmakuTypes(next) }
    }

    /** 重置弹幕设置: back to the documented defaults, in one pass. */
    fun resetDanmakuSettings() = launch {
        settings.setDanmakuScale(1f)
        settings.setDanmakuOpacity(1f)
        settings.setDanmakuArea(1f)
        settings.setDanmakuSpeed(8f)
        settings.setDanmakuTypes(
            setOf(DanmakuMode.SCROLL, DanmakuMode.BOTTOM, DanmakuMode.TOP),
        )
    }

    fun setHomeGrid(enabled: Boolean) = launch { settings.setHomeGrid(enabled) }

    /**
     * Signs out.
     *
     * @param keepLocalData when true only the session is dropped — preferences
     *   and the device fingerprint survive, which is what "switch account but
     *   keep my settings" means. When false everything local is wiped.
     */
    fun switchAccount(keepLocalData: Boolean) {
        viewModelScope.launch {
            session.clear()
            if (!keepLocalData) settings.clear()
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
