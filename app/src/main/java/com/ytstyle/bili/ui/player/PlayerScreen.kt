package com.ytstyle.bili.ui.player

import com.ytstyle.bili.ui.theme.AppTheme

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Rational
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.ytstyle.bili.data.log.AppLog
import com.ytstyle.bili.data.net.PgcEpisode
import com.ytstyle.bili.data.net.Reply
import com.ytstyle.bili.ui.PipState
import com.ytstyle.bili.ui.cast.CastDialog
import com.ytstyle.bili.ui.components.CompactSlider
import com.ytstyle.bili.ui.components.SystemBarIcons
import com.ytstyle.bili.ui.components.SystemBarsState
import com.ytstyle.bili.ui.components.formatCount
import com.ytstyle.bili.ui.theme.YtColors

// ---- landscape chrome ----
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onUploaderClick: (Long) -> Unit = {},
    /** Opens another video — used by the 合集 list and the related rail. */
    onOpenVideo: (String) -> Unit = {},
    /** Opens the offline-cache picker for this video. */
    onDownload: () -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val inPip = PipState.isInPip

    // The player must outlive recomposition and be released with the screen.
    // It starts paused so the "自动播放" setting decides whether it ever runs;
    // building it playing would leak a moment of audio from a video the user
    // asked not to start automatically.
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = false }
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    /**
     * Set by the landscape exit arrow: keep the window portrait until the phone
     * is upright again.
     *
     * Without it the landscape arrow could only clear [isFullscreen], which does
     * nothing when the window is landscape *because the phone is lying on its
     * side* — so tapping "exit" left the landscape layout up and the next tap
     * would have exited the video instead, which is what was reported.
     */
    var exitLandscapeRequested by remember { mutableStateOf(false) }
    /**
     * A landscape **window** counts as landscape, not just the fullscreen flag.
     *
     * With the phone's auto-rotate on, the activity itself rotates, so the player
     * is already lying on its side — but the chrome used to be chosen from
     * [isFullscreen] alone, so the page kept the portrait layout (and its black
     * letterbox) inside a landscape window. Reading the configuration makes
     * rotating the phone the gesture that switches layouts, which is what
     * "自动旋转" is expected to do.
     *
     * Picture-in-picture is excluded: there the window is a small floating
     * rectangle and the portrait controls are the right ones.
     */
    val configuration = LocalConfiguration.current
    val landscape = !exitLandscapeRequested && (
        isFullscreen ||
            (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && !inPip)
        )
    // Release the portrait pin only once the *device* is upright again.
    //
    // Releasing it the moment the window reported portrait was the bug behind
    // "the exit arrow does nothing" on auto-rotate: the phone is still sideways,
    // so the sensor rotates the window straight back to landscape.
    OrientationUprightEffect(enabled = exitLandscapeRequested) {
        exitLandscapeRequested = false
    }
    /**
     * Landscape lock. While it is on, a tap no longer reveals the chrome and
     * only the unlock button is drawn, which is what stops a stray touch from
     * pausing or seeking a video being watched in landscape.
     */
    var isLocked by remember { mutableStateOf(false) }
    /** The rate chosen in the landscape 倍速 menu; the long-press boost overrides it. */
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    /** width / height of the decoded video; null until the first frame. */
    var videoAspect by remember { mutableStateOf<Float?>(null) }
    /** Long-press to speed up, released to return to 1x. */
    var isSpeedBoosted by remember { mutableStateOf(false) }
    /** Overlay chrome; hides itself a few seconds into playback. */
    var chromeVisible by remember { mutableStateOf(true) }
    /** Bumped by every interaction so the auto-hide timer restarts. */
    var interactionTick by remember { mutableIntStateOf(0) }
    /** Picture-in-picture starts clean and reveals its controls on tap. */
    var pipChromeVisible by remember { mutableStateOf(false) }
    /** Which of the three sections under the player is showing. */
    var tab by remember { mutableStateOf(PlayerTab.Intro) }
    /** Whether the 投屏 device sheet is open. */
    var castOpen by remember { mutableStateOf(false) }

    /** Any tap on a control counts as activity and re-arms the hide timer. */
    val onInteract: () -> Unit = {
        chromeVisible = true
        interactionTick++
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val danmakuConfig = state.danmakuConfig

    val activity = context.findActivity()
    val supportsPip = remember(activity) {
        activity?.packageManager?.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE,
        ) == true
    }

    /** Applies the stored autoplay preference once the setting has been read. */
    LaunchedEffect(state.autoplayResolved, state.autoplay) {
        if (state.autoplayResolved) exoPlayer.playWhenReady = state.autoplay
    }

    /**
     * The player's status-bar strip is black in **both** themes, so its icons
     * must be light regardless of the app palette.
     *
     * Only the *wish* is published here; MainActivity owns the window and
     * re-derives the tint from the theme the moment this screen is gone. The
     * previous version wrote the flag directly and restored it with an inverted
     * value, which is exactly why light-theme users came back to white icons on
     * a white page.
     */
    DisposableEffect(Unit) {
        SystemBarsState.icons = SystemBarIcons.Light
        onDispose { SystemBarsState.icons = null }
    }

    /**
     * Full screen *is* landscape here.
     *
     * The separate "lock landscape" control was removed with the setting behind
     * it: it did the same rotation, only without hiding the system bars, so the
     * two buttons disagreed about nothing but chrome.
     */
    DisposableEffect(isFullscreen, exitLandscapeRequested) {
        when {
            isFullscreen -> {
                activity?.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                SystemBarsState.hidden = true
            }

            // The exit arrow was pressed while the phone itself was sideways:
            // pin portrait, otherwise the window would stay landscape and the
            // "exit" would look like it did nothing.
            exitLandscapeRequested -> {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                SystemBarsState.hidden = false
            }

            else -> {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                SystemBarsState.hidden = false
            }
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            SystemBarsState.hidden = false
        }
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

    // The long-press boost wins while it is held; the menu's rate is what
    // playback returns to on release.
    LaunchedEffect(isSpeedBoosted, playbackSpeed) {
        exoPlayer.setPlaybackSpeed(if (isSpeedBoosted) SPEED_BOOST else playbackSpeed)
    }

    /** The landscape comments panel, on the right third. */
    var landscapeComments by remember { mutableStateOf(false) }

    /** The landscape 弹幕设置 sheet; shares the side slot with the comments. */
    var landscapeDanmakuSettings by remember { mutableStateOf(false) }

    /** The portrait composer panel: 字体大小 / 弹幕位置 / 弹幕颜色. */
    var danmakuSendPanel by remember { mutableStateOf(false) }

    /** The landscape 选集 panel; only meaningful for a season. */
    var landscapeEpisodes by remember { mutableStateOf(false) }

    // Leaving landscape always unlocks, and closes the side panel: neither
    // belongs to the portrait layout.
    LaunchedEffect(isFullscreen) {
        if (!isFullscreen) {
            isLocked = false
            landscapeComments = false
            landscapeDanmakuSettings = false
            landscapeEpisodes = false
        }
    }

    /**
     * 输入弹幕时暂停。
     *
     * While the composer holds focus the video pauses — you cannot read what you
     * are typing over a moving picture — and sending, or simply leaving the
     * field, resumes it. [wasPlayingBeforeComposing] is what keeps a video the
     * user had *deliberately* paused from starting itself when they tap the
     * field and change their mind.
     */
    var danmakuComposing by remember { mutableStateOf(false) }
    var wasPlayingBeforeComposing by remember { mutableStateOf(false) }
    LaunchedEffect(danmakuComposing) {
        if (danmakuComposing) {
            wasPlayingBeforeComposing = exoPlayer.playWhenReady
            exoPlayer.playWhenReady = false
        } else if (wasPlayingBeforeComposing) {
            exoPlayer.playWhenReady = true
        }
    }

    /**
     * Back leaves full screen first, and only then leaves the screen.
     *
     * This is the same rule the top-left arrow follows — before, the arrow
     * called `onBack()` unconditionally, so tapping it while watching landscape
     * closed the video instead of returning to portrait.
     */
    BackHandler(enabled = isFullscreen) { isFullscreen = false }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                // Pausing is the moment somebody is most likely to leave and
                // pick the video up elsewhere, so the position goes up straight
                // away rather than waiting for the next throttled report.
                if (!playing) viewModel.reportProgress(force = true)
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                val ratio = if (videoSize.height > 0) {
                    videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                } else {
                    null
                }
                if (ratio != null && ratio > 0f) videoAspect = ratio
            }

            /**
             * Playback failures are otherwise invisible: the screen just stays
             * black. The error name and the failing URL's host are what identify
             * a region block, an expired CDN signature or a codec problem.
             */
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                AppLog.e("player", "playback error ${error.errorCodeName}: ${error.message}", error)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    /**
     * Hides the overlay chrome a few seconds after the last interaction.
     *
     * Only while playing: a paused video with no visible controls reads as a
     * frozen app, so the bar stays put until playback resumes.
     */
    LaunchedEffect(chromeVisible, isPlaying, interactionTick) {
        if (chromeVisible && isPlaying) {
            kotlinx.coroutines.delay(CONTROLS_HIDE_DELAY_MS)
            chromeVisible = false
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

    // Which content the player is currently holding, so a rebuild can tell a
    // quality switch from a different video.
    var appliedContentKey by remember { mutableStateOf("") }

    /**
     * Whether the user has asked for playback.
     *
     * With 「仅 Wi-Fi 自动播放」 on a mobile network the media is not even
     * *prepared*: preparing starts buffering, which spends the user's data on a
     * video the app already decided not to start. This flag is what turns the
     * first tap — on the play button or on the picture — into the load.
     */
    var playbackRequested by remember { mutableStateOf(false) }

    // Rebuild the media source whenever the resolved tracks *or* the session
    // cookie change; the CDN headers depend on the latter.
    LaunchedEffect(
        state.videoTrack,
        state.audioTrack,
        state.fallbackUrl,
        state.mediaCookie,
        state.contentKey,
        playbackRequested,
    ) {
        // Nothing is fetched until the user asks: a blocked autoplay must not
        // buffer the stream in the background.
        if (state.autoplayBlockedByNetwork && !playbackRequested) return@LaunchedEffect

        val video = state.videoTrack
        val audio = state.audioTrack
        val fallback = state.fallbackUrl

        // **Seamless quality switching.** Choosing a different 清晰度 resolves a
        // new track and rebuilds the source; passing `resetPosition = false`
        // when the video itself has not changed makes ExoPlayer hand the
        // position over to the new source, so playback continues from the same
        // second instead of jumping back to the start. A different video or
        // episode *does* reset.
        val sameContent = appliedContentKey == state.contentKey
        val resetPosition = !sameContent

        when {
            video != null -> {
                exoPlayer.setMediaSource(
                    buildMergedSource(video, audio, state.mediaCookie),
                    resetPosition,
                )
                exoPlayer.prepare()
            }
            fallback != null -> {
                // Progressive fallback still needs the CDN's required headers.
                val dataSource = DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(mediaHeaders(state.mediaCookie))
                exoPlayer.setMediaSource(
                    ProgressiveMediaSource.Factory(dataSource)
                        .createMediaSource(MediaItem.fromUri(fallback)),
                    resetPosition,
                )
                exoPlayer.prepare()
            }
            else -> return@LaunchedEffect
        }

        if (!sameContent) {
            appliedContentKey = state.contentKey
            // Cross-device resume: continue from Bilibili's stored position.
            // Only on a *new* content key, so a quality switch does not jump
            // back to it after the user has watched further.
            val resume = state.startPositionMs
            if (resume > 0) {
                exoPlayer.seekTo(resume)
                positionMs = resume
            }
        }
    }

    /**
     * In picture-in-picture the whole window is the video and nothing else.
     *
     * Without this the PiP window rendered the 16:9 picture *plus* the control
     * bar, the danmaku overlay and a slice of the info list — the reported
     * "小窗 UI 遮挡视频内容".
     */
    if (inPip) {
        PlayerSurface(
            exoPlayer = exoPlayer,
            state = state,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            isFullscreen = true,
            inPip = true,
            // Clean by default: the window is only a few centimetres wide, so
            // the picture is all that should be there until it is tapped.
            controlsVisible = pipChromeVisible,
            isSpeedBoosted = false,
            supportsPip = false,
            videoAspect = videoAspect,
            danmakuConfig = danmakuConfig,
            onInteract = {},
            onToggleChrome = { pipChromeVisible = !pipChromeVisible },
            onSeek = { exoPlayer.seekTo(it); positionMs = it },
            onTogglePlay = { exoPlayer.playWhenReady = !exoPlayer.playWhenReady },
            onSkip = { delta ->
                val target = (exoPlayer.currentPosition + delta).coerceAtLeast(0L)
                exoPlayer.seekTo(target)
                positionMs = target
            },
            onSpeedBoostChange = {},
            onEnterPip = {},
            onToggleFullscreen = {},
            onBack = {},
            onCast = {},
            // Picture-in-picture owns the whole window and never shows the
            // landscape chrome, so every one of these is inert.
            isLocked = false,
            playbackSpeed = 1f,
            onToggleLock = {},
            onLike = {},
            onWatchLater = {},
            onShare = {},
            onFollow = {},
            onToggleDanmaku = {},
            onDanmakuText = {},
            onSendDanmaku = {},
            onDanmakuComposing = {},
            onSelectQuality = {},
            onSelectSpeed = {},
            onToggleComments = {},
            onToggleDanmakuSettings = {},
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        PlayerSurface(
            exoPlayer = exoPlayer,
            state = state,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            isFullscreen = landscape,
            inPip = false,
            controlsVisible = chromeVisible,
            isSpeedBoosted = isSpeedBoosted,
            supportsPip = supportsPip,
            videoAspect = videoAspect,
            danmakuConfig = danmakuConfig,
            // Only the comments take a slice of the frame; the 弹幕设置 card
            // floats over the picture instead of shrinking it.
            landscapePanelFraction = if (landscapeComments) LANDSCAPE_COMMENTS_WIDTH else 0f,
            onInteract = onInteract,
            // A tap on the picture toggles the chrome; a tap while it is up
            // hides it again, which is what "默认隐藏，点击才显示" means.
            onToggleChrome = {
                // Tapping the picture is how the danmaku settings are dismissed:
                // the panel deliberately has no close button, so the gesture
                // that opened it must also be the one that closes it.
                if (landscapeDanmakuSettings) {
                    landscapeDanmakuSettings = false
                } else if (landscapeEpisodes) {
                    // Same rule as the danmaku panel: no close button, the tap
                    // on the picture is the way out.
                    landscapeEpisodes = false
                } else if (state.autoplayBlockedByNetwork && !playbackRequested) {
                    // The blocked-autoplay banner promises "点画面即可开始", so
                    // the first tap on the picture is also the load + start.
                    playbackRequested = true
                    exoPlayer.playWhenReady = true
                } else {
                    chromeVisible = !chromeVisible
                }
                interactionTick++
            },
            onSeek = { target ->
                onInteract()
                exoPlayer.seekTo(target)
                positionMs = target
            },
            onTogglePlay = {
                onInteract()
                playbackRequested = true
                exoPlayer.playWhenReady = !exoPlayer.playWhenReady
            },
            onSkip = { delta ->
                onInteract()
                val target = (exoPlayer.currentPosition + delta).coerceAtLeast(0L)
                exoPlayer.seekTo(target)
                positionMs = target
            },
            onSpeedBoostChange = { isSpeedBoosted = it },
            onEnterPip = {
                // Picture-in-picture keeps the current playback going in a
                // floating window, so the source is untouched.
                activity?.let { act ->
                    runCatching {
                        act.enterPictureInPictureMode(
                            PictureInPictureParams.Builder()
                                .setAspectRatio(pipAspectRatio(videoAspect))
                                .build(),
                        )
                    }
                }
            },
            onToggleFullscreen = {
                exitLandscapeRequested = false
                isFullscreen = !isFullscreen
            },
            onBack = {
                // In the landscape layout the arrow means "exit landscape", not
                // "exit the video". It has to key off `landscape` rather than
                // `isFullscreen`: with auto-rotate the window can be landscape
                // while the flag is false, and leaving the video there was the
                // reported behaviour.
                if (landscape) {
                    isFullscreen = false
                    exitLandscapeRequested = true
                } else {
                    onBack()
                }
            },
            onCast = {
                // Resolve a castable single-file URL first; the sheet waits for it.
                viewModel.ensureCastSource()
                castOpen = true
            },
            isLocked = isLocked,
            playbackSpeed = playbackSpeed,
            onToggleLock = {
                isLocked = !isLocked
                onInteract()
            },
            onLike = {
                onInteract()
                viewModel.toggleLike()
            },
            onWatchLater = {
                onInteract()
                viewModel.toggleWatchLater()
            },
            onShare = {
                onInteract()
                shareVideo(context, viewModel.shareUrl(), viewModel.shareTitle())
            },
            onFollow = {
                onInteract()
                viewModel.toggleFollow()
            },
            onToggleDanmaku = {
                onInteract()
                viewModel.toggleDanmaku()
            },
            onDanmakuText = viewModel::onDanmakuTextChanged,
            onSendDanmaku = {
                onInteract()
                viewModel.sendDanmaku()
            },
            onDanmakuComposing = { danmakuComposing = it },
            onSelectQuality = { quality ->
                onInteract()
                viewModel.selectQuality(quality)
            },
            onSelectSpeed = { rate ->
                onInteract()
                playbackSpeed = rate
            },
            onToggleComments = {
                onInteract()
                landscapeComments = !landscapeComments
                if (landscapeComments) landscapeDanmakuSettings = false
            },
            onToggleDanmakuSettings = {
                onInteract()
                landscapeDanmakuSettings = !landscapeDanmakuSettings
                if (landscapeDanmakuSettings) landscapeComments = false
            },
            danmakuSettingsOpen = landscapeDanmakuSettings,
            onToggleEpisodes = {
                onInteract()
                landscapeEpisodes = !landscapeEpisodes
                if (landscapeEpisodes) {
                    landscapeComments = false
                    landscapeDanmakuSettings = false
                }
            },
            episodesOpen = landscapeEpisodes,
            landscapeOverlay = {
                if (landscapeComments) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .fillMaxWidth(LANDSCAPE_COMMENTS_WIDTH),
                    ) {
                        LandscapeCommentsPanel(
                            state = state,
                            onClose = { landscapeComments = false },
                            onReplyTextChange = viewModel::onReplyTextChanged,
                            onSend = viewModel::sendReply,
                            onLikeReply = viewModel::likeReply,
                            onDislikeReply = viewModel::dislikeReply,
                            onReplyTo = viewModel::startReplyTo,
                            onCancelReply = viewModel::cancelReply,
                            onLoadMore = viewModel::loadMoreReplies,
                            onExpandThread = viewModel::expandThread,
                            onSortChange = viewModel::setReplySort,
                            onUserClick = onUploaderClick,
                        )
                    }
                }
                if (landscapeEpisodes && state.episodes.isNotEmpty()) {
                    // 选集 in landscape, with each episode's own name. The list
                    // floats over the picture like the other secondary panels,
                    // and the row being watched is marked so it is obvious which
                    // one the season is currently on.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = LANDSCAPE_DANMAKU_PANEL_BOTTOM_PADDING.dp)
                            .width(300.dp)
                            .heightIn(max = 420.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(LANDSCAPE_MENU_BACKGROUND),
                    ) {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                text = "选集（${state.episodes.size}）",
                                color = Color(0xCCFFFFFF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(
                                    start = 14.dp,
                                    end = 14.dp,
                                    top = 12.dp,
                                    bottom = 6.dp,
                                ),
                            )
                            state.episodes.forEach { episode ->
                                val selected = episode.playId == state.currentEpId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onInteract()
                                            landscapeEpisodes = false
                                            viewModel.selectEpisode(episode)
                                        }
                                        .background(
                                            if (selected) Color(0x26FFFFFF) else Color.Transparent,
                                        )
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = "正在播放",
                                            tint = YtColors.Red,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(
                                        text = episodeLabel(episode),
                                        color = if (selected) YtColors.Red else Color.White,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (episode.badge.isNotEmpty()) {
                                        Spacer(Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(YtColors.Red)
                                                .padding(horizontal = 4.dp, vertical = 1.dp),
                                        ) {
                                            Text(
                                                text = episode.badge,
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (landscapeDanmakuSettings) {
                    // A card that floats above the control row, next to the
                    // danmaku switch that opened it — not a full-height side
                    // sheet sharing the comments' slot, which is what made the
                    // two look like the same panel. Translucent so the picture
                    // being judged is still visible behind it, and dismissed by
                    // tapping the picture rather than by a close button.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(
                                start = 12.dp,
                                bottom = LANDSCAPE_DANMAKU_PANEL_BOTTOM_PADDING.dp,
                            )
                            .width(LANDSCAPE_DANMAKU_PANEL_WIDTH.dp)
                            .heightIn(max = 400.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(LANDSCAPE_MENU_BACKGROUND),
                    ) {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            DanmakuSettingsPanel(
                                scale = state.danmakuConfig.scale,
                                opacity = state.danmakuConfig.opacity,
                                area = state.danmakuConfig.area,
                                speed = state.danmakuConfig.traverseSeconds,
                                types = state.danmakuConfig.types,
                                onScale = viewModel::setDanmakuScale,
                                onOpacity = viewModel::setDanmakuOpacity,
                                onArea = viewModel::setDanmakuArea,
                                onSpeed = viewModel::setDanmakuSpeed,
                                onToggleType = viewModel::toggleDanmakuType,
                                onReset = viewModel::resetDanmakuSettings,
                                onDark = true,
                            )
                        }
                    }
                }
            },
        )

        // The cast sheet. It renders as a dialog window, so it survives the
        // fullscreen switch — casting from landscape has to work.
        if (castOpen) {
            CastDialog(
                // The *progressive* stream, not the DASH pair: a television
                // plays exactly one URL. `ensureCastSource` resolves it when the
                // sheet opens, so this is blank only for the first moment.
                sourceUrl = state.castUrl.orEmpty(),
                title = state.detail?.title.orEmpty(),
                // The CDN wants these; the television never sees them.
                headers = mediaHeaders(state.mediaCookie),
                onDismiss = { castOpen = false },
                isResolvingSource = state.isResolvingCast,
            )
        }

        // Everything below the video is hidden in fullscreen, so the surface
        // owns the whole window. Guarding rather than early-returning keeps the
        // section out of composition entirely.
        if (!isFullscreen) {
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                state.error?.let { message ->
                    ErrorBanner(message = message, onRetry = viewModel::retry, onDismiss = viewModel::dismissError)
                }

                // Explains a paused start: the video is not broken, the
                // autoplay policy simply declined to spend mobile data.
                if (state.autoplayBlockedByNetwork) {
                    Text(
                        text = "当前是移动网络，按「设置 → 播放」的策略没有自动播放，点画面即可开始。",
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppTheme.colors.surfaceElevated)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }

                if (state.audioOnly) {
                    // The picture is collapsed to nothing in this mode, which left
                    // a black strip with no controls at all. One obvious play /
                    // pause target sits on it, with the way back at the top-left.
                    AudioOnlyPlayerBar(
                        isPlaying = isPlaying,
                        onTogglePlay = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        onBack = onBack,
                    )
                    AudioOnlyBanner(title = state.detail?.title.orEmpty())
                }

                if (state.detail == null) {
                    // Detail, streams and comments all hang off the detail
                    // payload, so an empty page here means the whole lower half
                    // failed — say so and offer a retry instead of leaving a
                    // blank strip under the player.
                    LoadingOrRetry(
                        isLoading = state.isLoading,
                        onRetry = viewModel::retry,
                    )
                } else {
                    PlayerTabRow(
                        selected = tab,
                        onSelect = { tab = it },
                        commentCount = state.detail?.stat?.reply?.let(::formatCount).orEmpty(),
                    ) {
                        // The composer lives in the header now, not in its own
                        // tab: it is one tap from anywhere on the page.
                        DanmakuPill(
                            state = state,
                            onTextChange = viewModel::onDanmakuTextChanged,
                            onSend = viewModel::sendDanmaku,
                            onToggleDanmaku = viewModel::toggleDanmaku,
                            onToggleSendPanel = { danmakuSendPanel = !danmakuSendPanel },
                            sendPanelOpen = danmakuSendPanel,
                            onComposingChange = { danmakuComposing = it },
                        )
                    }

                    // Sits directly under the composer, the way the reference
                    // client lays it out, so the colour that was just picked is
                    // visible right above the swatch that picked it.
                    if (danmakuSendPanel) {
                        DanmakuSendPanel(
                            mode = state.danmakuMode,
                            fontSize = state.danmakuFontSize,
                            color = state.danmakuColor,
                            vipGradient = state.danmakuVipGradient,
                            canSendColor = state.canSendColorDanmaku,
                            canSendVipGradient = state.canSendVipColorful,
                            onMode = viewModel::setDanmakuMode,
                            onFontSize = viewModel::setDanmakuFontSize,
                            onColor = { option ->
                                // The gradient is a style, not a colour value:
                                // it travels in `colorful`, not `color`.
                                if (option.vipGradient) {
                                    viewModel.setDanmakuVipGradient()
                                } else {
                                    viewModel.setDanmakuColor(option.color)
                                }
                            },
                        )
                    }

                    state.danmakuFeedback?.let { message ->
                        LaunchedEffect(message) {
                            kotlinx.coroutines.delay(5_000)
                            viewModel.dismissDanmakuFeedback()
                        }
                        Text(
                            text = message,
                            color = if (state.danmakuFeedbackIsError) {
                                YtColors.Red
                            } else {
                                AppTheme.colors.onBackgroundSecondary
                            },
                            fontSize = 11.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }

                    when (tab) {
                        // 简介: everything that describes the video, plus the
                        // collection it belongs to and the watch-next rail.
                        PlayerTab.Intro -> LazyColumn(
                            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                            contentPadding = PaddingValues(bottom = 24.dp),
                        ) {
                            item {
                                VideoInfoSection(
                                    state = state,
                                    onLike = viewModel::toggleLike,
                                    onCoin = { viewModel.addCoin(1, alsoLike = true) },
                                    onFavorite = viewModel::openFavouritePicker,
                                    onFollow = viewModel::toggleFollow,
                                    onToggleAudioOnly = viewModel::toggleAudioOnly,
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
                                    onCastClick = {
                                        // Same path as the player's cast button:
                                        // resolve, then open the device sheet.
                                        // This used to raise a stale "尚未实现"
                                        // notice from before casting existed.
                                        viewModel.ensureCastSource()
                                        castOpen = true
                                    },
                                    onWatchLater = viewModel::toggleWatchLater,
                                    onEpisodeClick = viewModel::selectEpisode,
                                    onDownload = onDownload,
                                )
                            }

                            if (state.isLoading) {
                                item {
                                    Box(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            color = YtColors.Red,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }

                            val season = state.detail?.ugcSeason
                            if (season != null) {
                                item {
                                    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                                        CollectionSection(
                                            season = season,
                                            currentBvid = state.detail?.bvid.orEmpty(),
                                            onOpenVideo = onOpenVideo,
                                        )
                                    }
                                }
                            }

                            item {
                                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                    RelatedSection(
                                        videos = state.related,
                                        onOpenVideo = onOpenVideo,
                                    )
                                }
                            }
                        }

                        PlayerTab.Comments -> LazyColumn(
                            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                            contentPadding = PaddingValues(bottom = 24.dp),
                        ) {
                            item {
                                CommentsSection(
                                    state = state,
                                    onReplyTextChange = viewModel::onReplyTextChanged,
                                    onSend = viewModel::sendReply,
                                    onLikeReply = viewModel::likeReply,
                                    onDislikeReply = viewModel::dislikeReply,
                                    onReplyTo = viewModel::startReplyTo,
                                    onCancelReply = viewModel::cancelReply,
                                    onLoadMore = viewModel::loadMoreReplies,
                                    onExpandThread = viewModel::expandThread,
                                    onSortChange = viewModel::setReplySort,
                                    // Same destination as the uploader link.
                                    onUserClick = onUploaderClick,
                                )
                            }

                            // Paging sentinel. The section above renders every
                            // reply it holds, so this only enters composition
                            // once the reader has actually scrolled past them —
                            // which is the moment to fetch the next page. The
                            // explicit 「加载更多评论」 button is still there, but
                            // waiting for a tap meant a 388-comment thread sat at
                            // "已加载 20" forever, which is what "评论区还是加载
                            // 不完全" was.
                            //
                            // Keyed on Unit, so it fires once per entry into
                            // composition: the page it loads pushes the sentinel
                            // out of view, and it re-arms on the next scroll down
                            // rather than looping on the spot.
                            // Keyed on the list size rather than on Unit: Unit
                            // fires once per entry into composition, so a short
                            // page that left the sentinel still on screen stalled
                            // there with nothing left to trigger the next fetch.
                            // Re-arming on growth keeps pulling pages while the
                            // sentinel is visible, and stops on its own because
                            // `hasMoreReplies` requires the page to have added
                            // something new.
                            if (state.hasMoreReplies) {
                                item(key = "replies-sentinel") {
                                    LaunchedEffect(state.replies.size) { viewModel.loadMoreReplies() }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(
                                            color = YtColors.Red,
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                }
                            } else if (state.replies.isNotEmpty()) {
                                item(key = "replies-end") {
                                    Text(
                                        text = "没有更多评论了",
                                        color = AppTheme.colors.onBackgroundSecondary,
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                    )
                                }
                            }
                        }

                    }
                }
            }
        }
    }

    FavouriteFolderDialog(
        state = state,
        onPick = viewModel::toggleFavouriteIn,
        onDismiss = viewModel::dismissFavouritePicker,
    )

    // The comment result sits under the composer until it has been read.
    state.commentFeedback?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(5000)
            viewModel.dismissCommentFeedback()
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
}

/**
 * Shown while the page payload is still loading, and when it failed.
 *
 * The old build dismissed `error` in a `LaunchedEffect` the moment it appeared,
 * so a failed `viewDetail` or a region-locked `playurl` left a black player
 * above an empty page with no explanation at all.
 */
@Composable
private fun LoadingOrRetry(isLoading: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = YtColors.Red)
            Spacer(Modifier.height(12.dp))
            Text("正在加载视频信息…", color = AppTheme.colors.onBackgroundSecondary, fontSize = 13.sp)
        } else {
            Text("视频信息加载失败", color = AppTheme.colors.onBackground, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "可能是网络问题，或该内容在当前地区不可用。",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(AppTheme.colors.surfaceElevated)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text("重试", color = AppTheme.colors.onBackground)
            }
        }
    }
}

/** Inline error strip with a retry action; replaces the silent dismissal. */
@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(YtColors.Red.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = AppTheme.colors.onBackground,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "重试",
            color = YtColors.Red,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onRetry).padding(4.dp),
        )
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "关闭提示",
            tint = AppTheme.colors.onBackgroundSecondary,
            modifier = Modifier.size(16.dp).clickable(onClick = onDismiss),
        )
    }
}

