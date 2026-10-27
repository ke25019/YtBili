package com.ytstyle.bili.ui.account

import com.ytstyle.bili.ui.theme.AppTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ytstyle.bili.ui.theme.VipPink
import com.ytstyle.bili.ui.theme.YtColors

/**
 * The "我的" tab, restructured the way YouTube's "You" page is: profile header,
 * then a plain list of destinations.
 *
 * Deliberately absent: 会员购 (the Bilibili merch store), 课堂, 漫画, 游戏中心 and
 * the rest of the domestic client's commercial entries. Only the membership
 * centre is kept, per the requested scope.
 */
@Composable
fun AccountScreen(
    onLoginClick: () -> Unit,
    onVipClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onUploadClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    onWatchLaterClick: () -> Unit = {},
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            kotlinx.coroutines.delay(4000)
            viewModel.dismissNotice()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .verticalScroll(rememberScrollState()),
    ) {
        ProfileHeader(
            state = state,
            onLoginClick = onLoginClick,
        )

        if (state.isLoggedIn) {
            Spacer(Modifier.height(8.dp))
            StatsRow(following = state.following, followers = state.followers)
            Spacer(Modifier.height(8.dp))
            ExpBar(level = state.level, current = state.currentExp, next = state.nextExp)
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()

        AccountRow(
            icon = Icons.Filled.WorkspacePremium,
            title = if (state.isVip) "大会员中心" else "开通大会员",
            subtitle = state.vipLabel.ifEmpty { "会员权益 · 卡券 · 订单" },
            tint = VipPink,
            onClick = onVipClick,
        )
        AccountRow(
            icon = Icons.Filled.History,
            title = "观看记录",
            subtitle = "继续观看与历史",
            onClick = onHistoryClick,
        )
        AccountRow(
            icon = Icons.Filled.Star,
            title = "我的收藏",
            subtitle = "收藏夹与订阅",
            onClick = onFavoritesClick,
        )
        AccountRow(
            icon = Icons.Filled.WatchLater,
            title = "稍后再看",
            subtitle = "待观看列表",
            onClick = onWatchLaterClick,
        )
        AccountRow(
            icon = Icons.Filled.CloudUpload,
            title = "投稿管理",
            subtitle = "在网页中上传视频与稿件",
            onClick = onUploadClick,
        )
        AccountRow(
            icon = Icons.Filled.Settings,
            title = "设置",
            subtitle = "主题、播放偏好与关于",
            onClick = onSettingsClick,
        )

        state.notice?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProfileHeader(state: AccountUiState, onLoginClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.surfaceElevated)
                .clickable(enabled = !state.isLoggedIn, onClick = onLoginClick),
        ) {
            if (state.avatarUrl.isNotEmpty()) {
                AsyncImage(
                    model = state.avatarUrl,
                    contentDescription = state.uname,
                    modifier = Modifier.size(64.dp).clip(CircleShape),
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            if (state.isLoggedIn) {
                Text(
                    text = state.uname,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppTheme.colors.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "@${state.mid} · Lv${state.level}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onBackgroundSecondary,
                )
            } else {
                Text(
                    text = "未登录",
                    style = MaterialTheme.typography.titleLarge,
                    color = AppTheme.colors.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "点击登录，同步收藏与历史",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onBackgroundSecondary,
                )
            }
        }

        if (!state.isLoggedIn) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(YtColors.Red)
                    .clickable(onClick = onLoginClick)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("登录", color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun StatsRow(following: Int, followers: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        StatItem(following, "关注")
        StatItem(followers, "粉丝")
    }
}

@Composable
private fun StatItem(value: Int, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = value.toString(),
            color = AppTheme.colors.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 12.sp,
        )
    }
}

/** Bilibili's level/experience bar, restyled to YouTube's flat progress look. */
@Composable
private fun ExpBar(level: Int, current: Int, next: Int) {
    val progress = if (next > 0) (current.toFloat() / next).coerceIn(0f, 1f) else 0f
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Lv$level",
                color = YtColors.Red,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$current / $next",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AppTheme.colors.surfaceElevated),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(3.dp)
                    .background(YtColors.Red),
            )
        }
    }
}

@Composable
private fun HorizontalDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppTheme.colors.divider),
    )
}

@Composable
private fun AccountRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color = AppTheme.colors.onBackground,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = AppTheme.colors.onBackground,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onBackgroundSecondary,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = AppTheme.colors.onBackgroundSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}
