package com.ytstyle.bili.data.cast

import com.ytstyle.bili.data.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives a DLNA renderer over SOAP.
 *
 * Every call is one HTTP POST of a SOAP envelope to the device's AVTransport
 * control URL, with the action named in both the body and the `SOAPACTION`
 * header. The envelope text itself is built by [SoapEnvelope], which is unit
 * tested; this class only moves bytes.
 */
@Singleton
class DlnaController @Inject constructor() {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Points the renderer at [url] and starts playing it. */
    suspend fun play(
        renderer: DlnaRenderer,
        url: String,
        title: String,
        mimeType: String = "video/mp4",
    ): Result<Unit> {
        val metadata = DidlLite.metadata(url, title, mimeType)
        setUri(renderer, url, metadata).onFailure { return Result.failure(it) }
        return invoke(renderer, "Play", mapOf("InstanceID" to "0", "Speed" to "1"))
    }

    suspend fun setUri(
        renderer: DlnaRenderer,
        url: String,
        metadata: String,
    ): Result<Unit> = invoke(
        renderer,
        "SetAVTransportURI",
        mapOf(
            "InstanceID" to "0",
            "CurrentURI" to url,
            "CurrentURIMetaData" to metadata,
        ),
    )

    suspend fun pause(renderer: DlnaRenderer): Result<Unit> =
        invoke(renderer, "Pause", mapOf("InstanceID" to "0"))

    suspend fun stop(renderer: DlnaRenderer): Result<Unit> =
        invoke(renderer, "Stop", mapOf("InstanceID" to "0"))

    /** [seconds] is the absolute position to jump to. */
    suspend fun seek(renderer: DlnaRenderer, seconds: Long): Result<Unit> = invoke(
        renderer,
        "Seek",
        mapOf(
            "InstanceID" to "0",
            "Unit" to "REL_TIME",
            "Target" to formatDuration(seconds),
        ),
    )

    /** `PLAYING` / `PAUSED_PLAYBACK` / `STOPPED` / `TRANSITIONING`. */
    suspend fun transportState(renderer: DlnaRenderer): Result<String> = invokeRaw(
        renderer,
        "GetTransportInfo",
        mapOf("InstanceID" to "0"),
    ).map { SoapEnvelope.parse(it)["CurrentTransportState"].orEmpty() }

    private suspend fun invoke(
        renderer: DlnaRenderer,
        action: String,
        arguments: Map<String, String>,
    ): Result<Unit> = invokeRaw(renderer, action, arguments).map { }

    private suspend fun invokeRaw(
        renderer: DlnaRenderer,
        action: String,
        arguments: Map<String, String>,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (renderer.controlUrl.isBlank()) return@withContext Result.failure(IllegalStateException("设备缺少控制地址"))
        val envelope = SoapEnvelope.build(action, arguments)
        val request = Request.Builder()
            .url(renderer.controlUrl)
            .post(envelope.toRequestBody(SOAP_MEDIA_TYPE))
            .header("SOAPACTION", SoapEnvelope.soapActionHeader(action))
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    body
                } else {
                    // A UPnP device answers a rejected action with HTTP 500 and
                    // a SOAP fault carrying the real reason.
                    val fault = SoapEnvelope.faultMessage(body) ?: "设备返回 HTTP ${response.code}"
                    AppLog.w("cast", "$action rejected: ${response.code}")
                    throw IllegalStateException(fault)
                }
            }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) },
        )
    }

    /** `REL_TIME` wants `H:MM:SS`, not seconds. */
    private fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        return "%d:%02d:%02d".format(safe / 3600, (safe % 3600) / 60, safe % 60)
    }

    private companion object {
        val SOAP_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaType()
    }
}
