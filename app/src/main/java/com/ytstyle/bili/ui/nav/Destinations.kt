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

    /**
     * 编辑资料. There is no public profile-editing API, so this opens Bilibili's
     * own 账号资料 page in the embedded browser — the same approach 投稿 takes.
     */
    const val PROFILE_EDIT = "profile_edit"

    /** 稍后再看. */
    const val WATCH_LATER = "watch_later"

    /** Message centre. Native since v1.7.1 — it used to be the embedded browser. */
    const val MESSAGES = "messages"

    /** 追更: 追番 / 追剧 / 合集. Reachable from 我的收藏. */
    const val FOLLOW = "follow"

    /** One notification category (回复我的 / @我 / 收到的赞 / 系统通知). */
    const val MESSAGE_CATEGORY = "messages/{category}"

    fun messageCategory(category: com.ytstyle.bili.ui.messages.MessageCategory) =
        "messages/${category.name}"

    /** A user's space, keyed by mid. */
    const val SPACE = "space/{mid}"

    /** A live room, keyed by room id. */
    const val LIVE_ROOM = "live/{roomId}"

    /** Offline cache: choose tracks for one video. */
    const val DOWNLOAD_PICKER = "download/{bvid}"

    /** Offline cache: the list of cached videos. */
    const val DOWNLOADS = "downloads"

    /** Plays one cached video with no network. */
    const val OFFLINE_PLAYER = "offline/{id}"

    /**
     * One 私信会话's history.
     *
     * The peer's display name rides along as a route argument because the
     * message endpoints never return it: it was resolved by the session list
     * (`account/v1/user/cards`) and would otherwise have to be fetched again.
     */
    const val SESSION_DETAIL = "session/{talkerId}/{sessionType}/{peerName}"

    fun player(bvid: String) = "player/$bvid"

    /**
     * [peerName] must be URL-encoded: nicknames legitimately contain `/`, `?`
     * and `#`, all of which would otherwise split the route.
     */
    fun sessionDetail(talkerId: Long, sessionType: Int, peerName: String) =
        "session/$talkerId/$sessionType/" +
            // A blank segment does not match the route at all, so an unknown
            // nickname has to travel as a placeholder; the screen treats `-` as
            // "not resolved yet" and looks the card up itself.
            android.net.Uri.encode(peerName.ifBlank { EMPTY_NAME_PLACEHOLDER })

    /** In sync with `SessionDetailViewModel`. */
    const val EMPTY_NAME_PLACEHOLDER = "-"

    fun space(mid: Long) = "space/$mid"

    fun liveRoom(roomId: Long) = "live/$roomId"

    fun downloadPicker(bvid: String) = "download/$bvid"

    fun offlinePlayer(id: String) = "offline/$id"

    /**
     * 粉丝 / 关注 二级列表.
     *
     * [tab] is the `RelationTab` name, so the screen can open on whichever
     * counter was tapped.
     */
    const val RELATION_LIST = "relation/{mid}/{tab}"

    fun relationList(mid: Long, tab: com.ytstyle.bili.ui.relation.RelationTab) =
        "relation/$mid/${tab.name}"
}
