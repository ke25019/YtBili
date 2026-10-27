package com.ytstyle.bili.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ytstyle.bili.ui.theme.AppTheme

/**
 * YouTube's bar has no pill indicator and no background elevation — just the
 * icon, a small label, and the icon filling in when selected. Material 3's
 * default NavigationBarItem adds a coloured pill, so it is stripped out here.
 */
@Composable
fun YtBottomBar(
    current: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().background(AppTheme.colors.background)) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AppTheme.colors.divider),
        )
        NavigationBar(
            containerColor = AppTheme.colors.background,
            tonalElevation = 0.dp,
            modifier = Modifier.navigationBarsPadding(),
        ) {
            TopLevelDestination.entries.forEach { destination ->
                val selected = destination == current
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(destination) },
                    icon = {
                        Icon(
                            imageVector = if (selected) {
                                destination.selectedIcon
                            } else {
                                destination.unselectedIcon
                            },
                            contentDescription = destination.label,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    label = {
                        Text(
                            text = destination.label,
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AppTheme.colors.onBackground,
                        selectedTextColor = AppTheme.colors.onBackground,
                        unselectedIconColor = AppTheme.colors.onBackgroundSecondary,
                        unselectedTextColor = AppTheme.colors.onBackgroundSecondary,
                        indicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}
