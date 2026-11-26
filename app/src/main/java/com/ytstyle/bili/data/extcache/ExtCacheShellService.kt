package com.ytstyle.bili.data.extcache

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import java.io.File

/**
 * The half of the Shizuku integration that runs **outside** the app process.
 *
 * Shizuku starts this class through its UserService mechanism with the shell
 * (or root, when Shizuku was started by root) identity. That identity can read
 * `/sdcard/Android/data/tv.danmaku.bili`, which an ordinary app cannot, and it
 * can hand the *opened file descriptor* back over Binder — so the app reads the
 * official client's cache without copying it and without any storage
 * permission.
 *
 * This class is loaded by name in a separate process, which is why the whole
 * class is kept by `proguard-rules.pro` and why both constructors are required
 * (Shizuku v13 tries the [Context] one first, older versions the no-arg one).
 *
 * It must not touch anything that needs the app's own process (no Hilt, no
 * DataStore): only `java.io` and `android.system`.
 */
class ExtCacheShellService : IExtCacheService.Stub {

    @Suppress("unused")
    constructor() : super()

    @Keep
    @Suppress("unused")
    constructor(context: Context) : super()

    override fun destroy() {
        // The protocol calls this when the service is no longer wanted; without
        // the exit the helper process would linger after the app is closed.
        System.exit(0)
    }

    override fun findEntries(root: String, maxDepth: Int): Array<String> =
        walk(root, maxDepth) { it.isFile && it.name == ENTRY_NAME }

    override fun findFiles(root: String, name: String, maxDepth: Int): Array<String> =
        walk(root, maxDepth) { it.isFile && it.name == name }

    override fun readText(path: String): String = File(path).readText()

    override fun length(path: String): Long = File(path).length()

    override fun exists(path: String): Boolean = File(path).exists()

    override fun openAt(path: String, offset: Long): ParcelFileDescriptor {
        val descriptor = ParcelFileDescriptor.open(
            File(path),
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
        if (offset > 0) {
            try {
                Os.lseek(descriptor.fileDescriptor, offset, OsConstants.SEEK_SET)
            } catch (error: Throwable) {
                runCatching { descriptor.close() }
                throw error
            }
        }
        return descriptor
    }

    override fun identity(): String =
        "uid=${android.os.Process.myUid()}, pid=${android.os.Process.myPid()}"

    /**
     * Breadth-first-ish walk that stops descending once [accept] matches.
     *
     * Missing/unreadable directories are skipped rather than thrown: the service
     * runs over the official client's cache, whose permissions can change while
     * a download is being written.
     */
    private fun walk(root: String, maxDepth: Int, accept: (File) -> Boolean): Array<String> {
        val base = File(root)
        if (!base.isDirectory) return emptyArray()
        val found = ArrayList<String>()
        fun visit(directory: File, depth: Int) {
            if (depth > maxDepth) return
            val children = runCatching { directory.listFiles() }.getOrNull() ?: return
            for (child in children) {
                if (accept(child)) {
                    found += child.absolutePath
                } else if (child.isDirectory) {
                    visit(child, depth + 1)
                }
            }
        }
        visit(base, 0)
        return found.toTypedArray()
    }

    private companion object {
        const val ENTRY_NAME = "entry.json"
    }
}
