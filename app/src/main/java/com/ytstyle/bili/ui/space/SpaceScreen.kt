package com.ytstyle.bili.ui.space

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ytstyle.bili.data.net.DynamicEntry
import com.ytstyle.bili.data.net.FavFolder
import com.ytstyle.bili.data.net.FavResource
import com.ytstyle.bili.data.net.SpaceLikedVideo
import com.ytstyle.bili.data.net.SpaceVideo
import com.ytstyle.bili.ui.relation.RelationTab
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import com.ytstyle.bili.ui.components.LocalSystemBarIcons
import com.ytstyle.bili.ui.components.SystemBarIcons
import com.ytstyle.bili.ui.components.SystemBarsState
import com.ytstyle.bili.ui.components.ImageViewer
import com.ytstyle.bili.ui.components.formatCount
import com.ytstyle.bili.ui.components.formatDuration
import com.ytstyle.bili.ui.components.parseDurationString
import com.ytstyle.bili.ui.components.toSecureUrl
import com.ytstyle.bili.ui.components.toThumbUrl
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.theme.VipPink
import com.ytstyle.bili.ui.theme.YtColors

/** Banner strip height. The avatar hangs half-way over its lower edge. */
private val BannerHeight = 132.dp
private val AvatarSize = 72.dp
private val AvatarInset = 16.dp

/** Cards shown in the 主页 previews; the tabs show the full lists. */
private const val HomeVideoPreview = 6
private const val HomeFolderPreview = 4
private const val HomeIllustratedPreview = 4
private const val HomeBangumiPreview = 6

/** Cover height for one 追番 card; the grid is three across. */
private val BangumiCoverHeight = 104.dp

/**
 * A user's space, laid out like Bilibili's own: banner, overlapping avatar,
 * counters with the follow control, then 主页 / 投稿 / 收藏.
 *
 * Every section loads on its own, so a rejected endpoint (or a private
 * favourite list) only hides its own block — the header still renders as soon
 * as the profile arrives.
 */
@Composable
fun SpaceScreen(
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,
    onLiveClick: (Long) -> Unit,
    /** Opens the 账号资料 page; the 编辑资料 chip on one's own space. */
    onEditProfile: () -> Unit = {},
    /** Opens 粉丝 / 关注 列表 for this space. */
    onOpenRelation: (Long, RelationTab) -> Unit = { _, _ -> },
    /** Opens a 追番 season in the player as `ss<seasonId>`. */
    onSeasonClick: (Long) -> Unit = {},
    /** Opens one 图文动态 in the full reader. */
    onOpenDetail: (DynamicEntry) -> Unit = {},
    viewModel: SpaceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            kotlinx.coroutines.delay(2000)
            viewModel.dismissNotice()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        val profile = state.profile
        // The banner is drawn under the status bar, so the icon tint has to
        // follow the picture rather than the app palette: dark artwork needs
        // white icons, a bright one keeps dark icons.
        //
        // Published to the activity-wide holder. A CompositionLocal cannot do
        // this job: the only consumer is the activity *above* this screen, and
        // provided values travel downwards, so the override never arrived.
        val bannerIcons = rememberBannerSystemBarIcons(state.banner)
        DisposableEffect(bannerIcons) {
            SystemBarsState.icons = bannerIcons
            onDispose { SystemBarsState.icons = null }
        }
        CompositionLocalProvider(LocalSystemBarIcons provides bannerIcons) {
        when {
            profile != null -> SpaceBody(
                state = state,
                isLive = viewModel.isLive(),
                onBack = onBack,
                onToggleFollow = viewModel::toggleFollow,
                onLiveClick = {
                    val room = viewModel.roomId()
                    if (room > 0) onLiveClick(room)
                },
                onSelectTab = viewModel::selectTab,
                onVideoClick = onVideoClick,
                onOpenFolder = viewModel::openFolder,
                onCloseFolder = viewModel::closeFolder,
                onOpenFavourite = { media -> viewModel.openFavourite(media, onVideoClick) },
                onEditProfile = onEditProfile,
                onOpenRelation = onOpenRelation,
                onSeasonClick = onSeasonClick,
                onOpenDetail = onOpenDetail,
            )

            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = YtColors.Red)
            }

            else -> LoadError(
                message = state.error ?: "无法加载该用户",
                onRetry = viewModel::load,
            )
        }

        state.notice?.let { message ->
            NoticePill(
                message = message,
                isError = state.noticeIsError,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp),
            )
        }
        }
    }
}

