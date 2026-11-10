package com.ytstyle.bili.data.cast

import com.ytstyle.bili.data.log.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A tiny HTTP server that re-serves a Bilibili stream URL to the television.
 *
 * This is not optional for casting. Bilibili's CDN URLs are signed and
 * time-limited, and several edge hosts answer `403` unless the request carries
 * a `Referer` and the desktop UA — neither of which a DLNA renderer sends. The
 * phone therefore fetches the stream itself and forwards the bytes, which also
 * keeps the session cookie off the television.
 *
 * Range requests are forwarded verbatim, so seeking on the TV still works: a
 * proxy that ignored `Range` would break every jump-to-position, and some
 * renderers refuse to start without `Accept-Ranges: bytes`.
 */
@Singleton
class LocalMediaProxy @Inject constructor() {

    private var server: ServerSocket? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var upstreamHeaders: Map<String, String> = emptyMap()

    /** The port the proxy listens on; 0 until [start] succeeds. */
    @Volatile
    var port: Int = 0
        private set

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // Media reads are long-lived; a short read timeout would cut them off.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Starts the server and returns its port, or null when it cannot bind.
     *
     * [headers] are added to every upstream request — `Referer`, `User-Agent`
     * and `Cookie` for the media host.
     */
    fun start(headers: Map<String, String>): Int? {
        if (running) return port
        upstreamHeaders = headers
        val socket = runCatching { ServerSocket(0) }.getOrElse {
            AppLog.w("cast", "proxy bind failed ${it.javaClass.simpleName}")
            return null
        }
        server = socket
        port = socket.localPort
        running = true
        worker = Thread({ acceptLoop(socket) }, "ytbili-media-proxy").apply {
            isDaemon = true
            start()
        }
        return port
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        port = 0
    }

    /**
     * The URL to hand the renderer.
     *
     * [localHost] is the phone's LAN address: `127.0.0.1` would point the
     * television at itself.
     */
    fun urlFor(upstream: String, localHost: String): String =
        "http://$localHost:$port/media?u=${URLEncoder.encode(upstream, "UTF-8")}"

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val connection = try {
                socket.accept()
            } catch (e: Exception) {
                break
            }
            // One request per connection, handled inline: renderers open a
            // handful of connections at most, and serialising them keeps the
            // byte pump simple.
            runCatching { serve(connection) }
                .onFailure { AppLog.w("cast", "proxy request failed ${it.javaClass.simpleName}") }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { connection ->
            connection.soTimeout = REQUEST_TIMEOUT_MS
            val input = BufferedInputStream(connection.getInputStream())
            val head = StringBuilder()
            var previousWasNewline = false
            // Read until the blank line that ends the header block.
            while (true) {
                val next = input.read()
                if (next == -1) break
                val char = next.toChar()
                head.append(char)
                if (char == '\n') {
                    if (previousWasNewline) break
                    previousWasNewline = true
                } else if (char != '\r') {
                    previousWasNewline = false
                }
                if (head.length > MAX_HEADER_BYTES) break
            }

            val request = ProxyHttp.parseRequest(head.toString())
            val output = connection.getOutputStream()
            if (request == null) {
                ProxyHttp.writeSimple(output, 400, "Bad Request")
                return
            }
            val upstream = ProxyHttp.upstreamUrl(request.target)
            if (upstream == null) {
                ProxyHttp.writeSimple(output, 400, "missing u parameter")
                return
            }
            forward(upstream, request, output)
        }
    }

    private fun forward(upstream: String, request: ProxyHttp.Request, output: OutputStream) {
        val builder = Request.Builder().url(upstream).get()
        upstreamHeaders.forEach { (name, value) -> builder.header(name, value) }
        request.headers["range"]?.let { builder.header("Range", it) }
        // No transparent gzip: the consumers are media demuxers, and a
        // re-encoded stream is not byte-identical to what was signed.
        builder.header("Accept-Encoding", "identity")

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                ProxyHttp.writeSimple(output, response.code, "upstream ${response.code}")
                return
            }
            val body = response.body ?: run {
                ProxyHttp.writeSimple(output, 502, "empty upstream body")
                return
            }
            val contentType = response.header("Content-Type") ?: "video/mp4"
            val contentRange = response.header("Content-Range")
            val length = body.contentLength()

            val status = if (response.code == 206 && contentRange != null) 206 else 200
            output.write(
                ProxyHttp.responseHeaders(
                    status = status,
                    contentType = contentType,
                    contentLength = length,
                    contentRange = contentRange,
                ).toByteArray(Charsets.UTF_8),
            )
            output.flush()

            body.byteStream().use { source ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 10_000
        const val MAX_HEADER_BYTES = 16 * 1024
    }
}

/**
 * The HTTP text of the proxy, split out so it can be unit tested without a
 * socket. See `LocalMediaProxyTest`.
 */
object ProxyHttp {

    data class Request(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
    )

    /** Parses a request line plus headers; null when the block is unusable. */
    fun parseRequest(raw: String): Request? {
        val lines = raw.split("\r\n", "\n").filter { it.isNotEmpty() }
        val requestLine = lines.firstOrNull()?.trim() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            headers[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim()
        }
        return Request(method = parts[0].uppercase(), target = parts[1], headers = headers)
    }

    /** The upstream URL carried by `?u=…`, decoded; null when absent. */
    fun upstreamUrl(target: String): String? {
        val query = target.substringAfter('?', "")
        if (query.isEmpty()) return null
        val value = query.split('&')
            .firstOrNull { it.startsWith("u=") }
            ?.removePrefix("u=")
            ?: return null
        val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: return null
        if (!decoded.startsWith("http://") && !decoded.startsWith("https://")) return null
        return decoded
    }

    /**
     * The response head. `Content-Length` is always present when the upstream
     * sent one — renderers use it to size their buffer, and a chunked response
     * makes several of them give up.
     */
    fun responseHeaders(
        status: Int,
        contentType: String,
        contentLength: Long,
        contentRange: String?,
    ): String = buildString {
        append(if (status == 206) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        append("Content-Type: ").append(contentType).append("\r\n")
        append("Accept-Ranges: bytes\r\n")
        if (contentRange != null) append("Content-Range: ").append(contentRange).append("\r\n")
        if (contentLength >= 0) append("Content-Length: ").append(contentLength).append("\r\n")
        // One request per connection; saying so avoids a renderer waiting for a
        // keep-alive that will never come.
        append("Connection: close\r\n\r\n")
    }

    fun writeSimple(output: OutputStream, status: Int, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            output.write(head.toByteArray(Charsets.UTF_8))
            output.write(body)
            output.flush()
        }
    }

    /**
     * The phone's own LAN address, which is what the television must dial back.
     *
     * Returns null when no site-local IPv4 interface is up, which is the normal
     * state on mobile data — casting is a same-network feature.
     */
    fun findLanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
