package com.ytstyle.bili.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * YouTube-accurate palette. The point of this client is that it should read as
 * YouTube at a glance, so the greys and the red are taken from YouTube's own
 * dark theme rather than picked by eye.
 */
object YtColors {
    /** YouTube brand red — used for the logo, selected tabs and progress bars. */
    val Red = Color(0xFFFF0000)
    val RedDark = Color(0xFFCC0000)

    /** YouTube dark-theme surfaces, from deepest to lightest. */
    val Background = Color(0xFF0F0F0F)
    val Surface = Color(0xFF0F0F0F)
    val SurfaceElevated = Color(0xFF212121)
    val ChipBackground = Color(0xFF272727)
    val ChipSelected = Color(0xFFFFFFFF)

    val OnBackground = Color(0xFFF1F1F1)
    val OnBackgroundSecondary = Color(0xFFAAAAAA)
    val Divider = Color(0xFF303030)

    val LikeActive = Color(0xFFFFFFFF)
    val CoinColor = Color(0xFFF5A623)
    val FavColor = Color(0xFFF5A623)

    /** Danmaku text uses a light stroke so it stays legible over any frame. */
    val DanmakuText = Color(0xFFFFFFFF)
    val DanmakuStroke = Color(0xCC000000)

    // ---- light palette ----
    // YouTube's own light theme, so the two modes stay recognisably related.
    val LightBackground = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceElevated = Color(0xFFF2F2F2)
    val LightOnBackground = Color(0xFF0F0F0F)
    val LightOnBackgroundSecondary = Color(0xFF606060)
    val LightDivider = Color(0xFFE5E5E5)
}

/** Bilibili VIP brand pink, used only in the membership centre. */
val VipPink = Color(0xFFFF6699)