/**
 * The status-bar icon tint implied by the banner image.
 *
 * Samples the top third — the part the status bar covers — and averages its
 * luminance. `null` while nothing is known yet or there is no banner, which lets
 * the app palette decide in the meantime.
 *
 * Done on a downscaled copy: 48px is plenty for an average and keeps the decode
 * off the main thread's critical path.
 */
@Composable
private fun rememberBannerSystemBarIcons(url: String): SystemBarIcons? {
    val context = LocalContext.current
    var tone by remember(url) { mutableStateOf<SystemBarIcons?>(null) }
    LaunchedEffect(url) {
        if (url.isBlank()) {
            tone = null
            return@LaunchedEffect
        }
        val bitmap = runCatching {
            val request = ImageRequest.Builder(context)
                .data(url.toSecureUrl())
                .size(48)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            (result.drawable as? BitmapDrawable)?.bitmap
        }.getOrNull()
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return@LaunchedEffect

        val rows = (bitmap.height / 3).coerceAtLeast(1)
        var sum = 0.0
        var count = 0
        for (y in 0 until rows) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                sum += (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
                count++
            }
        }
        if (count > 0) {
            tone = if (sum / count > LIGHT_BANNER_THRESHOLD) {
                SystemBarIcons.Dark
            } else {
                SystemBarIcons.Light
            }
        }
    }
    return tone
}

/** Above this average luminance a banner is "light" and keeps dark icons. */
private const val LIGHT_BANNER_THRESHOLD = 0.55

