package com.ytstyle.bili.data.live

import com.ytstyle.bili.data.log.AppLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * A live danmaku connection over a WebSocket.
 *
 * [client] is the app's shared OkHttp client, so the session cookie and the
 * browser UA travel with the handshake. A derived client turns the read timeout
 * off — a message queue is idle for long stretches by design, and the shared
 * 30-second read timeout would tear the connection down between comments — and
 * adds a ping so a dead network is noticed.
 *
 * The flow ends when the socket closes or fails; callers reconnect if they care.
 */
class LiveDanmakuClient(private val client: OkHttpClient) {

    /** Everything the socket can report. */
    sealed interface Event {
        data object Connected : Event

        /** The server accepted the auth packet; messages can arrive now. */
        data object Authenticated : Event

        data class Chat(val message: LiveDanmakuProtocol.LiveChatMessage) : Event

        /** 人气值, from the reply to a heartbeat. */
        data class Popularity(val value: Int) : Event

        data class Closed(val reason: String) : Event
    }

    private val socketClient: OkHttpClient by lazy {
        client.newBuilder()
            // No read timeout: silence is normal between comments.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Connects to [host]:[port] and authenticates as [uid] (0 = guest).
     *
     * [token] comes from `getDanmuInfo` and is tied to the room; it expires, so
     * a long-lived session should reconnect rather than reuse one.
     */
    fun connect(
        host: String,
        port: Int,
        roomId: Long,
        uid: Long,
        token: String,
        buvid: String = "",
    ): Flow<Event> = callbackFlow {
        // Captured before the listener: inside its callbacks the callbackFlow
        // receiver is not in scope, so `launch` would not resolve.
        val scope = this

        val request = Request.Builder()
            // The path is part of the protocol, not a typo.
            .url("wss://$host:$port/sub")
            .build()

        var socket: WebSocket? = null
        var heartbeat: Job? = null

        val listener = object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(Event.Connected)
                webSocket.send(
                    LiveDanmakuProtocol.encode(
                        operation = LiveOp.AUTH,
                        body = LiveDanmakuProtocol.authBody(roomId, uid, token, buvid),
                        // Auth and heartbeat bodies are never compressed.
                        protocolVersion = 1,
                    ).toByteString(),
                )
                heartbeat = scope.launch {
                    while (isActive) {
                        delay(HEARTBEAT_MS)
                        // The body is arbitrary; the server echoes it back.
                        webSocket.send(
                            LiveDanmakuProtocol.encode(
                                operation = LiveOp.HEARTBEAT,
                                body = "[object Object]".toByteArray(Charsets.UTF_8),
                                protocolVersion = 1,
                            ).toByteString(),
                        )
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val payload = bytes.toByteArray()
                // A message frame's body is zlib-compressed and may itself hold
                // several packets; the protocol layer walks that.
                LiveDanmakuProtocol.decode(payload).forEach { frame ->
                    when (frame.operation) {
                        LiveOp.AUTH_REPLY -> trySend(Event.Authenticated)
                        LiveOp.HEARTBEAT_REPLY ->
                            LiveDanmakuProtocol.popularity(frame)?.let { trySend(Event.Popularity(it)) }
                        LiveOp.MESSAGE ->
                            LiveDanmakuProtocol.messages(payload).forEach { trySend(Event.Chat(it)) }
                        else -> Unit
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AppLog.w("live", "danmaku socket failed ${t.javaClass.simpleName}")
                trySend(Event.Closed(t.message?.takeIf { it.isNotBlank() } ?: "连接失败"))
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(Event.Closed(reason.ifBlank { "连接已关闭" }))
                close()
            }
        }

        socket = socketClient.newWebSocket(request, listener)
        awaitClose {
            heartbeat?.cancel()
            // cancel(), not close(): the caller has already gone away.
            socket?.cancel()
        }
    }

    private companion object {
        /** The protocol asks for one every 30s; a late one drops the socket. */
        const val HEARTBEAT_MS = 30_000L

        /** OkHttp-level ping, independent of the protocol heartbeat. */
        const val PING_SECONDS = 20L
    }
}

@Module
@InstallIn(SingletonComponent::class)
object LiveDanmakuModule {

    @Provides
    @Singleton
    fun provideLiveDanmakuClient(client: OkHttpClient): LiveDanmakuClient =
        LiveDanmakuClient(client)
}
