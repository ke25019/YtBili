package com.ytstyle.bili.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ytstyle.bili.ui.account.AccountScreen
import com.ytstyle.bili.ui.account.AccountViewModel
import com.ytstyle.bili.ui.browser.InAppBrowserScreen
import com.ytstyle.bili.ui.components.YtTopBar
import com.ytstyle.bili.ui.download.DownloadPickerScreen
import com.ytstyle.bili.ui.download.DownloadScreen
import com.ytstyle.bili.ui.download.OfflinePlayerScreen
import com.ytstyle.bili.ui.dynamic.DynamicDetailScreen
import com.ytstyle.bili.ui.dynamic.DynamicDetailSelection
import com.ytstyle.bili.ui.dynamic.DynamicScreen
import com.ytstyle.bili.ui.favorites.FavoritesScreen
import com.ytstyle.bili.ui.follow.FollowScreen
import com.ytstyle.bili.ui.history.HistoryScreen
import com.ytstyle.bili.ui.home.HomeScreen
import com.ytstyle.bili.ui.live.LiveRoomScreen
import com.ytstyle.bili.ui.login.LoginScreen
import com.ytstyle.bili.ui.messages.MessageCategoryScreen
import com.ytstyle.bili.ui.messages.MessagesScreen
import com.ytstyle.bili.ui.messages.SessionDetailScreen
import com.ytstyle.bili.ui.nav.Routes
import com.ytstyle.bili.ui.nav.TopLevelDestination
import com.ytstyle.bili.ui.nav.YtBottomBar
import com.ytstyle.bili.ui.player.PlayerScreen
import com.ytstyle.bili.ui.search.SearchScreen
import com.ytstyle.bili.ui.settings.SettingsScreen
import com.ytstyle.bili.ui.relation.RelationListScreen
import com.ytstyle.bili.ui.relation.RelationTab
import com.ytstyle.bili.ui.space.SpaceScreen
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.update.UpdateDialog
import com.ytstyle.bili.ui.update.UpdateViewModel
import com.ytstyle.bili.ui.vip.VipScreen
import com.ytstyle.bili.ui.watchlater.WatchLaterScreen

/** Bilibili's upload studio; desktop-only, hence the embedded browser. */
private const val UPLOAD_STUDIO_URL = "https://member.bilibili.com/platform/upload/video/frame"

/**
 * 账号资料 — where the profile is actually edited. No public API exists for it,
 * so the embedded browser carries the session instead. Reachability checked
 * before use: this URL answers 200, while the `/account/york/edit` guess 404s.
 */
private const val PROFILE_EDIT_URL = "https://account.bilibili.com/account/home"

/** Message centre. */
private const val MESSAGES_URL = "https://message.bilibili.com/"

/**
 * App shell modelled on YouTube: a persistent bottom bar owns the top-level
 * destinations, while search, player, space, live and the rest are pushed over
 * the top of it.
 */