@Composable
private fun SpaceBody(
    state: SpaceUiState,
    isLive: Boolean,
    onBack: () -> Unit,
    onToggleFollow: () -> Unit,
    onLiveClick: () -> Unit,
    onSelectTab: (SpaceTab) -> Unit,
    onVideoClick: (String) -> Unit,
    onOpenFolder: (FavFolder) -> Unit,
    onCloseFolder: () -> Unit,
    onOpenFavourite: (FavResource) -> Unit,
    onEditProfile: () -> Unit,
    onOpenRelation: (Long, RelationTab) -> Unit,
    onSeasonClick: (Long) -> Unit,
    onOpenDetail: (DynamicEntry) -> Unit,
) {
    val videoCount = state.videosTotal.takeIf { it > 0 } ?: state.videos.size

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            SpaceHeader(
                state = state,
                isLive = isLive,
                onBack = onBack,
                onToggleFollow = onToggleFollow,
                onLiveClick = onLiveClick,
                onSelectTab = onSelectTab,
                onEditProfile = onEditProfile,
                onOpenRelation = onOpenRelation,
            )
        }

        when (state.tab) {
            SpaceTab.Home -> {
                if (state.videos.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "视频 $videoCount",
                            onMore = { onSelectTab(SpaceTab.Videos) },
                        )
                    }
                    item { VideoGrid(state.videos.take(HomeVideoPreview), onVideoClick) }
                }
                if (state.illustrated.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "图文 ${state.illustrated.size}",
                            onMore = { onSelectTab(SpaceTab.Dynamics) },
                        )
                    }
                    item { IllustratedRow(state.illustrated.take(HomeIllustratedPreview), onOpenDetail) }
                }
                if (state.folders.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "收藏 ${state.folders.size}",
                            onMore = { onSelectTab(SpaceTab.Favorites) },
                        )
                    }
                    item {
                        FolderGrid(
                            folders = state.folders.take(HomeFolderPreview),
                            covers = state.folderCovers,
                            onOpen = onOpenFolder,
                        )
                    }
                }
                if (state.bangumi.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "追番 ${state.bangumi.size}",
                            onMore = { onSelectTab(SpaceTab.Bangumi) },
                        )
                    }
                    item { BangumiGrid(state.bangumi.take(HomeBangumiPreview)) { onSeasonClick(it.seasonId) } }
                }
                // 最近点赞. A private list is stated as such rather than shown as
                // an empty section — the endpoint distinguishes the two (53013),
                // so the UI should too.
                if (state.likedVideosPrivate) {
                    item { SectionHeader(title = "最近点赞", onMore = null) }
                    item { EmptyHint("对方隐私设置未公开，无法查看最近点赞的视频") }
                } else if (state.likedVideos.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "最近点赞 ${state.likedVideos.size}",
                            onMore = null,
                        )
                    }
                    item {
                        VideoGrid(
                            state.likedVideos.take(HomeVideoPreview).map { it.toSpaceVideo() },
                            onVideoClick,
                        )
                    }
                }
                // Only claim emptiness once every section has actually answered.
                val everySectionLoaded = state.videosLoaded && state.foldersLoaded &&
                    state.dynamicsLoaded && state.bangumiLoaded
                if (everySectionLoaded && state.videos.isEmpty() && state.folders.isEmpty() &&
                    state.dynamics.isEmpty() && state.bangumi.isEmpty()
                ) {
                    item { EmptyHint("暂无公开内容") }
                }
            }

            SpaceTab.Dynamics -> item {
                if (state.dynamics.isEmpty()) {
                    EmptyHint("暂无公开动态")
                } else {
                    DynamicsList(state.dynamics, onVideoClick, onOpenDetail)
                }
            }

            SpaceTab.Videos -> item {
                if (state.videos.isEmpty()) {
                    EmptyHint("暂无公开投稿")
                } else {
                    VideoGrid(state.videos, onVideoClick)
                }
            }

            SpaceTab.Favorites -> {
                val open = state.openFolder
                if (open == null) {
                    item {
                        if (state.folders.isEmpty()) {
                            EmptyHint("暂无公开收藏")
                        } else {
                            FolderGrid(state.folders, state.folderCovers, onOpenFolder)
                        }
                    }
                } else {
                    item {
                        FolderHeader(
                            folder = open,
                            count = state.folderItems.size,
                            onBack = onCloseFolder,
                        )
                    }
                    if (state.folderItemsLoading) {
                        item { CenteredSpinner() }
                    } else if (state.folderItems.isEmpty()) {
                        item { EmptyHint("这个收藏夹是空的") }
                    } else {
                        items(state.folderItems, key = { it.id }) { media ->
                            FavouriteRow(media = media, onClick = { onOpenFavourite(media) })
                        }
                    }
                }
            }

            SpaceTab.Bangumi -> item {
                if (state.bangumi.isEmpty()) {
                    EmptyHint("暂无公开追番")
                } else {
                    BangumiGrid(state.bangumi) { onSeasonClick(it.seasonId) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

/**
 * Banner + avatar + counters + tabs.
 *
 * The banner and the avatar are drawn in the same [Box] so the avatar can hang
 * over the banner edge; the text block below starts after the banner and
 * indents its first lines clear of the avatar.
 */
@Composable
private fun SpaceHeader(
    state: SpaceUiState,
    isLive: Boolean,
    onBack: () -> Unit,
    onToggleFollow: () -> Unit,
    onLiveClick: () -> Unit,
    onSelectTab: (SpaceTab) -> Unit,
    /** Opens 编辑资料; only reachable on one's own space. */
    onEditProfile: () -> Unit,
    /** Opens 粉丝 / 关注 列表, from the two counters. */
    onOpenRelation: (Long, RelationTab) -> Unit,
) {
    val profile = state.profile ?: return

    Box(modifier = Modifier.fillMaxWidth()) {
        // 1. Banner. No banner in the payload -> a flat elevated surface, which
        // is also the placeholder while the image loads.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BannerHeight)
                .background(AppTheme.colors.surfaceElevated),
        ) {
            if (state.banner.isNotBlank()) {
                AsyncImage(
                    model = state.banner.toSecureUrl(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Translucent scrim behind the back arrow: the banner can be any image.
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 8.dp, top = 4.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.38f)),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = BannerHeight, start = 16.dp, end = 16.dp),
        ) {
            // 2. Name / level / membership, to the right of the avatar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 84.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = profile.name.ifEmpty { "用户 ${state.mid}" },
                    color = AppTheme.colors.onBackground,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if ((profile.official?.type ?: -1) >= 0) {
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.Filled.Verified,
                        contentDescription = profile.official?.desc.orEmpty(),
                        tint = YtColors.Red,
                        modifier = Modifier.size(15.dp),
                    )
                }
                if (profile.level > 0) {
                    Spacer(Modifier.width(6.dp))
                    LevelBadge(profile.level)
                }
                if ((profile.vip?.status ?: 0) == 1) {
                    Spacer(Modifier.width(6.dp))
                    VipBadge(
                        label = profile.vip?.label?.text?.takeIf { it.isNotBlank() } ?: "大会员",
                    )
                }
            }

            // 3. UID + IP 属地. The location is skipped entirely when unknown.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 84.dp, top = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        append("UID: ")
                        append(state.mid)
                        if (state.ipLocation.isNotBlank()) {
                            append("  ")
                            append("IP属地: ")
                            append(state.ipLocation)
                        }
                    },
                    color = AppTheme.colors.onBackgroundSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 4. Counters, with the follow control on the right of the row.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatColumn(
                    state.followers,
                    "粉丝",
                    onClick = { onOpenRelation(state.mid, RelationTab.Fans) },
                )
                Spacer(Modifier.width(16.dp))
                StatColumn(
                    state.following,
                    "关注",
                    onClick = { onOpenRelation(state.mid, RelationTab.Following) },
                )
                Spacer(Modifier.width(16.dp))
                StatColumn(state.likes, "获赞")

                Spacer(Modifier.weight(1f))

                if (isLive) {
                    LiveChip(onClick = onLiveClick)
                    Spacer(Modifier.width(8.dp))
                }

                if (state.isSelf) {
                    // Following yourself is rejected by the API, so this slot
                    // shows 编辑资料 instead — and it is now a real control.
                    // It used to be a plain label with no handler, so tapping it
                    // did nothing (the reported 编辑资料点击无反应).
                    ShapeLabel(
                        text = "编辑资料",
                        background = AppTheme.colors.chipBackground,
                        onClick = onEditProfile,
                    )
                } else {
                    FollowButton(isFollowing = state.isFollowing, onClick = onToggleFollow)
                }
            }

            // 5. Tabs.
            TabStrip(
                tabs = state.tabs,
                selected = state.tab,
                onSelect = onSelectTab,
                modifier = Modifier.padding(top = 14.dp),
            )
        }

        // The avatar is drawn last so it sits on top of the banner.
        AsyncImage(
            model = profile.face.toSecureUrl(),
            contentDescription = profile.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = AvatarInset, y = BannerHeight - AvatarSize / 2)
                .size(AvatarSize)
                .clip(CircleShape)
                .background(AppTheme.colors.surfaceElevated)
                .border(2.dp, AppTheme.colors.background, CircleShape),
        )
    }
}

