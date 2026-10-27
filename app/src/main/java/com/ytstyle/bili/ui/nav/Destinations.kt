package com.ytstyle.bili.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * YouTube's bottom bar is exactly four destinations — Home, Shorts,
 * Subscriptions, You. We keep that shape, mapping the middle slot onto
 * Bilibili's dynamic feed and the last onto the account/membership centre.
 */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Home(
        route = "home",
        label = "首页",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    Dynamic(
        route = "dynamic",
        label = "动态",
        selectedIcon = Icons.Filled.Subscriptions,
        unselectedIcon = Icons.Outlined.Subscriptions,
    ),
    Account(
        route = "account",
        label = "我的",
        selectedIcon = Icons.Filled.AccountCircle,
        unselectedIcon = Icons.Outlined.AccountCircle,
    ),
}

/** Secondary routes pushed on top of the shell. */
object Routes {
    const val SEARCH = "search"
    const val PLAYER = "player/{bvid}"
    const val LOGIN = "login"
    const val VIP = "vip"
    const val HISTORY = "history"
    const val FAVORITES = "favorites"
    const val UPLOAD = "upload"
    const val SETTINGS = "settings"

    /** 稍后再看. */
    const val WATCH_LATER = "watch_later"

    /** Message centre, rendered in the embedded browser. */
    const val MESSAGES = "messages"

    /** A user's space, keyed by mid. */
    const val SPACE = "space/{mid}"

    /** A live room, keyed by room id. */
    const val LIVE_ROOM = "live/{roomId}"

    fun player(bvid: String) = "player/$bvid"

    fun space(mid: Long) = "space/$mid"

    fun liveRoom(roomId: Long) = "live/$roomId"
}
