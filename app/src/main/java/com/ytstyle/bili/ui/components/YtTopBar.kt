package com.ytstyle.bili.ui.components

import com.ytstyle.bili.ui.theme.AppTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ytstyle.bili.ui.theme.YtColors

/**
 * The YouTube wordmark: "You" in white, "Tube" in red, tight tracking.
 * Rendered as text rather than an asset so it scales with the theme and
 * carries no trademarked image into the build.
 */
@Composable
fun YtWordmark(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "You",
            color = AppTheme.colors.onBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        )
        Text(
            text = "Bili",
            color = YtColors.Red,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        )
    }
}

@Composable
fun YtTopBar(
    avatarUrl: String,
    onSearchClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onNotificationsClick: () -> Unit = {},
    onCastClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AppTheme.colors.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        YtWordmark()
        Spacer(Modifier.weight(1f))

        IconButton(onClick = onCastClick) {
            Icon(
                imageVector = Icons.Filled.Cast,
                contentDescription = "投屏",
                tint = AppTheme.colors.onBackground,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onNotificationsClick) {
            Icon(
                imageVector = Icons.Filled.NotificationsNone,
                contentDescription = "通知",
                tint = AppTheme.colors.onBackground,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onSearchClick) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "搜索",
                tint = AppTheme.colors.onBackground,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.width(4.dp))

        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.surfaceElevated)
                .clickable(onClick = onAvatarClick),
        ) {
            if (avatarUrl.isNotEmpty()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = "账户",
                    modifier = Modifier.size(28.dp).clip(CircleShape),
                )
            }
        }
    }
}
