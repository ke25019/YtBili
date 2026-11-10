package com.ytstyle.bili.data.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.ytstyle.bili.data.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds DLNA renderers on the local network.
 *
 * SSDP is a multicast UDP search: the app sends one `M-SEARCH` to
 * 239.255.255.250:1900 and every device that matches answers with a URL to its
 * description document, which is then fetched to learn the friendly name and
 * the AVTransport control endpoint.
 *
 * Two Android details are load-bearing:
 *
 *  - **A multicast lock is required.** Without `WifiManager.MulticastLock` the
 *    Wi-Fi stack filters multicast packets and the search silently finds
 *    nothing on many devices. It needs `CHANGE_WIFI_MULTICAST_STATE`.
 *  - The description fetch uses its **own** OkHttp client. The app's shared
 *    client attaches the Bilibili cookie, UA and Referer to every request, and
 *    none of that belongs on a request to a television in the living room.
 */
@Singleton
class SsdpDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Searches for [timeoutMs] and returns every usable renderer.
     *
     * A device that answers but whose description cannot be read is dropped:
     * without a control URL there is nothing to drive.
     */
    suspend fun discover(timeoutMs: Long = 3_500L): List<DlnaRenderer> = withContext(Dispatchers.IO) {
        val responses = search(timeoutMs)
        if (responses.isEmpty()) return@withContext emptyList()

        // `distinctBy` before the fetches: several search targets on one device
        // answer with the same LOCATION, and each fetch costs a round trip.
        responses.distinctBy { it.location }
            .mapNotNull { fetchRenderer(it.location) }
            .distinctBy { it.controlUrl }
    }

    private fun search(timeoutMs: Long): List<SsdpProtocol.SsdpResponse> {
        val found = ArrayList<SsdpProtocol.SsdpResponse>()
        val lock = acquireMulticastLock()
        val socket = runCatching { DatagramSocket().apply { soTimeout = RECEIVE_TIMEOUT_MS } }
            .getOrElse {
                AppLog.w("cast", "ssdp socket failed ${it.javaClass.simpleName}")
                lock?.release()
                return emptyList()
            }

        try {
            val payload = SsdpProtocol.searchRequest().toByteArray(Charsets.UTF_8)
            val group = InetAddress.getByName(SsdpProtocol.MULTICAST_ADDRESS)
            val packet = DatagramPacket(payload, payload.size, group, SsdpProtocol.MULTICAST_PORT)

            // UDP is lossy; the spec's own example sends the search twice.
            repeat(2) { runCatching { socket.send(packet) } }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val incoming = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(incoming)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                SsdpProtocol
                    .parseResponse(String(incoming.data, 0, incoming.length, Charsets.UTF_8))
                    ?.let(found::add)
            }
        } finally {
            runCatching { socket.close() }
            lock?.release()
        }
        return found
    }

    private fun fetchRenderer(descriptionUrl: String): DlnaRenderer? = runCatching {
        val request = Request.Builder().url(descriptionUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            DlnaDescription.parse(body, descriptionUrl)
        }
    }.getOrElse {
        AppLog.w("cast", "description failed ${it.javaClass.simpleName}")
        null
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        wifi.createMulticastLock("ytbili-dlna").apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    private companion object {
        /** Short: replies arrive within milliseconds; the loop waits for more. */
        const val RECEIVE_TIMEOUT_MS = 700
    }
}
