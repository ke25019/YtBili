package com.ytstyle.bili

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytstyle.bili.ui.PipState
import com.ytstyle.bili.ui.YtBiliRoot
import com.ytstyle.bili.ui.components.SystemBarIcons
import com.ytstyle.bili.ui.components.SystemBarsState
import com.ytstyle.bili.ui.settings.SettingsViewModel
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtBiliTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Fully transparent system bars, on purpose.
        //
        // The plain `enableEdgeToEdge()` call uses `SystemBarStyle.auto` with the
        // library's scrims, and Android *also* enforces a contrast scrim behind a
        // 3-button navigation bar (`isNavigationBarContrastEnforced`, on by
        // default since API 29). Between the two, the navigation strip came out as
        // an opaque grey band and the status bar as a light strip, instead of the
        // page colour showing through. Both are disabled here.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            // The theme mode lives in the settings store, so the whole activity
            // repaints when the user switches between light and dark.
            val settings: SettingsViewModel = hiltViewModel()
            val themeMode by settings.themeMode.collectAsStateWithLifecycle()

            YtBiliTheme(themeMode = themeMode) {
                /**
                 * Status/navigation bar icons must follow the **app's** palette,
                 * not the system's, unless the screen under the bar is not the
                 * page background.
                 *
                 * The whole window configuration is written in exactly one place —
                 * here. Screens publish what they want through [SystemBarsState]
                 * and never touch the insets controller themselves, which is what
                 * stops a player's restore from leaving white icons on a white
                 * page after it closes.
                 */
                val dark = AppTheme.colors.background.luminance() < 0.5f
                val lightIcons = when (SystemBarsState.icons) {
                    SystemBarIcons.Light -> true
                    SystemBarIcons.Dark -> false
                    null -> dark
                }
                val barsHidden = SystemBarsState.hidden

                val view = LocalView.current
                val lifecycleOwner = LocalLifecycleOwner.current
                val window = (view.context as? Activity)?.window

                /** One definition of "correct", used by every trigger below. */
                fun applyBars() {
                    val target = window ?: return
                    WindowCompat.getInsetsController(target, view).apply {
                        // `isAppearanceLight*` means *dark icons* on a light bar,
                        // so white icons need the flag off.
                        isAppearanceLightStatusBars = !lightIcons
                        isAppearanceLightNavigationBars = !lightIcons
                        if (barsHidden) {
                            systemBarsBehavior =
                                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            hide(WindowInsetsCompat.Type.systemBars())
                        } else {
                            show(WindowInsetsCompat.Type.systemBars())
                        }
                    }
                }

                // Re-applied whenever the theme, an override or the visibility
                // changes — including the moment the player leaves fullscreen.
                SideEffect { applyBars() }

                // Coming back from another activity or from the background can
                // reset the window flags on some OEM builds; re-assert them.
                DisposableEffect(lifecycleOwner, lightIcons, barsHidden) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) applyBars()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                Surface(
                    modifier = Modifier.fillMaxSize().background(AppTheme.colors.background),
                    color = AppTheme.colors.background,
                ) {
                    YtBiliRoot()
                }
            }
        }
    }

    /**
     * Picture-in-picture is a window mode of the activity, so this is the only
     * place that can observe it. The player reads [PipState] to hide every
     * overlay while the window is small.
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.isInPip = isInPictureInPictureMode
    }
}
