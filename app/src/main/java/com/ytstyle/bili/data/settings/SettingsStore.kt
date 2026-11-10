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

/**
 * When the player is allowed to start on its own.
 *
 * This replaced a plain on/off switch: "autoplay" is really a question about
 * the *link*, and the answer people actually want is usually "yes at home, no
 * on mobile data".
 */
enum class AutoplayMode(val id: String, val label: String, val detail: String) {
    Always(
        "always",
        "Wi-Fi/移动网络下自动播放",
        "任何网络下进入播放页都直接开始",
    ),
    WifiOnly(
        "wifi",
        "仅 Wi-Fi 下自动播放",
        "移动网络下进入播放页不会自动开始，避免消耗流量",
    ),
    Never(
        "never",
        "关闭自动播放",
        "任何网络下都需要手动点播放",
    );

    companion object {
        /** Defaults to [Always], matching the behaviour before this option existed. */
        fun fromId(id: String?): AutoplayMode = entries.firstOrNull { it.id == id } ?: Always
    }
}

/**
 * Preferred audio track.
 *
 * `id` is Bilibili's own audio id; [Auto] means "take the highest bitrate the
 * video offers", which is what the player did before this preference existed.
 */
enum class AudioPreference(val id: Int, val label: String) {
    Auto(0, "自动（最高音质）"),
    K192(30280, "192K"),
    K132(30232, "132K"),
    K64(30216, "64K");

    companion object {
        fun fromId(id: Int?): AudioPreference = entries.firstOrNull { it.id == id } ?: Auto
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

        /**
         * Read-only, kept so an install that predates [AutoplayMode] does not
         * silently reset the user's choice.
         *
         * It deliberately keeps its own key name: `Preferences.Key` compares by
         * name alone, so reading a Boolean that was stored under a key later
         * re-declared as a String would throw a `ClassCastException` rather
         * than return null.
         */
        val AUTOPLAY_LEGACY = booleanPreferencesKey("autoplay")
        val AUTOPLAY_MODE = stringPreferencesKey("autoplay_mode")

        // Danmaku appearance.
        val DANMAKU_SCALE = stringPreferencesKey("danmaku_scale")
        val DANMAKU_OPACITY = stringPreferencesKey("danmaku_opacity")
        val DANMAKU_AREA = stringPreferencesKey("danmaku_area")
        val DANMAKU_SPEED = stringPreferencesKey("danmaku_speed")
        val DANMAKU_TYPES = stringPreferencesKey("danmaku_types")

        // Experimental.
        val HOME_GRID = booleanPreferencesKey("home_grid")

        // 音画设置: the quality the player asks for when it opens a video, and
        // the audio track it prefers. Stored as strings so a future tier can be
        // added without a migration.
        val DEFAULT_QUALITY = stringPreferencesKey("default_quality")
        val AUDIO_PREFERENCE = stringPreferencesKey("audio_preference")

        // 应用内更新: whether to check on launch.
        val AUTO_UPDATE = booleanPreferencesKey("auto_update")
    }

    /**
     * Defaults to [ThemeMode.Light] rather than the system value: the app
     * historically shipped dark, and the requested default is now light.
     */
    val themeMode: Flow<ThemeMode> =
        context.settingsDataStore.data.map { ThemeMode.fromId(it[Keys.THEME]) }

    val danmakuEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.DANMAKU_ENABLED] ?: true }

    /**
     * Autoplay policy, migrating the old on/off flag on read: a stored `true`
     * becomes [AutoplayMode.Always], a stored `false` becomes
     * [AutoplayMode.Never], and an untouched install gets the default.
     */
    val autoplayMode: Flow<AutoplayMode> =
        context.settingsDataStore.data.map { prefs ->
            prefs[Keys.AUTOPLAY_MODE]?.let { AutoplayMode.fromId(it) }
                ?: when (prefs[Keys.AUTOPLAY_LEGACY]) {
                    true -> AutoplayMode.Always
                    false -> AutoplayMode.Never
                    null -> AutoplayMode.Always
                }
        }

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

    /** Default `qn` for playback; 80 is Bilibili's 1080P. */
    val defaultQuality: Flow<Int> =
        context.settingsDataStore.data.map { (it[Keys.DEFAULT_QUALITY] ?: "80").toIntOrNull() ?: 80 }

    /** Preferred audio track id; 0 means "highest available". */
    val audioPreference: Flow<AudioPreference> =
        context.settingsDataStore.data.map {
            AudioPreference.fromId(it[Keys.AUDIO_PREFERENCE]?.toIntOrNull())
        }

    suspend fun setDefaultQuality(qn: Int) {
        context.settingsDataStore.edit { it[Keys.DEFAULT_QUALITY] = qn.toString() }
    }

    suspend fun setAudioPreference(preference: AudioPreference) {
        context.settingsDataStore.edit { it[Keys.AUDIO_PREFERENCE] = preference.id.toString() }
    }

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

    suspend fun setAutoplayMode(mode: AutoplayMode) {
        context.settingsDataStore.edit { it[Keys.AUTOPLAY_MODE] = mode.id }
    }

    /** Whether the app checks for updates on its own at launch. */
    val autoUpdate: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.AUTO_UPDATE] ?: true }

    suspend fun setAutoUpdate(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_UPDATE] = enabled }
    }
}
