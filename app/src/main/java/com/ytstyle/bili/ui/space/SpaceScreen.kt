package com.ytstyle.bili.ui.space

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ytstyle.bili.ui.components.VideoRow
import com.ytstyle.bili.ui.components.formatCount
import com.ytstyle.bili.ui.components.parseDurationString
import com.ytstyle.bili.ui.components.toSecureUrl
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.VipPink
import com.ytstyle.bili.ui.theme.YtColors

/**
 * A user's space: profile header, counters, live indicator and their uploads.
 *
 * Reached from the player (tapping the uploader row) and from user search
 * results.
 */
@Composable
fun SpaceScreen(
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,
    onLiveClick: (Long) -> Unit,
    viewModel: SpaceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            kotlinx.coroutines.delay(2000)
            viewModel.dismissNotice()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AppTheme.colors.onBackground,
                )
            }
            Text(
                text = state.profile?.name.orEmpty().ifEmpty { "个人主页" },
                style = MaterialTheme.typography.titleMedium,
                color = AppTheme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = YtColors.Red)
            }

            state.profile == null -> Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error ?: "无法加载该用户",
                    color = AppTheme.colors.onBackgroundSecondary,
                )
            }

            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    ProfileHeader(
                        state = state,
                        onToggleFollow = viewModel::toggleFollow,
                        onLiveClick = {
                            val room = viewModel.roomId()
                            if (room > 0) onLiveClick(room)
                        },
                        isLive = viewModel.isLive(),
                    )
                }
                item {
                    HorizontalDivider(color = AppTheme.colors.divider)
                    Text(
                        text = "投稿 ${state.videos.size}",
                        color = AppTheme.colors.onBackground,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(state.videos, key = { it.bvid }) { video ->
                    VideoRow(
                        title = video.title,
                        thumbnailUrl = video.pic,
                        uploaderName = state.profile?.name.orEmpty(),
                        viewCount = video.play,
                        durationSeconds = parseDurationString(video.length),
                        onClick = { onVideoClick(video.bvid) },
                    )
                }
                if (state.videos.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "没有公开的投稿",
                                color = AppTheme.colors.onBackgroundSecondary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    state: SpaceUiState,
    onToggleFollow: () -> Unit,
    onLiveClick: () -> Unit,
    isLive: Boolean,
) {
    val profile = state.profile ?: return

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = profile.face.toSecureUrl(),
                contentDescription = profile.name,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(AppTheme.colors.surfaceElevated),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = AppTheme.colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if ((profile.official?.type ?: -1) >= 0) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.Verified,
                            contentDescription = profile.official?.desc,
                            tint = YtColors.Red,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Lv${profile.level}",
                        color = YtColors.Red,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if ((profile.vip?.status ?: 0) == 1) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = profile.vip?.label?.text.orEmpty().ifEmpty { "大会员" },
                            color = VipPink,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = profile.sex.ifEmpty { "" },
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        if (profile.sign.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            // No nested verticalScroll here: this composable is already inside
            // a LazyColumn item, and nesting same-axis scrolling crashes with
            // an infinite-constraints measurement error.
            Text(
                text = profile.sign,
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            StatItem(state.following, "关注")
            StatItem(state.followers, "粉丝")
            StatItem(profile.coins.toLong(), "硬币")
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The API rejects following yourself, so the button is omitted
            // rather than shown and then failing.
            if (!state.isSelf) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (state.isFollowing) {
                                AppTheme.colors.chipBackground
                            } else {
                                YtColors.Red
                            },
                        )
                        .clickable(onClick = onToggleFollow)
                        .padding(horizontal = 22.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = if (state.isFollowing) "已关注" else "关注",
                        color = if (state.isFollowing) {
                            AppTheme.colors.onBackground
                        } else {
                            Color.White
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            if (isLive) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(YtColors.Red)
                        .clickable(onClick = onLiveClick)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.LiveTv,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "直播中",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        state.notice?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(text = message, color = YtColors.Red, fontSize = 12.sp)
        }

        state.error?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(text = message, color = YtColors.Red, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StatItem(value: Long, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = formatCount(value),
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
