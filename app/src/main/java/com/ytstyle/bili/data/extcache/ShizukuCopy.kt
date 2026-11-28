package com.ytstyle.bili.data.extcache

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException

/**
 * Copies one file out of another app's cache through the Shizuku service.
 *
 * Used only as a fallback: the normal playback path streams the official file
 * through a descriptor, without copying. If that live read fails on a particular
 * device (or the file keeps working only at offset 0, where no `lseek` is
 * needed), the user is offered "复制到本机后播放", which lands the two tracks in
 * the app's private storage and plays them like any other download.
 *
 * The copy is written to a `.part` file first, so an interrupted copy never
 * leaves a truncated file that looks playable.
 */
internal object ShizukuCopy {

    fun copy(service: IExtCacheService, source: String, destination: File): Long {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, destination.name + ".part")
        if (part.exists()) part.delete()

        try {
            service.openAt(source, 0).use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    part.outputStream().use { output ->
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    }
                }
            }
            if (!part.renameTo(destination)) {
                part.copyTo(destination, overwrite = true)
                part.delete()
            }
            return destination.length()
        } catch (error: Throwable) {
            part.delete()
            throw if (error is IOException) error else IOException(error.message, error)
        }
    }
}
