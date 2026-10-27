package com.ytstyle.bili.ui.player

import com.ytstyle.bili.ui.theme.AppTheme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ytstyle.bili.ui.theme.YtColors

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onUploaderClick: (Long) -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The player must outlive recomposition and be released with the screen.
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var isFullscreen by remember { mutableStateOf(false) }
    /** width / height of the decoded video; null until the first frame. */
    var videoAspect by remember { mutableStateOf<Float?>(null) }
    /** Long-press to speed up, released to return to 1x. */
    var isSpeedBoosted by remember { mutableStateOf(false) }
    /** Manual landscape lock, independent of fullscreen. */
    var isLandscapeLocked by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val danmakuConfig = state.danmakuConfig

    val activity = context.findActivity()
    val supportsPip = remember(activity) {
        activity?.packageManager?.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE,
        ) == true
    }

    /** Manual landscape lock. */
    DisposableEffect(isLandscapeLocked) {
        activity?.requestedOrientation = if (isLandscapeLocked) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else if (!isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose { }
    }

    /**
     * Leaving the app must not leave audio playing.
     *
     * Audio-only mode is exempt on purpose: that mode exists to keep playing
     * with the screen off, so it is allowed to continue in the background.
     */
    DisposableEffect(lifecycleOwner, state.audioOnly) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !state.audioOnly) {
                exoPlayer.playWhenReady = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Playback speed follows the long-press state.
    LaunchedEffect(isSpeedBoosted) {
        exoPlayer.setPlaybackSpeed(if (isSpeedBoosted) SPEED_BOOST else 1f)
    }

    // Fullscreen rotates to landscape and drops the system bars, the way the
    // official clients behave. Both effects are undone on dispose so leaving
    // the screen cannot strand the activity in landscape.
    DisposableEffect(isFullscreen) {
        val controller = activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView)
        }
        if (isFullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Leaving the player while fullscreen must not leave the UI rotated.
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Back exits fullscreen before it leaves the screen.
    BackHandler(enabled = isFullscreen) { isFullscreen = false }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                val ratio = if (videoSize.height > 0) {
                    videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                } else {
                    null
                }
                if (ratio != null && ratio > 0f) videoAspect = ratio
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Progress ticker drives both the seek bar and the danmaku overlay.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = exoPlayer.currentPosition
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
            viewModel.onPositionChanged(positionMs)
            kotlinx.coroutines.delay(100)
        }
    }

    // Rebuild the media source whenever the resolved tracks *or* the session
    // cookie change; the CDN headers depend on the latter.
    LaunchedEffect(state.videoTrack, state.audioTrack, state.fallbackUrl, state.mediaCookie) {
        val video = state.videoTrack
        val audio = state.audioTrack
        val fallback = state.fallbackUrl

        when {
            video != null -> {
                exoPlayer.setMediaSource(
                    buildMergedSource(video, audio, state.mediaCookie),
                )
                exoPlayer.prepare()
            }
            fallback != null -> {
                // Progressive fallback still needs the CDN's required headers.
                val dataSource = DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(
                        buildMap {
                            put("User-Agent", MEDIA_USER_AGENT)
                            put("Referer", "https://www.bilibili.com/")
                            if (state.mediaCookie.isNotEmpty()) {
                                put("Cookie", state.mediaCookie)
                            }
                        },
                    )
                exoPlayer.setMediaSource(
                    ProgressiveMediaSource.Factory(dataSource)
                        .createMediaSource(MediaItem.fromUri(fallback)),
                )
                exoPlayer.prepare()
            }
            else -> Unit
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        PlayerSurface(
            exoPlayer = exoPlayer,
            state = state,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            isFullscreen = isFullscreen,
            isSpeedBoosted = isSpeedBoosted,
            isLandscapeLocked = isLandscapeLocked,
            supportsPip = supportsPip,
            videoAspect = videoAspect,
            danmakuConfig = danmakuConfig,
            onSeek = { target ->
                exoPlayer.seekTo(target)
                positionMs = target
            },
            onTogglePlay = { exoPlayer.playWhenReady = !exoPlayer.playWhenReady },
            onSpeedBoostChange = { isSpeedBoosted = it },
            onToggleLandscape = { isLandscapeLocked = !isLandscapeLocked },
            onEnterPip = {
                // Picture-in-picture keeps the current playback going in a
                // floating window, so the source is untouched.
                activity?.let { act ->
                    runCatching {
                        act.enterPictureInPictureMode(
                            android.app.PictureInPictureParams.Builder()
                                .setAspectRatio(android.util.Rational(16, 9))
                                .build(),
                        )
                    }
                }
            },
            onToggleFullscreen = { isFullscreen = !isFullscreen },
            onBack = onBack,
        )

        // Everything below the video is hidden in fullscreen, so the surface
        // owns the whole window. Guarding rather than early-returning keeps the
        // section out of composition entirely.
        if (!isFullscreen) {
            if (state.audioOnly) {
                AudioOnlyBanner(title = state.detail?.title.orEmpty())
            }

            // The seek bar now lives inside the video surface (see
            // PlayerSurface). Keeping a second one here is what produced the
            // duplicate progress bar.

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    VideoInfoSection(
                        state = state,
                        onLike = viewModel::toggleLike,
                        onCoin = { viewModel.addCoin(1, alsoLike = true) },
                        onFavorite = viewModel::toggleFavorite,
                        onFollow = viewModel::toggleFollow,
                        onToggleAudioOnly = viewModel::toggleAudioOnly,
                        onToggleDanmaku = viewModel::toggleDanmaku,
                        onQuality = viewModel::selectQuality,
                        onShare = {
                            shareVideo(
                                context = context,
                                url = viewModel.shareUrl(),
                                title = viewModel.shareTitle(),
                            )
                        },
                        onUploaderClick = {
                            val mid = viewModel.uploaderMid()
                            if (mid > 0) onUploaderClick(mid)
                        },
                        onCastClick = viewModel::onCastRequested,
                        onWatchLater = viewModel::toggleWatchLater,
                    )
                }

                item {
                    CommentsSection(
                        state = state,
                        onReplyTextChange = viewModel::onReplyTextChanged,
                        onSend = viewModel::sendReply,
                        onLikeReply = viewModel::likeReply,
                    )
                }
            }
        }
    }

    // Transient feedback (coin accepted, casting unavailable, ...). Shown as a
    // snackbar-style strip so it never blocks the video.
    state.notice?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(3200)
            viewModel.dismissNotice()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 48.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Text(
                text = message,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE6212121))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }

    state.error?.let { message ->
        LaunchedEffect(message) { viewModel.dismissError() }
    }
}

