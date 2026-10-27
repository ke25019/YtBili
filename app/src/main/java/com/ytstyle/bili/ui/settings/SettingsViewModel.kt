package com.ytstyle.bili.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytstyle.bili.data.session.SessionStore
import com.ytstyle.bili.data.settings.SettingsStore
import com.ytstyle.bili.data.settings.ThemeMode
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
) : ViewModel() {

    /**
     * Values are read straight from DataStore rather than mirrored into a
     * MutableStateFlow, so the activity and this screen cannot drift apart.
     */
    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.Light)

    val danmakuEnabled: StateFlow<Boolean> = settings.danmakuEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val autoplay: StateFlow<Boolean> = settings.autoplay
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val keepPlayerLandscape: StateFlow<Boolean> = settings.keepPlayerLandscape
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    // ---- experimental ----

    val homeGrid: StateFlow<Boolean> = settings.homeGrid
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ---- account ----

    val isLoggedIn: StateFlow<Boolean> = session.cookieFlow
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setThemeMode(mode: ThemeMode) = launch { settings.setThemeMode(mode) }

    fun setDanmakuEnabled(enabled: Boolean) = launch { settings.setDanmakuEnabled(enabled) }

    fun setAutoplay(enabled: Boolean) = launch { settings.setAutoplay(enabled) }

    fun setKeepPlayerLandscape(enabled: Boolean) =
        launch { settings.setKeepPlayerLandscape(enabled) }

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
