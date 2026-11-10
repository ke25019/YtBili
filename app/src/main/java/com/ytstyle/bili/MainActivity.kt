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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytstyle.bili.ui.PipState
import com.ytstyle.bili.ui.YtBiliRoot
import com.ytstyle.bili.ui.components.LocalSystemBarIcons
import com.ytstyle.bili.ui.components.SystemBarIcons
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
        // page colour showing through. Both are disabled here and the icon tint is
        // still driven by the app palette below.
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
                 * not the system's.
                 *
                 * `enableEdgeToEdge()` picks the icon tint from the system dark
                 * setting, but this app can be dark while the system is light (or
                 * the reverse). The mismatch drew dark icons on a dark bar, which
                 * reads as the clock and battery simply disappearing.
                 */
                val dark = AppTheme.colors.background.luminance() < 0.5f
                // A screen may override the tint when whatever sits behind the
                // status bar is not the page background — the space page puts a
                // banner there, and dark icons on artwork vanish.
                val override = LocalSystemBarIcons.current
                val lightIcons = when (override) {
                    SystemBarIcons.Light -> true
                    SystemBarIcons.Dark -> false
                    null -> dark
                }
                val view = LocalView.current
                SideEffect {
                    val window = (view.context as? Activity)?.window ?: return@SideEffect
                    WindowCompat.getInsetsController(window, view).apply {
                        // `isAppearanceLight*` means *dark icons* on a light bar,
                        // so white icons need the flag off.
                        isAppearanceLightStatusBars = !lightIcons
                        isAppearanceLightNavigationBars = !lightIcons
                    }
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
