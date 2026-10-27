package com.ytstyle.bili.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.ytstyle.bili.ui.dynamic.DynamicScreen
import com.ytstyle.bili.ui.favorites.FavoritesScreen
import com.ytstyle.bili.ui.history.HistoryScreen
import com.ytstyle.bili.ui.home.HomeScreen
import com.ytstyle.bili.ui.live.LiveRoomScreen
import com.ytstyle.bili.ui.login.LoginScreen
import com.ytstyle.bili.ui.nav.Routes
import com.ytstyle.bili.ui.nav.TopLevelDestination
import com.ytstyle.bili.ui.nav.YtBottomBar
import com.ytstyle.bili.ui.player.PlayerScreen
import com.ytstyle.bili.ui.search.SearchScreen
import com.ytstyle.bili.ui.settings.SettingsScreen
import com.ytstyle.bili.ui.space.SpaceScreen
import com.ytstyle.bili.ui.theme.AppTheme
import com.ytstyle.bili.ui.vip.VipScreen
import com.ytstyle.bili.ui.watchlater.WatchLaterScreen

/** Bilibili's upload studio; desktop-only, hence the embedded browser. */
private const val UPLOAD_STUDIO_URL = "https://member.bilibili.com/platform/upload/video/frame"

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
) {
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val topLevel = TopLevelDestination.entries.firstOrNull { it.route == currentRoute }

    accountViewModel.refreshIfNeeded()

    Column(modifier = Modifier.fillMaxSize().background(AppTheme.colors.background)) {
        if (topLevel != null) {
            YtTopBar(
                avatarUrl = accountState.avatarUrl,
                onSearchClick = { navController.navigate(Routes.SEARCH) },
                onAvatarClick = { navController.navigate(TopLevelDestination.Account.route) },
                // The bell opens Bilibili's own message centre in the embedded
                // browser rather than re-implementing three separate feeds.
                onNotificationsClick = { navController.navigate(Routes.MESSAGES) },
                // Casting has no public protocol; the button explains the
                // situation instead of doing nothing.
                onCastClick = { accountViewModel.showCastNotice() },
            )
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
                )
            }

            composable(TopLevelDestination.Dynamic.route) {
                DynamicScreen(
                    onVideoClick = { bvid -> navController.navigate(Routes.player(bvid)) },
                    onLoginClick = { navController.navigate(Routes.LOGIN) },
                )
            }

            composable(TopLevelDestination.Account.route) {
                AccountScreen(
                    onLoginClick = { navController.navigate(Routes.LOGIN) },
                    onVipClick = { navController.navigate(Routes.VIP) },
                    onHistoryClick = { navController.navigate(Routes.HISTORY) },
                    onFavoritesClick = { navController.navigate(Routes.FAVORITES) },
                    onUploadClick = { navController.navigate(Routes.UPLOAD) },
                    onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                    onWatchLaterClick = { navController.navigate(Routes.WATCH_LATER) },
                )
            }

            composable(Routes.SEARCH) {
                SearchScreen(
                    onVideoClick = { bvid -> navController.navigate(Routes.player(bvid)) },
                    onUserClick = { mid -> navController.navigate(Routes.space(mid)) },
                    onLiveClick = { roomId -> navController.navigate(Routes.liveRoom(roomId)) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(navArgument("bvid") { type = NavType.StringType }),
            ) {
                PlayerScreen(
                    onBack = { navController.popBackStack() },
                    onUploaderClick = { mid -> navController.navigate(Routes.space(mid)) },
                )
            }

            composable(
                route = Routes.SPACE,
                arguments = listOf(navArgument("mid") { type = NavType.StringType }),
            ) {
                SpaceScreen(
                    onBack = { navController.popBackStack() },
                    onVideoClick = { bvid -> navController.navigate(Routes.player(bvid)) },
                    onLiveClick = { roomId -> navController.navigate(Routes.liveRoom(roomId)) },
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

            composable(Routes.MESSAGES) {
                InAppBrowserScreen(
                    url = MESSAGES_URL,
                    title = "消息",
                    warning = null,
                    onBack = { navController.popBackStack() },
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
