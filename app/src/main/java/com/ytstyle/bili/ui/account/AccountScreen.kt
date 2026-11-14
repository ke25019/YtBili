package com.ytstyle.bili.ui.account

import com.ytstyle.bili.ui.theme.AppTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Download
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
import com.ytstyle.bili.ui.relation.RelationTab
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ytstyle.bili.ui.components.toThumbUrl
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
/**
 * The account tab.
 *
 * It takes the shell's [AccountUiState] rather than resolving a view model of
 * its own: a second instance scoped to this navigation entry was why this
 * screen and the top bar could disagree about whether anybody was signed in.
 */
@Composable
fun AccountScreen(
    state: AccountUiState,
    onDismissNotice: () -> Unit,
    onLoginClick: () -> Unit,
    onVipClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onUploadClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    onWatchLaterClick: () -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    /** Opens one's own space; the avatar and the name both lead there. */
    onOpenSpace: (Long) -> Unit = {},
    /** Opens the 粉丝 / 关注 second-level list for one's own mid. */
    onOpenRelation: (Long, RelationTab) -> Unit = { _, _ -> },
) {
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            kotlinx.coroutines.delay(4000)
            onDismissNotice()
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
            onOpenSpace = { if (state.mid > 0) onOpenSpace(state.mid) },
        )

        if (state.isLoggedIn) {
            Spacer(Modifier.height(8.dp))
            StatsRow(
                following = state.following,
                followers = state.followers,
                // Both counts open the same list the space page shows; they used
                // to be inert text.
                onFollowingClick = { if (state.mid > 0) onOpenRelation(state.mid, RelationTab.Following) },
                onFollowersClick = { if (state.mid > 0) onOpenRelation(state.mid, RelationTab.Fans) },
            )
            Spacer(Modifier.height(8.dp))
            ExpBar(level = state.level, current = state.currentExp, next = state.nextExp)
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()

        // 观看记录 / 我的收藏 / 稍后再看 / 离线缓存 are one 资源库 section now,
        // drawn as large cover cards rather than four identical text rows.
        LibrarySection(
            covers = state.libraryCovers,
            onOpen = { entry ->
                when (entry) {
                    LibraryEntry.History -> onHistoryClick()
                    LibraryEntry.Favorites -> onFavoritesClick()
                    LibraryEntry.WatchLater -> onWatchLaterClick()
                    LibraryEntry.Downloads -> onDownloadsClick()
                }
            },
        )

        // The 大会员 entry was removed on request. `onVipClick` and the VIP
        // route are kept so the screen stays reachable if it is ever wired back
        // (and so nothing else has to change to restore the row).
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

/**
 * 资源库: the four local lists, as large cover cards.
 *
 * Laid out as two explicit rows rather than a lazy grid — a second lazy
 * container inside this screen's `verticalScroll` column would be measured with
 * infinite height and crash.
 */
@Composable
private fun LibrarySection(
    covers: Map<LibraryEntry, String>,
    onOpen: (LibraryEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = "资源库",
            color = AppTheme.colors.onBackground,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        LibraryEntry.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { entry ->
                    LibraryCard(
                        entry = entry,
                        cover = covers[entry].orEmpty(),
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(entry) },
                    )
                }
                // Keeps a lone card half-width instead of stretching it.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun LibraryCard(
    entry: LibraryEntry,
    cover: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.surfaceElevated)
            .clickable(onClick = onClick),
    ) {
        if (cover.isNotBlank()) {
            AsyncImage(
                model = cover.toThumbUrl(),
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // No cover known for this entry yet — an icon panel is honest about
            // that in a way a blank grey rectangle is not.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = null,
                    tint = YtColors.Red,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))),
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(
                text = entry.title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = entry.subtitle,
                color = Color(0xCCFFFFFF),
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}

/** The icon each 资源库 card falls back to when it has no cover. */
private val LibraryEntry.icon: ImageVector
    get() = when (this) {
        LibraryEntry.History -> Icons.Filled.History
        LibraryEntry.Favorites -> Icons.Filled.Star
        LibraryEntry.WatchLater -> Icons.Filled.WatchLater
        LibraryEntry.Downloads -> Icons.Filled.Download
    }

@Composable
private fun ProfileHeader(
    state: AccountUiState,
    onLoginClick: () -> Unit,
    onOpenSpace: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            // Signed in, this opens one's own space; signed out it still starts
            // the login flow, which is the only useful action either way.
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.surfaceElevated)
                .clickable(
                    onClick = { if (state.isLoggedIn) onOpenSpace() else onLoginClick() },
                ),
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

        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = state.isLoggedIn, onClick = onOpenSpace),
        ) {
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
private fun StatsRow(
    following: Int,
    followers: Int,
    onFollowingClick: () -> Unit,
    onFollowersClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        StatItem(following, "关注", onFollowingClick)
        StatItem(followers, "粉丝", onFollowersClick)
    }
}

@Composable
private fun StatItem(value: Int, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
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
