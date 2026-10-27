package com.ytstyle.bili.ui.live

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ytstyle.bili.ui.components.formatCount
import com.ytstyle.bili.ui.components.toSecureUrl
import com.ytstyle.bili.ui.player.MEDIA_USER_AGENT
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.YtColors

/**
 * A live room: stream player plus room metadata.
 *
 * Live streams need no session cookie (they are public), but the CDN still
 * expects a desktop UA and a Referer on some edges, so the same header set as
 * on-demand playback is used.
 */
@OptIn(UnstableApi::class)
@Composable
fun LiveRoomScreen(
    onBack: () -> Unit,
    viewModel: LiveViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    // Rebuild the source when the resolved URL changes (or after a retry).
    LaunchedEffect(state.playUrl, state.isHls) {
        if (state.playUrl.isEmpty()) return@LaunchedEffect

        val headers = buildMap {
            put("User-Agent", MEDIA_USER_AGENT)
            put("Referer", "https://live.bilibili.com/")
            put("Origin", "https://live.bilibili.com")
        }
        val factory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)

        val source = if (state.isHls) {
            HlsMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(state.playUrl))
        } else {
            ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(state.playUrl))
        }

        exoPlayer.setMediaSource(source)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        Box(modifier = Modifier.fillMaxWidth().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )

            IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }

            if (state.isLoading) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = YtColors.Red)
                }
            }
        }

        RoomInfo(state = state)

        state.error?.let { message ->
            Text(
                text = message,
                color = YtColors.Red,
                fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun RoomInfo(state: LiveUiState) {
    val room = state.room ?: return

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(YtColors.Red)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (room.liveStatus == 1) "直播中" else "未开播",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatCount(room.online) + "人气",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = room.title,
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = listOf(room.parentAreaName, room.areaName)
                .filter { it.isNotEmpty() }
                .joinToString(" · "),
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(AppTheme.colors.surfaceElevated),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = room.tags.ifEmpty { room.description.take(40) },
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