/**
 * Folder picker for 收藏.
 *
 * The folder is chosen explicitly because the previous build silently used
 * whichever folder came back first, which is not the same thing as the user's
 * folder. Picking the folder the video is already in removes it, so one dialog
 * covers both directions.
 */
@Composable
private fun FavouriteFolderDialog(
    state: PlayerUiState,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.showFavPicker) return

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colors.surfaceElevated,
        title = {
            Text(
                text = if (state.isFavorited) "收藏夹（再次选择可取消收藏）" else "收藏到",
                color = AppTheme.colors.onBackground,
                fontSize = 16.sp,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                state.favFolders.forEach { folder ->
                    val containing = state.isFavorited && folder.id in state.favFolderIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(folder.id) }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = if (containing) YtColors.FavColor else AppTheme.colors.onBackgroundSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = folder.title,
                                color = AppTheme.colors.onBackground,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${folder.mediaCount} 个内容" +
                                    if (containing) " · 已收藏" else "",
                                color = AppTheme.colors.onBackgroundSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        if (containing) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "已在此收藏夹",
                                tint = YtColors.FavColor,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = AppTheme.colors.onBackgroundSecondary)
            }
        },
    )
}

/**
 * The video surface plus its overlay chrome.
 *
 * In fullscreen the surface fills the window and the surrounding scrollable
 * content is removed from composition by the caller, so the video is not
 * letterboxed into a 16:9 strip.
 *
 * [controlsVisible] is driven by the caller and hides itself a few seconds into
 * playback. Danmaku deliberately does **not** follow it — comments keep flying
 * while the controls are away — except in picture-in-picture, where nothing but
 * the picture belongs on screen until the window is tapped.
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
    inPip: Boolean,
    controlsVisible: Boolean,
    isSpeedBoosted: Boolean,
    supportsPip: Boolean,
    videoAspect: Float?,
    danmakuConfig: DanmakuConfig,
    onInteract: () -> Unit,
    onToggleChrome: () -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    /** Relative seek used by the picture-in-picture ±5s buttons. */
    onSkip: (Long) -> Unit,
    onSpeedBoostChange: (Boolean) -> Unit,
    onEnterPip: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onBack: () -> Unit,
    /** Opens the cast sheet; only the portrait bar offers it. */
    onCast: () -> Unit,
    /**
     * Extra content drawn inside the surface's own [Box], so a landscape panel
     * can sit above the video without a second player instance. Empty in
     * portrait and in picture-in-picture.
     */
    landscapeOverlay: @Composable BoxScope.() -> Unit = {},
    /**
     * Share of the landscape width taken by a side panel (the comments).
     *
     * The picture is laid out in what is left instead of being covered by the
     * panel, so opening the comments no longer hides the right third of the
     * video — the whole frame stays visible, only smaller. 0 in portrait and
     * whenever no panel is open.
     */
    landscapePanelFraction: Float = 0f,
    // ---- landscape chrome only (inert in portrait and in picture-in-picture) ----
    isLocked: Boolean,
    playbackSpeed: Float,
    onToggleLock: () -> Unit,
    onLike: () -> Unit,
    onWatchLater: () -> Unit,
    onShare: () -> Unit,
    onFollow: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onDanmakuText: (String) -> Unit,
    onSendDanmaku: () -> Unit,
    /** Focus of the landscape composer, so typing can pause the video. */
    onDanmakuComposing: (Boolean) -> Unit,
    onSelectQuality: (Quality) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    /** Toggles the landscape comments panel on the right third. */
    onToggleComments: () -> Unit,
    /** Toggles the landscape 弹幕设置 card, anchored beside the danmaku switch. */
    onToggleDanmakuSettings: () -> Unit,
    /** True while that card is open, so its button can read as active. */
    danmakuSettingsOpen: Boolean = false,
    /** Toggles the landscape 选集 list; only offered for a season. */
    onToggleEpisodes: () -> Unit = {},
    /** True while the 选集 list is open. */
    episodesOpen: Boolean = false,
) {
    // Held for the 截图 button: PixelCopy needs the SurfaceView the video is
    // actually drawn into, and that view is created inside the AndroidView
    // factory below.
    val playerViewRef = remember { AtomicReference<PlayerView?>(null) }

    // 截图 → 相册. API 26–28 need WRITE_EXTERNAL_STORAGE before the frame can be
    // written, so the capture is deferred until the system dialog answers;
    // API 29+ writes through MediaStore with no permission at all and never
    // reaches the launcher.
    val screenshotContext = LocalContext.current
    var pendingCapture by remember { mutableStateOf(false) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val view = playerViewRef.get()
        if (pendingCapture) {
            pendingCapture = false
            if (granted && view != null) {
                captureFrame(screenshotContext, view)
            } else {
                Toast.makeText(
                    screenshotContext,
                    "没有存储权限，无法保存截图",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (isFullscreen) it.fillMaxSize() else it }
            // Portrait: keep the picture — and the danmaku drawn on top of it —
            // below the status bar, so the clock and battery are never painted
            // over moving comments. Fullscreen stays edge to edge, which is the
            // whole point of fullscreen.
            //
            // Background *before* the inset, so the strip above the picture is
            // black rather than the app's page colour. With the two the other
            // way round the strip came out white in the light theme, which is
            // what "从白色改为深色" was about.
            .background(Color.Black)
            .let { if (isFullscreen) it else it.statusBarsPadding() },
    ) {
        val surfaceModifier = when {
            // Audio-only collapses the picture to nothing but keeps playback.
            state.audioOnly && !isFullscreen -> Modifier.fillMaxWidth().height(0.dp)
            // Landscape: give the side panel its share and keep the whole
            // picture in the rest, rather than letting the panel cover it.
            isFullscreen -> Modifier
                .fillMaxHeight()
                .fillMaxWidth(1f - landscapePanelFraction)
                .align(Alignment.CenterStart)
            else -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    playerViewRef.set(this)
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

        if (!state.audioOnly) {
            // Tap = show/hide the chrome (the "点击视频才展示 UI" behaviour);
            // long-press = 2x, released on lift. Audio-only has no picture to
            // tap and keeps its controls permanently.
            Box(
                modifier = surfaceModifier.pointerInput(inPip, isLocked) {
                    detectTapGestures(
                        // A locked landscape player swallows taps: that is the
                        // entire point of the lock.
                        onTap = { if (!isLocked) onToggleChrome() },
                        onLongPress = { if (!isLocked) onSpeedBoostChange(true) },
                        onPress = {
                            tryAwaitRelease()
                            if (!isLocked) onSpeedBoostChange(false)
                        },
                    )
                },
            )

            // Danmaku stays up while the controls are hidden — it is content,
            // not chrome — but never in picture-in-picture, where the window is
            // too small for it and the point is an unobstructed picture.
            if (!inPip) {
                DanmakuOverlay(
                    items = state.danmaku,
                    positionMs = { positionMs },
                    isPlaying = isPlaying,
                    videoAspectRatio = videoAspect,
                    config = danmakuConfig.copy(
                        enabled = danmakuConfig.enabled && state.danmakuEnabled,
                        speedMultiplier = if (isSpeedBoosted) SPEED_BOOST else 1f,
                    ),
                    // clipToBounds keeps comments inside the picture even when
                    // the container is a different shape from the video.
                    modifier = surfaceModifier.clipToBounds(),
                )
            }
        }

        // Top chrome: back only. Fullscreen lives at the bottom-right, where the
        // reference clients put it.
        //
        // No inset here: the whole surface is already inset in portrait (see the
        // container above), and in fullscreen the system bars are hidden.
        if (controlsVisible && !inPip && !isFullscreen) {
            Row(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = {
                        onInteract()
                        onBack()
                    },
                    modifier = Modifier.padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = if (isFullscreen) "退出全屏" else "返回",
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
        }

        // Picture-in-picture gets its own compact bar: play/pause and ±5s, and
        // only when the window has been tapped.
        if (inPip) {
            if (controlsVisible) {
                PipControls(
                    isPlaying = isPlaying,
                    onSkipBack = { onSkip(-PIP_SKIP_MS) },
                    onTogglePlay = onTogglePlay,
                    onSkipForward = { onSkip(PIP_SKIP_MS) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            return@Box
        }

        // Bottom control bar: progress, times, play/pause, fullscreen. Portrait
        // only — landscape has the richer chrome below.
        if (!state.audioOnly && controlsVisible && !isFullscreen) {
            PlayerControls(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                isFullscreen = isFullscreen,
                supportsPip = supportsPip,
                onSeek = onSeek,
                onTogglePlay = onTogglePlay,
                onEnterPip = onEnterPip,
                onToggleFullscreen = onToggleFullscreen,
                onCast = onCast,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Landscape chrome: title bar with the actions on top, the danmaku
        // composer and playback controls along the bottom, and lock/screenshot
        // down the right edge.
        if (isFullscreen && !inPip && !state.audioOnly) {
            LandscapeChrome(
                state = state,
                title = state.detail?.title.orEmpty(),
                viewCount = state.detail?.stat?.view ?: 0L,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                isLocked = isLocked,
                controlsVisible = controlsVisible,
                playbackSpeed = playbackSpeed,
                onInteract = onInteract,
                onBack = onBack,
                onTogglePlay = onTogglePlay,
                onSeek = onSeek,
                onToggleLock = onToggleLock,
                onLike = onLike,
                onWatchLater = onWatchLater,
                onShare = onShare,
                onFollow = onFollow,
                onToggleDanmaku = onToggleDanmaku,
                onDanmakuText = onDanmakuText,
                onSendDanmaku = onSendDanmaku,
                onDanmakuComposing = onDanmakuComposing,
                onSelectQuality = onSelectQuality,
                onSelectSpeed = onSelectSpeed,
                onToggleComments = onToggleComments,
                onToggleDanmakuSettings = onToggleDanmakuSettings,
                danmakuSettingsOpen = danmakuSettingsOpen,
                onToggleEpisodes = onToggleEpisodes,
                episodesOpen = episodesOpen,
                onScreenshot = {
                    onInteract()
                    val view = playerViewRef.get()
                    when {
                        view == null -> Toast.makeText(
                            screenshotContext,
                            "截图失败：画面还没准备好",
                            Toast.LENGTH_SHORT,
                        ).show()

                        needsStoragePermission(screenshotContext) -> {
                            pendingCapture = true
                            storagePermissionLauncher.launch(
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            )
                        }

                        else -> captureFrame(screenshotContext, view)
                    }
                },
            )
        }

        if (state.isLoading && controlsVisible) {
            Box(surfaceModifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = YtColors.Red)
            }
        }

        // The landscape side panel (comments) is supplied by the caller: it sits
        // in this Box, so it covers the video's right third and keeps the
        // player's chrome and the panel independent.
        if (isFullscreen && !inPip) {
            landscapeOverlay()
        }
    }
}

/**
 * YouTube-style bottom bar: elapsed time, a draggable progress track, total
 * time, play/pause, and fullscreen pushed to the far right.
 *
 * There is no separate landscape button: full screen already rotates, and two
 * controls that do the same rotation with different chrome was the source of
 * the "横屏和全屏有什么区别" confusion.
 */
@Composable
private fun PlayerControls(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isFullscreen: Boolean,
    supportsPip: Boolean,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onCast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction
        ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            // A soft scrim rather than a solid black bar: the bar used to be a
            // hard-edged `0x99000000` rectangle sitting on the picture, which
            // read as a black box pasted over the video. Fading upward keeps the
            // controls legible without a visible frame.
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        CompactSlider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { onSeek((it * durationMs).toLong()) }
                dragFraction = null
            },
            modifier = Modifier.fillMaxWidth(),
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

            // 投屏: hands the progressive stream to a DLNA renderer.
            IconButton(onClick = onCast, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.Cast,
                    contentDescription = "投屏",
                    tint = Color.White,
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

/**
 * Compact bar for the picture-in-picture window: skip back, play/pause, skip
 * forward. It only appears once the little window has been tapped.
 */
@Composable
private fun PipControls(
    isPlaying: Boolean,
    onSkipBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xB3000000))
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onSkipBack, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = Icons.Filled.Replay5,
                contentDescription = "后退 5 秒",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onTogglePlay, modifier = Modifier.size(42.dp)) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
        IconButton(onClick = onSkipForward, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = Icons.Filled.Forward5,
                contentDescription = "前进 5 秒",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Long-press playback speed. */
private const val SPEED_BOOST = 2f

/** Share of the landscape screen the comments panel takes. */
private const val LANDSCAPE_COMMENTS_WIDTH = 0.34f

/**
 * Fixed width of the floating 弹幕设置 card.
 *
 * It is anchored beside the danmaku switch rather than filling the right third
 * like the comments panel: the two were sharing one slot, so opening the
 * settings looked like opening the comments.
 */
private const val LANDSCAPE_DANMAKU_PANEL_WIDTH = 320

/** How far the panel floats above the bottom control row. */
private const val LANDSCAPE_DANMAKU_PANEL_BOTTOM_PADDING = 96

/**
 * Background for the landscape's floating panels and menus.
 *
 * Translucent on purpose: these sit over the picture, and an opaque sheet hides
 * the thing the setting is being judged against. Alpha is still high enough to
 * keep white text legible over a bright frame.
 */
private val LANDSCAPE_MENU_BACKGROUND = Color(0xD9101014)

/**
 * The landscape comments panel: the right third of the screen, over the video.
 *
 * The comments themselves are the portrait page's [CommentsSection] — same view
 * model, same callbacks — so liking, replying and paging behave identically in
 * both orientations instead of drifting apart.
 */
@Composable
private fun LandscapeCommentsPanel(
    state: PlayerUiState,
    onClose: () -> Unit,
    onReplyTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onLikeReply: (Long) -> Unit,
    onDislikeReply: (Long) -> Unit,
    onReplyTo: (Reply) -> Unit,
    onCancelReply: () -> Unit,
    onLoadMore: () -> Unit,
    onExpandThread: (Reply) -> Unit,
    onSortChange: (ReplySort) -> Unit,
    onUserClick: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "评论 ${state.replyTotalCount}",
                color = AppTheme.colors.onBackground,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭评论",
                    tint = AppTheme.colors.onBackgroundSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(AppTheme.colors.divider),
        )
        // Its own scroll container: the section is a plain Column, and stacking
        // it inside the page's scroll would nest same-direction scrollers.
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            CommentsSection(
                state = state,
                onReplyTextChange = onReplyTextChange,
                onSend = onSend,
                onLikeReply = onLikeReply,
                onDislikeReply = onDislikeReply,
                onReplyTo = onReplyTo,
                onCancelReply = onCancelReply,
                onLoadMore = onLoadMore,
                onExpandThread = onExpandThread,
                onSortChange = onSortChange,
                onUserClick = onUserClick,
            )
        }
    }
}

/** How long the chrome lingers after the last interaction. */
private const val CONTROLS_HIDE_DELAY_MS = 3_500L

/** Step used by the picture-in-picture skip buttons. */
private const val PIP_SKIP_MS = 5_000L

/** Shared with the tab composables in PlayerTabs.kt. */
internal fun formatClock(ms: Long): String {
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
 * Picture-in-picture only accepts an aspect ratio between roughly 0.42 and 2.39
 * and throws for anything else, so a portrait or ultrawide video falls back to
 * 16:9 rather than crashing the button.
 */
internal fun pipAspectRatio(videoAspect: Float?): Rational {
    val ratio = videoAspect?.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
    val clamped = ratio.coerceIn(0.5f, 2.35f)
    return if (clamped >= 1f) {
        Rational((clamped * 1000).toInt(), 1000)
    } else {
        Rational(1000, (1000 / clamped).toInt())
    }
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
    val factory = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(mediaHeaders(cookie))
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

/** The header set every media request needs; shared by both source builders. */
private fun mediaHeaders(cookie: String): Map<String, String> = buildMap {
    put("User-Agent", MEDIA_USER_AGENT)
    put("Referer", "https://www.bilibili.com/")
    put("Origin", "https://www.bilibili.com")
    if (cookie.isNotEmpty()) put("Cookie", cookie)
}

internal const val MEDIA_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

@Composable
private fun AudioOnlyPlayerBar(
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .background(Color.Black),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
            )
        }
        IconButton(
            onClick = onTogglePlay,
            modifier = Modifier
                .align(Alignment.Center)
                .size(64.dp)
                .clip(CircleShape)
                .background(Color(0x33FFFFFF)),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
    }
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
                text = "听视频（仅播放声音）",
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

// ---------------------------------------------------------------------------
// Landscape chrome
//
// Full screen here *is* landscape (see the rotation effect in PlayerScreen), so
// this is the layout a rotated phone shows: title and the four actions along
// the top, the uploader's follow button and the live viewer count under them,
// the danmaku composer and playback controls along the bottom, and
// lock / screenshot down the right edge.
//
// It deliberately reuses the view model's actions instead of re-implementing
// them, so a like from landscape and a like from portrait are the same call.
// ---------------------------------------------------------------------------

/**
 * "第23话 攻略迷宫" for the landscape 选集 list.
 *
 * Some seasons already return the number as "第23话" and some as "23", so the
 * 第/话 wrapper is only added when it is missing — otherwise the row would read
 * "第第23话话".
 */
private fun episodeLabel(episode: PgcEpisode): String {
    val number = episode.title.trim()
    val numbered = when {
        number.isEmpty() -> ""
        number.startsWith("第") -> number
        number.endsWith("话") -> "第$number"
        else -> "第${number}话"
    }
    return listOf(numbered, episode.longTitle.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}

/** Rates offered by the 倍速 menu. */
private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private fun speedLabel(rate: Float): String {
    val text = if (rate % 1f == 0f) rate.toInt().toString() else rate.toString()
    return "${text}x"
}

@Composable
private fun LandscapeChrome(
    state: PlayerUiState,
    title: String,
    viewCount: Long,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isLocked: Boolean,
    controlsVisible: Boolean,
    playbackSpeed: Float,
    onInteract: () -> Unit,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleLock: () -> Unit,
    onLike: () -> Unit,
    onWatchLater: () -> Unit,
    onShare: () -> Unit,
    onFollow: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onDanmakuText: (String) -> Unit,
    onSendDanmaku: () -> Unit,
    onDanmakuComposing: (Boolean) -> Unit,
    onSelectQuality: (Quality) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onToggleComments: () -> Unit,
    onToggleDanmakuSettings: () -> Unit,
    danmakuSettingsOpen: Boolean,
    onToggleEpisodes: () -> Unit,
    episodesOpen: Boolean,
    onScreenshot: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isLocked) {
            // Locked means locked: one way out, nothing else on screen.
            IconButton(
                onClick = {
                    onInteract()
                    onToggleLock()
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(44.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "解锁",
                    tint = Color.White,
                )
            }
            return@Box
        }

        if (!controlsVisible) return@Box

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)),
                )
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        onInteract()
                        onBack()
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "退出全屏",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = title.ifBlank { "视频" },
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (viewCount > 0) {
                    Text(
                        text = "${formatCount(viewCount)}次播放",
                        color = Color(0xCCFFFFFF),
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                ChromeIcon(
                    icon = if (state.isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    label = "点赞",
                    count = state.likeCount,
                    tint = if (state.isLiked) YtColors.Red else Color.White,
                    onClick = {
                        onInteract()
                        onLike()
                    },
                )
                ChromeIcon(
                    icon = if (state.isInWatchLater) {
                        Icons.Filled.WatchLater
                    } else {
                        Icons.Outlined.WatchLater
                    },
                    label = "稍后再看",
                    count = 0L,
                    tint = if (state.isInWatchLater) YtColors.Red else Color.White,
                    onClick = {
                        onInteract()
                        onWatchLater()
                    },
                )
                ChromeIcon(
                    icon = Icons.Filled.Share,
                    label = "分享",
                    count = 0L,
                    tint = Color.White,
                    onClick = {
                        onInteract()
                        onShare()
                    },
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
            ) {
                FollowChip(
                    // 番剧: the season is what gets followed, so the chip reads
                    // 追番 rather than claiming the uploader was subscribed.
                    following = if (state.isPgc) state.seasonFollowed else state.isFollowing,
                    isSeason = state.isPgc,
                    onClick = {
                        onInteract()
                        onFollow()
                    },
                )
                // Hidden when unknown: the endpoint that answers this was
                // unavailable, and "0人正在看" would be a lie.
                if (state.onlineCount > 0) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "${formatCount(state.onlineCount.toLong())}人正在看",
                        color = Color(0xCCFFFFFF),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(
                onClick = {
                    onInteract()
                    onToggleComments()
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Comment,
                    contentDescription = "评论",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = onScreenshot, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = "截图",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(
                onClick = {
                    onInteract()
                    onToggleLock()
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.LockOpen,
                    contentDescription = "锁定屏幕",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))),
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            var dragFraction by remember { mutableStateOf<Float?>(null) }
            val fraction = dragFraction
                ?: if (durationMs > 0) {
                    (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatClock(
                        dragFraction?.let { (it * durationMs).toLong() } ?: positionMs,
                    ),
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
                CompactSlider(
                    value = fraction,
                    onValueChange = {
                        onInteract()
                        dragFraction = it
                    },
                    onValueChangeFinished = {
                        dragFraction?.let { onSeek((it * durationMs).toLong()) }
                        dragFraction = null
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = YtColors.Red,
                        activeTrackColor = YtColors.Red,
                        inactiveTrackColor = Color(0x66FFFFFF),
                    ),
                )
                Text(
                    text = formatClock(durationMs),
                    color = Color(0xCCFFFFFF),
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        onInteract()
                        onTogglePlay()
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(
                    onClick = {
                        onInteract()
                        onToggleDanmaku()
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Subtitles,
                        contentDescription = "弹幕开关",
                        tint = if (state.danmakuEnabled) Color.White else Color(0x59FFFFFF),
                        modifier = Modifier.size(22.dp),
                    )
                }
                // 弹幕设置 sits immediately right of the 弹幕开关, where the two
                // danmaku controls belong together, and carries a 弹幕+齿轮 mark
                // so it cannot be mistaken for a generic settings button.
                DanmakuSettingsButton(
                    active = danmakuSettingsOpen,
                    onClick = {
                        onInteract()
                        onToggleDanmakuSettings()
                    },
                )
                Spacer(Modifier.width(4.dp))
                LandscapeDanmakuField(
                    text = state.danmakuText,
                    enabled = state.isLoggedIn,
                    isSending = state.isSendingDanmaku,
                    onTextChange = onDanmakuText,
                    onSend = onSendDanmaku,
                    onComposingChange = onDanmakuComposing,
                )
                Spacer(Modifier.weight(1f))
                // 选集 only exists for a season, and the entry sits where the
                // reference client puts it: bottom right, beside 倍速.
                if (state.isPgc && state.episodes.isNotEmpty()) {
                    Text(
                        text = "选集",
                        color = if (episodesOpen) YtColors.Red else Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onInteract()
                                onToggleEpisodes()
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                SpeedChip(speed = playbackSpeed, onSelect = onSelectSpeed)
                Spacer(Modifier.width(6.dp))
                QualityChip(
                    qualities = state.availableQualities,
                    current = state.currentQuality,
                    onSelect = onSelectQuality,
                )
            }
        }
    }
}

/** An action in the landscape title bar; a count is drawn only when it has one. */
@Composable
private fun ChromeIcon(
    icon: ImageVector,
    label: String,
    count: Long,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
            if (count > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = formatCount(count),
                    color = tint,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 弹幕设置 for the landscape bar: a 弹幕 glyph with a gear tucked into its corner.
 *
 * A bare gear was ambiguous — it read as "app settings" rather than "danmaku
 * settings", which is the one thing this button does.
 */
@Composable
private fun DanmakuSettingsButton(active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Subtitles,
            contentDescription = "弹幕设置",
            tint = if (active) YtColors.Red else Color.White,
            modifier = Modifier.size(22.dp),
        )
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = null,
            tint = if (active) YtColors.Red else Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 6.dp, bottom = 6.dp)
                .size(11.dp),
        )
    }
}

@Composable
private fun FollowChip(
    following: Boolean,
    onClick: () -> Unit,
    /** True for a 番剧, where the chip is 追番 rather than 关注. */
    isSeason: Boolean = false,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (following) Color(0x33FFFFFF) else YtColors.Red)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Text(
            text = when {
                isSeason && following -> "已追番"
                isSeason -> "+ 追番"
                following -> "已关注"
                else -> "+ 关注"
            },
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

/**
 * The landscape danmaku composer.
 *
 * Deliberately not [DanmakuPill]: that one is a 196dp control built for the
 * portrait header, with the colour picker and the on/off switch beside it. Here
 * the switch is already on the control row and the field has to stay narrow.
 */
@Composable
private fun LandscapeDanmakuField(
    text: String,
    enabled: Boolean,
    isSending: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onComposingChange: (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // Same rule as the portrait composer: typing pauses the video.
    LaunchedEffect(focused) { onComposingChange(focused) }

    Box(
        modifier = Modifier
            .width(210.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0x33FFFFFF))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text.isEmpty()) {
            Text(
                text = if (enabled) "发个友善的弹幕见证当下" else "登录后可发送弹幕",
                color = Color(0x99FFFFFF),
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            enabled = enabled && !isSending,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
            cursorBrush = SolidColor(Color.White),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                onComposingChange(false)
                onSend()
            }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SpeedChip(speed: Float, onSelect: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            text = if (speed == 1f) "倍速" else speedLabel(speed),
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            // Translucent: this floats over the picture in landscape.
            containerColor = LANDSCAPE_MENU_BACKGROUND,
        ) {
            SPEED_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = speedLabel(option),
                            color = if (option == speed) YtColors.Red else AppTheme.colors.onBackground,
                            fontSize = 13.sp,
                        )
                    },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun QualityChip(
    qualities: List<Quality>,
    current: Quality,
    onSelect: (Quality) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            text = current.label,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            // Translucent, like the 倍速 menu: the tier is chosen by watching
            // the picture, so the picture has to stay visible behind it.
            containerColor = LANDSCAPE_MENU_BACKGROUND,
        ) {
            // Before the quality list has arrived the current one is all that
            // is known, so the menu still opens with something in it.
            (qualities.ifEmpty { listOf(current) }).forEach { quality ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = quality.label,
                            color = if (quality == current) YtColors.Red else AppTheme.colors.onBackground,
                            fontSize = 13.sp,
                        )
                    },
                    onClick = {
                        open = false
                        onSelect(quality)
                    },
                )
            }
        }
    }
}

/**
 * Saves the current video frame into the system gallery.
 *
 * PixelCopy is used rather than re-drawing the composable: the picture lives in
 * a `SurfaceView` that Compose cannot read back. `MediaProjection` would work
 * too but needs a consent dialog for every capture, which is far too heavy for
 * a button in the player.
 *
 * The result goes to `Pictures/YtBili` through MediaStore, so it shows up in the
 * phone's gallery like any other screenshot. An earlier version wrote into the
 * app's private storage and opened a share sheet, which is why "截图之后根本
 * 没有保存到相册" — a file nothing else can see is not a screenshot.
 */
private fun captureFrame(context: Context, playerView: View?) {
    val surface = playerView?.let(::findSurfaceView)
    if (surface == null || surface.width <= 0 || surface.height <= 0) {
        Toast.makeText(context, "截图失败：画面还没准备好", Toast.LENGTH_SHORT).show()
        return
    }
    val bitmap = runCatching {
        Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
    }.getOrNull()
    if (bitmap == null) {
        Toast.makeText(context, "截图失败：内存不足", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        PixelCopy.request(
            surface,
            bitmap,
            { result ->
                if (result != PixelCopy.SUCCESS) {
                    AppLog.w("player", "screenshot: PixelCopy result=$result")
                    Toast.makeText(context, "截图失败：画面受保护或尚未就绪", Toast.LENGTH_SHORT).show()
                } else {
                    val saved = runCatching { saveToGallery(context, bitmap) }.getOrDefault(false)
                    AppLog.d("player", "screenshot saved=$saved")
                    Toast.makeText(
                        context,
                        if (saved) "截图已保存到相册" else "截图失败：无法写入相册",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }.onFailure { error ->
        AppLog.w("player", "screenshot failed ${error.javaClass.simpleName}")
        Toast.makeText(context, "截图失败", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Writes a captured frame into the gallery.
 *
 * On API 29+ this needs **no permission at all** — the app is only ever writing
 * its own MediaStore entry, and `IS_PENDING` keeps it hidden until the bytes are
 * complete. Older releases (26–28) need `WRITE_EXTERNAL_STORAGE`, which the
 * caller requests first.
 */
private fun saveToGallery(context: Context, bitmap: Bitmap): Boolean {
    val name = "YtBili_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/YtBili",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return false
    val written = runCatching {
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: false
    }.getOrDefault(false)

    if (!written) {
        runCatching { resolver.delete(uri, null, null) }
        return false
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }
    }
    return true
}

/** API 29+ writes to MediaStore without any storage permission. */
private fun needsStoragePermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED

/** Media3's `PlayerView` puts the picture in a SurfaceView somewhere below it. */
private fun findSurfaceView(view: View): SurfaceView? = when (view) {
    is SurfaceView -> view
    is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { index ->
        findSurfaceView(view.getChildAt(index))
    }
    else -> null
}