/**
 * The video surface plus its overlay chrome.
 *
 * In fullscreen the surface fills the window and the surrounding scrollable
 * content is removed from composition by the caller, so the video is not
 * letterboxed into a 16:9 strip.
 */
@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(
    exoPlayer: ExoPlayer,
    state: PlayerUiState,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isFullscreen: Boolean,
    isSpeedBoosted: Boolean,
    isLandscapeLocked: Boolean,
    supportsPip: Boolean,
    videoAspect: Float?,
    danmakuConfig: DanmakuConfig,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onSpeedBoostChange: (Boolean) -> Unit,
    onToggleLandscape: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (isFullscreen) it.fillMaxSize() else it }
            .background(Color.Black),
    ) {
        val surfaceModifier = when {
            // Audio-only collapses the picture to nothing but keeps playback.
            state.audioOnly && !isFullscreen -> Modifier.fillMaxWidth().height(0.dp)
            isFullscreen -> Modifier.fillMaxSize()
            else -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    // Controls are drawn by Compose below. Leaving Media3's own
                    // controller on is what produced two progress bars.
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = surfaceModifier,
        )

        // Long-press anywhere on the picture for 2x, release to resume 1x.
        if (!state.audioOnly) {
            Box(
                modifier = surfaceModifier.pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTogglePlay() },
                        onLongPress = { onSpeedBoostChange(true) },
                        onPress = {
                            tryAwaitRelease()
                            onSpeedBoostChange(false)
                        },
                    )
                },
            )
        }

        if (!state.audioOnly) {
            DanmakuOverlay(
                items = state.danmaku,
                positionMs = { positionMs },
                isPlaying = isPlaying,
                videoAspectRatio = videoAspect,
                config = danmakuConfig.copy(
                    enabled = danmakuConfig.enabled && state.danmakuEnabled,
                    speedMultiplier = if (isSpeedBoosted) SPEED_BOOST else 1f,
                ),
                // clipToBounds keeps comments inside the picture even when the
                // container is a different shape from the video.
                modifier = surfaceModifier.clipToBounds(),
            )
        }

        // Top chrome: back only. Fullscreen moved to the bottom-right, where
        // the reference clients put it.
        Row(modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            Spacer(Modifier.weight(1f))
            if (isSpeedBoosted) {
                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "${SPEED_BOOST.toInt()}x",
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        // Bottom control bar: progress, times, play/pause, fullscreen.
        if (!state.audioOnly) {
            PlayerControls(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                isFullscreen = isFullscreen,
                isLandscapeLocked = isLandscapeLocked,
                supportsPip = supportsPip,
                onSeek = onSeek,
                onTogglePlay = onTogglePlay,
                onToggleLandscape = onToggleLandscape,
                onEnterPip = onEnterPip,
                onToggleFullscreen = onToggleFullscreen,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (state.isLoading) {
            Box(surfaceModifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = YtColors.Red)
            }
        }
    }
}

/**
 * YouTube-style bottom bar: elapsed time, a draggable progress track, total
 * time, play/pause, and fullscreen pushed to the far right.
 */
@Composable
private fun PlayerControls(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isFullscreen: Boolean,
    isLandscapeLocked: Boolean,
    supportsPip: Boolean,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onToggleLandscape: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction
        ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x99000000))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Slider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { onSeek((it * durationMs).toLong()) }
                dragFraction = null
            },
            modifier = Modifier.fillMaxWidth().height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = YtColors.Red,
                activeTrackColor = YtColors.Red,
                inactiveTrackColor = Color(0x66FFFFFF),
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = formatClock(dragFraction?.let { (it * durationMs).toLong() } ?: positionMs),
                color = Color.White,
                fontSize = 11.sp,
            )
            Text(
                text = " / ${formatClock(durationMs)}",
                color = Color(0xB3FFFFFF),
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))

            // Manual landscape lock, for watching on a stand without
            // entering fullscreen.
            IconButton(onClick = onToggleLandscape, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.ScreenRotation,
                    contentDescription = if (isLandscapeLocked) "解锁方向" else "锁定横屏",
                    tint = if (isLandscapeLocked) YtColors.Red else Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }

            // Picture-in-picture, only offered when the device supports it.
            if (supportsPip) {
                IconButton(onClick = onEnterPip, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.PictureInPictureAlt,
                        contentDescription = "小窗播放",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (isFullscreen) {
                        Icons.Filled.FullscreenExit
                    } else {
                        Icons.Filled.Fullscreen
                    },
                    contentDescription = if (isFullscreen) "退出全屏" else "全屏",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** Long-press playback speed. */
private const val SPEED_BOOST = 2f

private fun formatClock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Walks the context chain to the hosting [Activity]; null in previews/tests. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Hands the video to the system share sheet.
 *
 * `createChooser` is used explicitly so the user always gets the picker rather
 * than being dropped straight into whichever app happens to be the default.
 */
private fun shareVideo(context: Context, url: String, title: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, buildString {
            if (title.isNotEmpty()) {
                append(title)
                append('\n')
            }
            append(url)
        })
        if (title.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, title)
    }
    val chooser = Intent.createChooser(intent, "分享视频").apply {
        // The player lives in a task without a launcher activity, so the chooser
        // needs its own task to appear reliably.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}

/**
 * Bilibili serves DASH video and audio as *separate* URLs rather than a single
 * manifest. Media3 cannot merge those from a [MediaItem], so we build the
 * [MergingMediaSource] directly and hand it to the player, bypassing the
 * `setMediaItem` convenience path.
 *
 * The request headers matter as much as the URLs:
 *
 *  - **`Referer` is mandatory.** Bilibili's edge hosts (`*.bilivideo.cn`,
 *    `*.mountaintoys.cn`) answer `403` to a bare request. This was the reason
 *    playback failed even once a valid stream URL had been resolved.
 *  - `Cookie` carries the session, which gates the higher quality tiers.
 *  - The UA must be a desktop browser's, matching the web API client.
 */
@OptIn(UnstableApi::class)
private fun buildMergedSource(
    video: com.ytstyle.bili.data.net.DashTrack,
    audio: com.ytstyle.bili.data.net.DashTrack?,
    cookie: String,
): androidx.media3.exoplayer.source.MediaSource {
    val headers = buildMap {
        put("User-Agent", MEDIA_USER_AGENT)
        put("Referer", "https://www.bilibili.com/")
        put("Origin", "https://www.bilibili.com")
        if (cookie.isNotEmpty()) put("Cookie", cookie)
    }

    val factory = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(headers)
        // Bilibili redirects to per-edge hosts, occasionally across protocols.
        .setAllowCrossProtocolRedirects(true)

    val videoSource = ProgressiveMediaSource.Factory(factory)
        .createMediaSource(MediaItem.fromUri(video.baseUrl))

    if (audio == null) return videoSource

    // Audio is fetched with the same headers, or its edge host 403s too.
    val audioSource = ProgressiveMediaSource.Factory(factory)
        .createMediaSource(MediaItem.fromUri(audio.baseUrl))

    return MergingMediaSource(videoSource, audioSource)
}

internal const val MEDIA_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val current = dragValue ?: if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTime((dragValue?.let { (it * durationMs).toLong() } ?: positionMs)),
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 11.sp,
        )
        Slider(
            value = current,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { onSeek((it * durationMs).toLong()) }
                dragValue = null
            },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = YtColors.Red,
                activeTrackColor = YtColors.Red,
                inactiveTrackColor = AppTheme.colors.surfaceElevated,
            ),
        )
        Text(
            text = formatTime(durationMs),
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 11.sp,
        )
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun AudioOnlyBanner(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.surfaceElevated)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Headphones,
            contentDescription = null,
            tint = YtColors.Red,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "仅音频播放",
                color = AppTheme.colors.onBackground,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = title,
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