@Composable
fun YtBiliRoot(
    navController: NavHostController = rememberNavController(),
    accountViewModel: AccountViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val topLevel = TopLevelDestination.entries.firstOrNull { it.route == currentRoute }

    // The view model seeds itself from the persisted session and refreshes on
    // its own, so the shell only has to surface its messages.
    accountState.notice?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(4000)
            accountViewModel.dismissNotice()
        }
    }

    // Landscape: the shell's bars fade out while the feed is scrolled down and
    // come back on the way up, so the list gets the whole width and height. The
    // hidden flag is reset whenever the window is not landscape, so leaving
    // landscape never strands the bars invisible.
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var chromeHidden by remember { mutableStateOf(false) }
    LaunchedEffect(landscape) {
        if (!landscape) chromeHidden = false
    }
    val barsVisible = !(landscape && chromeHidden)

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        if (topLevel != null) {
            AnimatedVisibility(visible = barsVisible, enter = fadeIn(), exit = fadeOut()) {
                YtTopBar(
                    avatarUrl = accountState.avatarUrl,
                    onSearchClick = { navController.navigate(Routes.SEARCH) },
                    // Tapping your own avatar opens your space page, which is what
                    // it means everywhere else. The 我的 tab is still on the bottom
                    // bar, so nothing is lost. Signed out, it asks you to sign in.
                    onAvatarClick = {
                        val mid = accountState.mid
                        if (mid > 0) {
                            navController.navigate(Routes.space(mid))
                        } else {
                            navController.navigate(Routes.LOGIN)
                        }
                    },
                    // The bell opens Bilibili's own message centre in the embedded
                    // browser rather than re-implementing three separate feeds.
                    onNotificationsClick = { navController.navigate(Routes.MESSAGES) },
                    // Casting has no public protocol; the button explains the
                    // situation instead of doing nothing.
                    onCastClick = { accountViewModel.showCastNotice() },
                )
            }
        }

        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Home.route,
            modifier = Modifier.weight(1f),
        ) {
            composable(TopLevelDestination.Home.route) {
                HomeScreen(
                    onVideoClick = { bvid -> navController.navigate(Routes.player(bvid)) },
                    onSearchClick = { navController.navigate(Routes.SEARCH) },
                    onChromeHiddenChange = { hidden -> if (landscape) chromeHidden = hidden },
                )
            }

            composable(TopLevelDestination.Dynamic.route) {
                DynamicScreen(
                    onVideoClick = { bvid -> navController.navigate(Routes.player(bvid)) },
                    onLoginClick = { navController.navigate(Routes.LOGIN) },
                    onOpenDetail = { entry ->
                        // Hand the parsed card over so the detail page paints
                        // immediately; the id in the route is what the network
                        // refinements and the comments use.
                        DynamicDetailSelection.entry = entry
                        navController.navigate(Routes.dynamicDetail(entry.id))
                    },
                )
            }

            composable(
                route = Routes.DYNAMIC_DETAIL,
                arguments = listOf(navArgument("dynId") { type = NavType.StringType }),
            ) {
                DynamicDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSpace = { mid -> navController.navigate(Routes.space(mid)) },
                )
            }

            composable(TopLevelDestination.Account.route) {
                AccountScreen(
                    state = accountState,
                    onDismissNotice = accountViewModel::dismissNotice,
                    onLoginClick = { navController.navigate(Routes.LOGIN) },
                    onVipClick = { navController.navigate(Routes.VIP) },
                    onHistoryClick = { navController.navigate(Routes.HISTORY) },
                    onFavoritesClick = { navController.navigate(Routes.FAVORITES) },
                    onUploadClick = { navController.navigate(Routes.UPLOAD) },
                    onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                    onWatchLaterClick = { navController.navigate(Routes.WATCH_LATER) },
                    onDownloadsClick = { navController.navigate(Routes.DOWNLOADS) },
                    onOpenSpace = { mid -> navController.navigate(Routes.space(mid)) },
                )
            }

            composable(Routes.SEARCH) {
                SearchScreen(
                    onVideoClick = { bvid -> navController.navigate(Routes.player(bvid)) },
                    onUserClick = { mid -> navController.navigate(Routes.space(mid)) },
                    onLiveClick = { roomId -> navController.navigate(Routes.liveRoom(roomId)) },
                    // 番剧 / 影视 play in this app's own player instead of being
                    // handed to a browser. The route carries Bilibili's season id
                    // with an `ss` prefix so the player knows it is PGC.
                    onMediaClick = { seasonId ->
                        navController.navigate(Routes.player("ss$seasonId"))
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(navArgument("bvid") { type = NavType.StringType }),
            ) { entry ->
                val currentBvid = entry.arguments?.getString("bvid").orEmpty()
                PlayerScreen(
                    onBack = { navController.popBackStack() },
                    onUploaderClick = { mid -> navController.navigate(Routes.space(mid)) },
                    // A collection episode or a related video pushes another
                    // player entry, so Back returns to what was being watched.
                    onOpenVideo = { bvid -> navController.navigate(Routes.player(bvid)) },
                    onDownload = { navController.navigate(Routes.downloadPicker(currentBvid)) },
                )
            }

            composable(Routes.FOLLOW) {
                FollowScreen(
                    onBack = { navController.popBackStack() },
                    onVideoClick = { bvid -> navController.navigate(Routes.player(bvid)) },
                    // The player already understands an `ss<seasonId>` handle.
                    onBangumiClick = { seasonId -> navController.navigate(Routes.player("ss$seasonId")) },
                )
            }

            composable(
                route = Routes.DOWNLOAD_PICKER,
                arguments = listOf(navArgument("bvid") { type = NavType.StringType }),
            ) {
                DownloadPickerScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.DOWNLOADS) {
                DownloadScreen(
                    onBack = { navController.popBackStack() },
                    onPlay = { id -> navController.navigate(Routes.offlinePlayer(id)) },
                )
            }

            composable(
                route = Routes.OFFLINE_PLAYER,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) {
                OfflinePlayerScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = Routes.SPACE,
                arguments = listOf(navArgument("mid") { type = NavType.StringType }),
            ) {
                SpaceScreen(
                    onBack = { navController.popBackStack() },
                    onVideoClick = { bvid -> navController.navigate(Routes.player(bvid)) },
                    onLiveClick = { roomId -> navController.navigate(Routes.liveRoom(roomId)) },
                    onEditProfile = { navController.navigate(Routes.PROFILE_EDIT) },
                    onOpenRelation = { mid, tab ->
                        navController.navigate(Routes.relationList(mid, tab))
                    },
                    // 番剧 plays through the same player, addressed as `ss<id>`.
                    onSeasonClick = { seasonId -> navController.navigate(Routes.player("ss$seasonId")) },
                )
            }

            composable(
                route = Routes.RELATION_LIST,
                arguments = listOf(
                    navArgument("mid") { type = NavType.StringType },
                    navArgument("tab") { type = NavType.StringType },
                ),
            ) { entry ->
                val mid = entry.arguments?.getString("mid")?.toLongOrNull() ?: 0L
                val tab = entry.arguments?.getString("tab")
                    ?.let { name -> RelationTab.entries.firstOrNull { it.name == name } }
                    ?: RelationTab.Fans
                RelationListScreen(
                    mid = mid,
                    initialTab = tab,
                    // The screen carries both tabs, so the bar names the pair
                    // rather than whichever counter happened to be tapped.
                    title = "粉丝与关注",
                    onBack = { navController.popBackStack() },
                    onUserClick = { userMid -> navController.navigate(Routes.space(userMid)) },
                )
            }

            composable(
                route = Routes.LIVE_ROOM,
                arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
            ) {
                LiveRoomScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.LOGIN) {
                LoginScreen(
                    onLoggedIn = {
                        accountViewModel.refresh()
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onAddAccount = { navController.navigate(Routes.LOGIN) },
                    // The update dialog lives in this shell, so the settings page
                    // only has to ask it to check; the outcome opens on top.
                    onCheckUpdate = updateViewModel::checkNow,
                    onLoggedOut = {
                        accountViewModel.refresh()
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(TopLevelDestination.Home.route)
                        }
                    },
                )
            }

            composable(Routes.VIP) {
                VipScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.HISTORY) {
                HistoryScreen(
                    onVideoClick = { bvid -> navController.navigate(Routes.player(bvid)) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.FAVORITES) {
                FavoritesScreen(
                    onVideoClick = { bvid -> navController.navigate(Routes.player(bvid)) },
                    onBack = { navController.popBackStack() },
                    onFollowClick = { navController.navigate(Routes.FOLLOW) },
                )
            }

            // 投稿 runs in the embedded browser: the studio is a desktop-only
            // web app and there is no public upload API.
            composable(Routes.UPLOAD) {
                InAppBrowserScreen(
                    url = UPLOAD_STUDIO_URL,
                    title = "投稿管理",
                    warning = "上传在网页中进行，直接使用你的 B 站账号。" +
                        "大文件请保持前台，切到后台可能中断且无法续传。",
                    onBack = { navController.popBackStack() },
                )
            }

            // 编辑资料. The 编辑资料 chip on one's own space used to be a plain
            // label with no handler, so tapping it did nothing at all.
            composable(Routes.PROFILE_EDIT) {
                InAppBrowserScreen(
                    url = PROFILE_EDIT_URL,
                    title = "编辑资料",
                    warning = "资料编辑在网页中进行，直接使用你的 B 站账号。" +
                        "本应用不提供上传头像等本地功能。",
                    onBack = { navController.popBackStack() },
                )
            }

            // Native since v1.7.1: the message centre used to be the embedded
            // browser, which showed Bilibili's own chrome inside our shell.
            composable(Routes.MESSAGES) {
                MessagesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenVideo = { bvid -> navController.navigate(Routes.player(bvid)) },
                    onOpenSession = { talkerId, sessionType, peerName ->
                        navController.navigate(
                            Routes.sessionDetail(talkerId, sessionType, peerName),
                        )
                    },
                    onOpenCategory = { category ->
                        navController.navigate(Routes.messageCategory(category))
                    },
                )
            }

            /**
             * One notification category. The enum name is the route argument, so
             * an unknown value falls back to 回复我的 inside the view model
             * rather than failing to resolve the route.
             */
            composable(
                route = Routes.MESSAGE_CATEGORY,
                arguments = listOf(navArgument("category") { type = NavType.StringType }),
            ) {
                MessageCategoryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenVideo = { bvid -> navController.navigate(Routes.player(bvid)) },
                )
            }

            /**
             * One 私信会话. The peer's name is already URL-encoded by
             * [Routes.sessionDetail]; decoding it here keeps a nickname
             * containing `/` from looking like extra path segments.
             */
            composable(
                route = Routes.SESSION_DETAIL,
                arguments = listOf(
                    navArgument("talkerId") { type = NavType.StringType },
                    navArgument("sessionType") { type = NavType.StringType },
                    navArgument("peerName") { type = NavType.StringType },
                ),
            ) {
                SessionDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenVideo = { bvid -> navController.navigate(Routes.player(bvid)) },
                )
            }

            composable(Routes.WATCH_LATER) {
                WatchLaterScreen(
                    onVideoClick = { bvid -> navController.navigate(Routes.player(bvid)) },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        if (topLevel != null) {
            AnimatedVisibility(visible = barsVisible, enter = fadeIn(), exit = fadeOut()) {
                YtBottomBar(
                    current = topLevel,
                    onSelect = { destination ->
                        if (destination.route != currentRoute) {
                            navController.navigate(destination.route) {
                                popUpTo(TopLevelDestination.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        }
    }

    // In-app update. One instance for the whole app, mounted beside the shell so
    // it can open over any destination; the settings page triggers the same one
    // through `onCheckUpdate` rather than keeping a second copy of the state.
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        updateViewModel.checkOnLaunch()
    }

    UpdateDialog(
        state = updateState,
        onDismiss = updateViewModel::dismiss,
        onLater = updateViewModel::dismiss,
        onDisableAutoUpdate = updateViewModel::disableAutoUpdate,
        onOpenDetails = { url ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        },
        onDownload = updateViewModel::downloadAndInstall,
        onInstall = updateViewModel::installDownloaded,
    )
}
