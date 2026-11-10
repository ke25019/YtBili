package com.ytstyle.bili.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/** Which tint the system bar icons need so they stay visible on what is behind them. */
enum class SystemBarIcons {
    /** White icons — for a dark picture or banner under the status bar. */
    Light,

    /** Dark icons — for a light page background. */
    Dark,
}

/**
 * Overrides the status bar icon tint for the current screen.
 *
 * `null` is the default and means "follow the app palette", which is right for
 * every screen whose top area is the page background. A screen that puts a
 * picture under the status bar (the space page's banner) sets [SystemBarIcons.Light]
 * so the clock and battery do not disappear into the artwork — that is the
 * "随画面颜色自动变化" behaviour: the choice follows the content, not the theme.
 */
val LocalSystemBarIcons = staticCompositionLocalOf<SystemBarIcons?> { null }
