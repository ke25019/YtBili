package com.ytstyle.bili.data.extcache

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.ytstyle.bili.data.log.AppLog
import java.io.IOException
import java.io.InputStream

/**
 * Reads a file from another app's cache through a Shizuku-opened descriptor.
 *
 * ExoPlayer needs random access for seeking, so each [open] asks the user
 * service for a descriptor already positioned at the requested byte — that is
 * `ParcelFileDescriptor.open` + `lseek` in the shell process. The app never
 * touches the path, which is why Android 11's `Android/data` restriction does
 * not apply to the read.
 *
 * This is deliberately a plain [BaseDataSource] rather than an HTTP shim: the
 * data never leaves the device and there is no protocol to get wrong.
 */
@UnstableApi
class ShizukuDataSource(
    private val shell: ShizukuShell,
    private val path: String,
) : BaseDataSource(/* isNetwork = */ false) {

    private var input: InputStream? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var uri: Uri = Uri.EMPTY
    private var bytesRemaining = 0L
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        close()

        val service = shell.serviceNow()
            ?: throw IOException("Shizuku 服务未连接（可在设置里重新授权）")
        val total = runCatching { service.length(path) }.getOrElse { error ->
            throw IOException("Shizuku 读取长度失败: ${error.javaClass.simpleName}", error)
        }
        if (total <= 0) throw IOException("无法读取缓存文件（长度 $total）")

        val start = dataSpec.position.coerceIn(0L, total)
        descriptor = try {
            service.openAt(path, start)
        } catch (error: Throwable) {
            throw IOException(
                "Shizuku 打开缓存失败: ${error.javaClass.simpleName}: ${error.message}",
                error,
            )
        }
        input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)

        uri = dataSpec.uri
        bytesRemaining = if (dataSpec.length >= 0) {
            dataSpec.length.coerceAtMost(total - start)
        } else {
            total - start
        }
        started = true
        transferStarted(dataSpec)
        AppLog.d(
            "shizuku",
            "open ${path.substringAfterLast('/')} pos=$start len=$bytesRemaining total=$total",
        )
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val stream = input ?: return C.RESULT_END_OF_INPUT
        val wanted = minOf(length.toLong(), bytesRemaining).toInt()
        val read = stream.read(buffer, offset, wanted)
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri = uri

    override fun close() {
        runCatching { input?.close() }
        runCatching { descriptor?.close() }
        input = null
        descriptor = null
        uri = Uri.EMPTY
        bytesRemaining = 0L
        if (started) {
            started = false
            transferEnded()
        }
    }

    /** One factory per track; Media3 creates a fresh data source per open. */
    class Factory(
        private val shell: ShizukuShell,
        private val path: String,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ShizukuDataSource(shell, path)
    }
}
