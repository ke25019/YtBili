package com.ytstyle.bili.ui.messages

import com.ytstyle.bili.data.log.AppLog
import com.ytstyle.bili.data.net.MessageApi
import com.ytstyle.bili.data.net.MsgEnvelope
import com.ytstyle.bili.data.net.VcMessageApi
import com.ytstyle.bili.data.session.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the message centre feeds.
 *
 * Every call needs the login cookie, so failures are surfaced verbatim: the
 * screen distinguishes `-101` (未登录) from a network error from an empty page,
 * because "暂无消息" and "未登录" must not look the same to the user.
 */
@Singleton
class MessagesRepository @Inject constructor(
    private val api: MessageApi,
    private val vcApi: VcMessageApi,
    private val session: SessionStore,
) {

    /**
     * One page of a session's history, oldest first.
     *
     * [endSeqno] walks backwards: pass the previous page's `minSeqno` to get
     * the messages just before it. The session's own `session_type` (1 用户,
     * 2 粉丝团) is required — the request-level filter means something else.
     */
    suspend fun sessionMessages(
        talkerId: Long,
        sessionType: Int,
        endSeqno: Long? = null,
    ): MsgResult<SessionMessagePage> {
        val selfUid = runCatching { session.midFlow.first() }.getOrDefault(0L)
        return vcRequest(
            call = "fetch_session_msgs",
            block = {
                vcApi.sessionMessages(
                    talkerId = talkerId,
                    sessionType = sessionType,
                    endSeqno = endSeqno,
                )
            },
            parse = { parseSessionMessages(it.data, selfUid) },
        )
    }

    /**
     * Display name and avatar for one conversation's peer.
     *
     * The session row already carries these, but the route into a conversation
     * may have been built from a row whose card lookup failed — and a chat page
     * with no name and no avatar is what "私信无法加载头像和名称" was. Looking it
     * up here makes the screen self-sufficient.
     */
    suspend fun peerCard(talkerId: Long): MsgResult<Pair<String, String>> {
        if (talkerId <= 0) return MsgResult.Err("无效的会话")
        return vcRequest(
            call = "user_cards",
            block = { vcApi.userCards(talkerId.toString()) },
            parse = { parseUserCard(it.data, talkerId) ?: ("" to "") },
        )
    }

    /**
     * Marks a session read.
     *
     * Best effort by design: the message list has already rendered by the time
     * this runs, and a failure only means the unread badge survives.
     */
    suspend fun markSessionRead(
        talkerId: Long,
        sessionType: Int,
        ackSeqno: Long? = null,
    ): MsgResult<Unit> {
        val csrf = runCatching { session.currentCsrf() }.getOrDefault("")
        if (csrf.isEmpty()) return MsgResult.Err("请先登录", MSG_CODE_NOT_LOGGED_IN)
        return vcRequest(
            call = "update_ack",
            block = {
                vcApi.updateAck(
                    talkerId = talkerId,
                    sessionType = sessionType,
                    ackSeqno = ackSeqno,
                    csrfToken = csrf,
                    csrf = csrf,
                )
            },
            parse = { Unit },
        )
    }

    suspend fun unread(): MsgResult<MsgUnread> =
        request(block = { api.unread() }, parse = { MessageParser.unread(it.data) })

    /**
     * The 私信会话 page, with blank names filled in from the user-card endpoint.
     *
     * The session call is the only thing that can fail the result: a failed card
     * lookup still returns the sessions, just nameless, because the avatar
     * fallback already renders a row without a resolved name and hiding the
     * whole list over a cosmetic call would be a worse trade.
     */
    suspend fun sessions(): MsgResult<List<DirectMessageSession>> {
        val result = vcRequest(
            call = "get_sessions",
            block = { sessionsEnvelope() },
            parse = { parseSessions(it.data) },
        )
        return when (result) {
            is MsgResult.Ok -> MsgResult.Ok(withCards(result.value))
            is MsgResult.Err -> result
        }
    }

    /** `-509` is transient rate limiting, so the call is retried once. */
    private suspend fun sessionsEnvelope(): MsgEnvelope {
        val first = vcApi.sessions(size = SESSION_PAGE_SIZE)
        if (first.code != MSG_CODE_RATE_LIMITED) return first
        AppLog.w("msg", "get_sessions code=$MSG_CODE_RATE_LIMITED, retrying once")
        delay(RATE_LIMIT_RETRY_MS)
        return vcApi.sessions(size = SESSION_PAGE_SIZE)
    }

    /**
     * Resolves the names/faces the session payload left blank.
     *
     * Two card endpoints are tried, documented one first:
     *
     *  1. `x/polymer/pc-electron/v1/user/cards` on api.bilibili.com. Its payload
     *     is **documented** as `data` keyed by mid-string with `{mid, name,
     *     face}` — the shape [mergeUserCards] was written for.
     *  2. `account/v1/user/cards` on api.vc.bilibili.com, which the session list
     *     lives next to but whose response shape is undocumented. It stays as a
     *     fallback so a change in either direction cannot leave rows nameless.
     *
     * Both paths were checked anonymously (HTTP 200 `code=-101 账号未登录`), so
     * neither is a dead URL; only a logged-in session can say which one answers
     * with usable names, and that has not been possible to test here.
     *
     * Names are cosmetic: neither failure hides the conversation list.
     */
    private suspend fun withCards(sessions: List<DirectMessageSession>): List<DirectMessageSession> {
        val uids = sessions
            .filter { it.talkerId > 0 && (it.name.isBlank() || it.face.isBlank()) }
            .map { it.talkerId }
            .distinct()
            .take(CARD_UID_LIMIT)
        if (uids.isEmpty()) return sessions
        val joined = uids.joinToString(",")

        val documented = fetchCards("user_cards_polymer") {
            api.userCardsPolymer(joined)
        }
        val merged = if (documented != null) mergeUserCards(sessions, documented) else sessions
        if (merged.any { it.name.isNotBlank() } || merged.none { it.name.isBlank() }) {
            AppLog.d(
                "msg",
                "user_cards_polymer resolved ${merged.count { it.name.isNotBlank() }}/${sessions.size}",
            )
            return merged
        }

        // Documented call gave nothing usable: fall back to the VC-hosted one.
        val legacy = fetchCards("user_cards") { vcApi.userCards(joined) } ?: return merged
        val legacyMerged = mergeUserCards(merged, legacy)
        val resolved = legacyMerged.count { it.name.isNotBlank() }
        // Counts only — this line settles "私信没有头像和名称" without ever
        // putting a nickname in the log.
        AppLog.d("msg", "user_cards resolved $resolved/${sessions.size} names")
        if (resolved == 0 && sessions.isNotEmpty()) {
            AppLog.w("msg", "user_cards returned no usable names")
        }
        return legacyMerged
    }

    /** Runs one card lookup; null when it failed or answered with no payload. */
    private suspend fun fetchCards(
        call: String,
        block: suspend () -> MsgEnvelope,
    ): JsonElement? = try {
        val envelope = block()
        if (envelope.code != 0) {
            // Never log the uid list itself.
            AppLog.w("msg", "$call code=${envelope.code}")
            null
        } else {
            envelope.data
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w("msg", "$call failed ${e.javaClass.simpleName}")
        null
    }

    /**
     * One page of [category].
     *
     * [cursor] is null for the first page; the server ignores `id`/`time` when
     * they are absent, which is why they are only sent once a cursor exists.
     */
    suspend fun page(category: MessageCategory, cursor: MessageCursor?): MsgResult<MessagePage> =
        request(
            block = { fetch(category, cursor) },
            parse = { MessageParser.page(category, it.data) },
        )

    private suspend fun fetch(category: MessageCategory, cursor: MessageCursor?): MsgEnvelope {
        val id = cursor?.id?.takeIf { it > 0 }
        val time = cursor?.time?.takeIf { it > 0 }
        return when (category) {
            // The paging timestamp parameter is named after the feed
            // (`reply_time`, `at_time`, `like_time`) in the web API, while
            // `time` is what the cursor is called in the response. Sending both
            // costs nothing and keeps paging working whichever one the server
            // honours.
            MessageCategory.REPLY -> api.replies(id = id, time = time, replyTime = time)
            MessageCategory.AT -> api.mentions(id = id, time = time, atTime = time)
            MessageCategory.LIKE -> api.likes(id = id, time = time, likeTime = time)
            MessageCategory.SYSTEM -> api.systemMessages(id = id, time = time)
        }
    }

    private suspend fun <T> request(
        block: suspend () -> MsgEnvelope,
        parse: (MsgEnvelope) -> T,
    ): MsgResult<T> = withContext(Dispatchers.IO) {
        try {
            val envelope = block()
            if (envelope.code == 0) {
                MsgResult.Ok(parse(envelope))
            } else {
                MsgResult.Err(
                    envelope.message.ifBlank { "加载失败 (code=${envelope.code})" },
                    envelope.code,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            MsgResult.Err(
                if (e.code() == 404) "服务器没有这个接口 (HTTP 404)" else "请求失败 (HTTP ${e.code()})",
                -1,
            )
        } catch (e: Exception) {
            MsgResult.Err(e.message?.takeIf { it.isNotBlank() } ?: "网络异常，请稍后重试")
        }
    }

    /**
     * Same envelope handling as [request], for the VC host.
     *
     * [call] is a fixed endpoint name, never a URL: the log is meant to be
     * shareable and Bilibili's query strings are not. Cancellation is rethrown
     * so an abandoned screen refresh is not reported to the user as a network
     * failure.
     */
    private suspend fun <T> vcRequest(
        call: String,
        block: suspend () -> MsgEnvelope,
        parse: (MsgEnvelope) -> T,
    ): MsgResult<T> = withContext(Dispatchers.IO) {
        try {
            val envelope = block()
            if (envelope.code == 0) {
                MsgResult.Ok(parse(envelope))
            } else {
                AppLog.w("msg", "$call code=${envelope.code}")
                MsgResult.Err(
                    envelope.message.ifBlank { "加载失败 (code=${envelope.code})" },
                    envelope.code,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            AppLog.w("msg", "$call HTTP ${e.code()}")
            MsgResult.Err(
                if (e.code() == 404) "服务器没有这个接口 (HTTP 404)" else "请求失败 (HTTP ${e.code()})",
                -1,
            )
        } catch (e: Exception) {
            // Class name only: an exception message can carry the request URL,
            // and the URL carries the query string.
            AppLog.w("msg", "$call failed ${e.javaClass.simpleName}")
            MsgResult.Err(e.message?.takeIf { it.isNotBlank() } ?: "网络异常，请稍后重试")
        }
    }

    private companion object {
        /**
         * The documented default is 20; 50 fills the visible first screen while
         * staying well under the endpoint's cap of 100.
         */
        const val SESSION_PAGE_SIZE = 50

        /** `uids` is comma separated; the endpoint is asked for at most this many. */
        const val CARD_UID_LIMIT = 50

        /** Backoff before the single `-509` retry. */
        const val RATE_LIMIT_RETRY_MS = 800L
    }
}