@Composable
private fun StatColumn(value: Long?, label: String, onClick: (() -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // 粉丝 and 关注 open their lists; 获赞 passes null and stays a readout.
        modifier = if (onClick != null) {
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        } else {
            Modifier
        },
    ) {
        Text(
            // A null counter means "the call failed", never a real zero.
            text = value?.let { formatCount(it) } ?: "—",
            color = AppTheme.colors.onBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = label,
            color = AppTheme.colors.onBackgroundSecondary,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun LevelBadge(level: Int) {
    Text(
        text = "Lv$level",
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(YtColors.Red)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun VipBadge(label: String) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(VipPink)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun FollowButton(isFollowing: Boolean, onClick: () -> Unit) {
    ShapeLabel(
        text = if (isFollowing) "已关注" else "关注",
        background = if (isFollowing) AppTheme.colors.chipBackground else YtColors.Red,
        contentColor = if (isFollowing) AppTheme.colors.onBackground else Color.White,
        onClick = onClick,
    )
}

/** Rounded pill used by the follow button and the 编辑资料 label. */
@Composable
private fun ShapeLabel(
    text: String,
    background: Color,
    contentColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
) {
    val resolvedContent = if (contentColor == Color.Unspecified) {
        AppTheme.colors.onBackground
    } else {
        contentColor
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            color = resolvedContent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun LiveChip(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(YtColors.Red)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.LiveTv,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "直播中",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TabStrip(
    tabs: List<SpaceTab>,
    selected: SpaceTab,
    onSelect: (SpaceTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            tabs.forEach { tab ->
                val active = tab == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(tab) }
                        .padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = tab.label,
                        color = if (active) YtColors.Red else AppTheme.colors.onBackgroundSecondary,
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (active) YtColors.Red else Color.Transparent),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = AppTheme.colors.divider)
    }
}

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

@Composable
private fun SectionHeader(title: String, onMore: (() -> Unit)?) {
    // The whole row is the tap target, not just the trailing label. Only
    // 「查看更多 ›」 used to be clickable — a 12sp string with 4dp of padding —
    // so tapping the section title, which is what anybody actually aims at,
    // did nothing at all. That is what "收藏/追番点击之后没有反应" was.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onMore != null) { onMore?.invoke() }
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = AppTheme.colors.onBackground,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (onMore != null) {
            Text(
                text = "查看更多 ›",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Two-column upload grid.
 *
 * Built from chunked rows instead of a nested lazy grid: a second lazy
 * container inside [LazyColumn] is measured with infinite height and crashes.
 */
@Composable
private fun VideoGrid(videos: List<SpaceVideo>, onVideoClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        videos.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { video ->
                    SpaceVideoCard(
                        video = video,
                        modifier = Modifier.weight(1f),
                        onClick = { onVideoClick(video.bvid) },
                    )
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SpaceVideoCard(
    video: SpaceVideo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val seconds = parseDurationString(video.length)
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(bottom = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(AppTheme.colors.surfaceElevated),
        ) {
            AsyncImage(
                model = video.pic.toThumbUrl(),
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (video.play > 0) {
                Tag(
                    text = "${formatCount(video.play)}播放",
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                )
            }
            if (seconds > 0) {
                Tag(
                    text = formatDuration(seconds),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = video.title,
            color = AppTheme.colors.onBackground,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Small dark pill over a thumbnail (duration, play count, folder size). */
@Composable
private fun Tag(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(text = text, color = Color.White, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun FolderGrid(
    folders: List<FavFolder>,
    covers: Map<Long, String>,
    onOpen: (FavFolder) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        folders.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { folder ->
                    val cover = covers[folder.id] ?: covers[folder.fid]
                    FolderCard(
                        folder = folder,
                        cover = cover.orEmpty(),
                        onClick = { onOpen(folder) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FolderCard(
    folder: FavFolder,
    cover: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.clickable(onClick = onClick).padding(bottom = 14.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(AppTheme.colors.surfaceElevated),
        ) {
            if (cover.isNotBlank()) {
                AsyncImage(
                    model = cover.toThumbUrl(),
                    contentDescription = folder.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Cover not in the payload: a neutral folder glyph rather than
                // an empty box (or a made-up image).
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = AppTheme.colors.onBackgroundSecondary,
                    modifier = Modifier.align(Alignment.Center).size(26.dp),
                )
            }
            if (folder.mediaCount > 0) {
                Tag(
                    text = "${formatCount(folder.mediaCount.toLong())}个视频",
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = folder.title,
            color = AppTheme.colors.onBackground,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = AppTheme.colors.onBackgroundSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun CenteredSpinner() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = YtColors.Red)
    }
}

/**
 * 图文: the space's image dynamics.
 *
 * A tile opens the full reader, not just the picture: the preview is a way into
 * the post, and the reader is where its text and the rest of the images live.
 */
@Composable
private fun IllustratedRow(entries: List<DynamicEntry>, onOpenDetail: (DynamicEntry) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        entries.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { entry ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = 12.dp)
                            .clickable { onOpenDetail(entry) },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(AppTheme.colors.surfaceElevated),
                        ) {
                            AsyncImage(
                                model = entry.imageUrls.firstOrNull().orEmpty().toThumbUrl(),
                                contentDescription = entry.text,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = entry.text.ifBlank { entry.authorName },
                            color = AppTheme.colors.onBackground,
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * The archive shape [VideoGrid] renders, from a 最近点赞 entry.
 *
 * The liked list is archive-shaped (`stat.view`, `duration` in seconds) while the
 * upload listing is search-shaped (`play`, `length` as "12:34"), so the two can
 * share the grid only through this conversion.
 */
private fun SpaceLikedVideo.toSpaceVideo() = SpaceVideo(
    aid = aid,
    bvid = bvid,
    title = title,
    pic = pic,
    length = if (duration > 0) formatDuration(duration) else "",
    created = pubdate,
    play = stat?.view ?: 0,
    comment = stat?.danmaku ?: 0,
)

/**
 * Three-column 追番 grid; the progress wording comes from the payload.
 *
 * Tapping one opens it in the player. The player treats an `ss…` id as a 番剧
 * (see `PlayerViewModel.isPgcRequest`), so the season itself is enough — no
 * separate PGC route is needed.
 */
@Composable
private fun BangumiGrid(items: List<SpaceBangumi>, onBangumiClick: (SpaceBangumi) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        items.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { item ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = 12.dp)
                            .clickable { onBangumiClick(item) },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(BangumiCoverHeight)
                                .clip(RoundedCornerShape(6.dp))
                                .background(AppTheme.colors.surfaceElevated),
                        ) {
                            AsyncImage(
                                model = item.cover.toThumbUrl(),
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = item.title,
                            color = AppTheme.colors.onBackground,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.progress.isNotBlank()) {
                            Text(
                                text = item.progress,
                                color = AppTheme.colors.onBackgroundSecondary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The 动态 tab: text, its first image, and a tap into a shared video. */
@Composable
private fun DynamicsList(
    entries: List<DynamicEntry>,
    onVideoClick: (String) -> Unit,
    onOpenDetail: (DynamicEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        entries.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Video posts keep opening the player; everything else — a
                    // pure 图文 post in particular — opens the reader. The old
                    // version only made video posts clickable, which is why a
                    // 图文 row in 个人主页 did nothing at all.
                    .clickable {
                        if (entry.videoBvid.isNotEmpty()) {
                            onVideoClick(entry.videoBvid)
                        } else {
                            onOpenDetail(entry)
                        }
                    }
                    .padding(vertical = 10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.pubTimeLabel,
                        color = AppTheme.colors.onBackgroundSecondary,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = entry.text.ifBlank { entry.videoTitle },
                        color = AppTheme.colors.onBackground,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val thumb = entry.imageUrls.firstOrNull() ?: entry.videoCover.takeIf { it.isNotBlank() }
                if (!thumb.isNullOrBlank()) {
                    Spacer(Modifier.width(10.dp))
                    AsyncImage(
                        model = thumb.toThumbUrl(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(AppTheme.colors.surfaceElevated),
                    )
                }
            }
            HorizontalDivider(color = AppTheme.colors.divider)
        }
    }
}

/** Header shown once a folder is opened inside the 收藏 tab. */
@Composable
private fun FolderHeader(folder: FavFolder, count: Int, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "‹ 收藏夹",
            color = YtColors.Red,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = folder.title,
            color = AppTheme.colors.onBackground,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Text(
                text = "$count 个内容",
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

/** One row inside an opened folder: cover, title, play count, duration. */
@Composable
private fun FavouriteRow(media: FavResource, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(140.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(AppTheme.colors.surfaceElevated),
        ) {
            AsyncImage(
                model = media.cover.toThumbUrl(),
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (media.duration > 0) {
                Tag(
                    text = formatDuration(media.duration),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = media.title,
                color = AppTheme.colors.onBackground,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = media.upper?.name.orEmpty() +
                    if (media.cntInfo != null) {
                        " · ${formatCount(media.cntInfo.playCount)}播放"
                    } else {
                        ""
                    },
                color = AppTheme.colors.onBackgroundSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LoadError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = message, color = AppTheme.colors.onBackgroundSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        ShapeLabel(text = "重试", background = YtColors.Red, contentColor = Color.White, onClick = onRetry)
    }
}

@Composable
private fun NoticePill(message: String, isError: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(AppTheme.colors.chipBackground)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = message,
            color = if (isError) YtColors.Red else AppTheme.colors.onBackground,
            fontSize = 12.sp,
            maxLines = 2,
        )
    }
}
