package com.ytstyle.bili

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ytstyle.bili.ui.YtBiliRoot
import com.ytstyle.bili.ui.settings.SettingsViewModel
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtBiliTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // The theme mode lives in the settings store, so the whole activity
            // repaints when the user switches between light and dark.
            val settings: SettingsViewModel = hiltViewModel()
            val themeMode by settings.themeMode.collectAsStateWithLifecycle()

            YtBiliTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize().background(AppTheme.colors.background),
                    color = AppTheme.colors.background,
                ) {
                    YtBiliRoot()
                }
            }
        }
    }
}
