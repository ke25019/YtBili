package com.ytstyle.bili.ui.player

import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Calls [onUpright] once the device itself is held upright again.
 *
 * Why this exists: "exit landscape" used to be implemented by pinning the
 * activity to portrait and then releasing the pin as soon as the *window*
 * reported portrait. With auto-rotate on, that release happens while the phone
 * is still lying on its side, so the sensor immediately turns it back to
 * landscape — the exit arrow looked like it did nothing.
 *
 * The pin is therefore released on the device's physical orientation (within
 * [UPRIGHT_TOLERANCE] degrees of upright), not on the window's.
 */
@Composable
fun OrientationUprightEffect(enabled: Boolean, onUpright: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(enabled, context) {
        if (!enabled) return@DisposableEffect onDispose { }
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                // ORIENTATION_UNKNOWN means the sensor cannot tell; staying
                // pinned is the safer failure mode.
                if (orientation == ORIENTATION_UNKNOWN) return
                if (orientation <= UPRIGHT_TOLERANCE || orientation >= 360 - UPRIGHT_TOLERANCE) {
                    onUpright()
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable() else listener.disable()
        onDispose { listener.disable() }
    }
}

/** Degrees either side of upright that still count as "held upright". */
private const val UPRIGHT_TOLERANCE = 45
