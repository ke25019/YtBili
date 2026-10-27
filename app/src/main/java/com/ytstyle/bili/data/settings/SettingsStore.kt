package com.ytstyle.bili.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "ytbili_settings")

/** How the app decides between the light and dark palettes. */
enum class ThemeMode(val id: String, val label: String) {
    /** Follow the system setting. */
    System("system", "跟随系统"),

    /** Always light — this is the default. */
    Light("light", "浅色"),

    /** Always dark. */
    Dark("dark", "深色");

    companion object {
        fun fromId(id: String?): ThemeMode =
            entries.firstOrNull { it.id == id } ?: Light
    }
}

/** Danmaku on/off defaults to on, but is remembered across sessions. */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DANMAKU_ENABLED = booleanPreferencesKey("danmaku_enabled")
        val AUTOPLAY = booleanPreferencesKey("autoplay")

        // Danmaku appearance.
        val DANMAKU_SCALE = stringPreferencesKey("danmaku_scale")
        val DANMAKU_OPACITY = stringPreferencesKey("danmaku_opacity")
        val DANMAKU_AREA = stringPreferencesKey("danmaku_area")
        val DANMAKU_SPEED = stringPreferencesKey("danmaku_speed")
        val DANMAKU_TYPES = stringPreferencesKey("danmaku_types")

        // Experimental.
        val HOME_GRID = booleanPreferencesKey("home_grid")
        val KEEP_PLAYER_LANDSCAPE = booleanPreferencesKey("keep_player_landscape")
    }

    /**
     * Defaults to [ThemeMode.Light] rather than the system value: the app
     * historically shipped dark, and the requested default is now light.
     */
    val themeMode: Flow<ThemeMode> =
        context.settingsDataStore.data.map { ThemeMode.fromId(it[Keys.THEME]) }

    val danmakuEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.DANMAKU_ENABLED] ?: true }

    val autoplay: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.AUTOPLAY] ?: true }

    /** Danmaku font scale, 0.5x – 1.5x. */
    val danmakuScale: Flow<Float> =
        context.settingsDataStore.data.map { (it[Keys.DANMAKU_SCALE] ?: "1.0").toFloatOrNull() ?: 1f }

    /** Danmaku opacity, 0.3 – 1.0. */
    val danmakuOpacity: Flow<Float> =
        context.settingsDataStore.data.map { (it[Keys.DANMAKU_OPACITY] ?: "1.0").toFloatOrNull() ?: 1f }

    /** Fraction of the player height danmaku may occupy, 0.25 – 1.0. */
    val danmakuArea: Flow<Float> =
        context.settingsDataStore.data.map { (it[Keys.DANMAKU_AREA] ?: "1.0").toFloatOrNull() ?: 1f }

    /** Danmaku scroll duration in seconds; larger is slower. */
    val danmakuSpeed: Flow<Float> =
        context.settingsDataStore.data.map { (it[Keys.DANMAKU_SPEED] ?: "8").toFloatOrNull() ?: 8f }

    /**
     * Which danmaku modes to show, as a set of mode numbers
     * (1 = scrolling, 4 = bottom, 5 = top).
     */
    val danmakuTypes: Flow<Set<Int>> =
        context.settingsDataStore.data.map { prefs ->
            (prefs[Keys.DANMAKU_TYPES] ?: "1,4,5")
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet()
        }

    /** Experimental: two-column home feed. */
    val homeGrid: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.HOME_GRID] ?: false }

    /** Keep the player in landscape after fullscreen is toggled off. */
    val keepPlayerLandscape: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.KEEP_PLAYER_LANDSCAPE] ?: false }

    suspend fun setDanmakuScale(value: Float) {
        context.settingsDataStore.edit { it[Keys.DANMAKU_SCALE] = value.toString() }
    }

    suspend fun setDanmakuOpacity(value: Float) {
        context.settingsDataStore.edit { it[Keys.DANMAKU_OPACITY] = value.toString() }
    }

    suspend fun setDanmakuArea(value: Float) {
        context.settingsDataStore.edit { it[Keys.DANMAKU_AREA] = value.toString() }
    }

    suspend fun setDanmakuSpeed(value: Float) {
        context.settingsDataStore.edit { it[Keys.DANMAKU_SPEED] = value.toString() }
    }

    suspend fun setDanmakuTypes(modes: Set<Int>) {
        context.settingsDataStore.edit { it[Keys.DANMAKU_TYPES] = modes.sorted().joinToString(",") }
    }

    suspend fun setHomeGrid(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.HOME_GRID] = enabled }
    }

    suspend fun setKeepPlayerLandscape(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.KEEP_PLAYER_LANDSCAPE] = enabled }
    }

    /** Wipes every preference. Used when switching accounts without keeping data. */
    suspend fun clear() {
        context.settingsDataStore.edit { it.clear() }
    }

    suspend fun currentThemeMode(): ThemeMode = themeMode.first()

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME] = mode.id }
    }

    suspend fun setDanmakuEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DANMAKU_ENABLED] = enabled }
    }

    suspend fun setAutoplay(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTOPLAY] = enabled }
    }
}
