package com.ytstyle.bili.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the current screen needs from the system bars.
 *
 * This is process-wide state rather than a `CompositionLocal` because the
 * consumer is the **activity**, which sits *above* every screen in the
 * composition tree: a value provided by a screen can never reach it. That is why
 * the space page's "icons follow the banner" override used to have no effect at
 * all — it was provided below the only place that read it.
 *
 * [MainActivity] is the single writer of the window: it applies both the icon
 * tint and the visibility from these two values, and re-applies them whenever
 * they change or the activity resumes. Screens only state what they want.
 */
object SystemBarsState {

    /** True while a screen wants the bars hidden — the player in fullscreen. */
    var hidden by mutableStateOf(false)

    /**
     * Overrides the palette-derived icon tint; `null` means "follow the theme".
     *
     * Set to [SystemBarIcons.Light] by screens whose top strip is a dark picture
     * (the player, the space banner), so the clock does not vanish into it.
     */
    var icons by mutableStateOf<SystemBarIcons?>(null)
}
