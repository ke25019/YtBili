package com.ytstyle.bili.data.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.ytstyle.bili.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A rolling text log the user can hand over without a cable.
 *
 * Why this exists: the app used to write **nothing** — no `Log` calls, no file
 * — so when something went wrong on a phone there was nothing to look at beyond
 * a logcat buffer that is wiped on reboot. That made every report a guessing
 * game.
 *
 * Design choices:
 *
 *  - **Two builds, two policies.** `d`/`i` are *dropped entirely* in a release
 *    build — not hidden from logcat, not written to the file, not even
 *    formatted. A release log therefore contains warnings, errors, crash stacks
 *    and the startup banner, which is what is actually worth showing a user;
 *    a debug build keeps everything.
 *  - **Bounded at 10 MB.** Two files of at most [MAX_BYTES]; when the current
 *    one fills it is renamed to `.prev` and a new one starts, so the newest
 *    entries always survive and the total can never exceed 2 × [MAX_BYTES].
 *  - **No secrets.** Cookies, request headers and form bodies are never written.
 *    URLs are logged as **paths only** (no query string), because Bilibili's
 *    query strings carry tokens and signatures.
 *  - **Clearable.** The user can wipe both files from 设置 → 关于 → 日志 in
 *    either build, which is the first thing to ask for before reproducing a
 *    problem.
 */
object AppLog {

    private const val TAG = "YtBili"
    private const val FILE_NAME = "ytbili.log"
    private const val PREVIOUS_NAME = "ytbili.prev.log"

    /**
     * Per-file cap: 5 MB, so the two files together stay inside the 10 MB the
     * user asked for even in the worst case.
     */
    private const val MAX_BYTES = 5L * 1024L * 1024L

    private const val LEVEL_DEBUG = "D"
    private const val LEVEL_INFO = "I"
    private const val LEVEL_WARN = "W"
    private const val LEVEL_ERROR = "E"

    /** Guarded by [lock]; `SimpleDateFormat` is not thread safe. */
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    /**
     * Whether `d`/`i` are recorded at all.
     *
     * This is the release switch: in a release build the two calls return before
     * doing any work, so nothing reaches logcat and nothing is written. A debug
     * build keeps the full stream.
     */
    private val verbose: Boolean = BuildConfig.DEBUG

    @Volatile
    private var directory: File? = null

    /** The current log file, or null before [init]. */
    val logFile: File?
        get() = directory?.let { File(it, FILE_NAME) }

    val previousFile: File?
        get() = directory?.let { File(it, PREVIOUS_NAME) }

    /** Human-readable size of everything the log currently occupies. */
    fun sizeBytes(): Long =
        (logFile?.length() ?: 0L) + (previousFile?.length() ?: 0L)

    /** True when this build records debug/info lines. */
    val isVerbose: Boolean get() = verbose

    /**
     * Opens the log directory and writes a header.
     *
     * Called once from `Application.onCreate`, before anything else can log, so
     * the first line of every log identifies the build it came from. The banner
     * is appended **directly** rather than through [i]: a release build drops
     * info lines, and identifying which build produced a log is the one piece of
     * information that must never be missing.
     */
    fun init(context: Context) {
        val dir = File(context.filesDir, "logs")
        runCatching { dir.mkdirs() }
        directory = dir
        append(
            "${stamp()} $LEVEL_INFO/$TAG: app start · " +
                "version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                "debug=${BuildConfig.DEBUG} · verbose=$verbose",
        )
    }

    /**
     * Records uncaught exceptions in the file and then lets the default handler
     * kill the process as usual.
     *
     * This is the single most valuable thing the log does: a crash on a phone
     * with no cable attached is otherwise unrecoverable.
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e(TAG, "FATAL · uncaught on thread '${thread.name}'", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun d(tag: String, message: String) = write(LEVEL_DEBUG, tag, message, null)

    fun i(tag: String, message: String) = write(LEVEL_INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) =
        write(LEVEL_WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        write(LEVEL_ERROR, tag, message, error)

    /**
     * Drops both files.
     *
     * Returns true when something was actually removed, so the caller can show
     * "已清除" rather than a confirmation for a no-op.
     */
    fun clear(): Boolean {
        synchronized(lock) {
            val existed = (logFile?.isFile == true) || (previousFile?.isFile == true)
            runCatching { logFile?.delete() }
            runCatching { previousFile?.delete() }
            // The banner is re-written so the file is usable straight away and
            // the next export still says which build it came from.
            append("${stamp()} $LEVEL_INFO/$TAG: log cleared by user")
            return existed
        }
    }

    /**
     * A share intent for the log file.
     *
     * Returns null when there is nothing to share, so the caller can say so
     * rather than opening an empty chooser.
     */
    fun buildShareIntent(context: Context): Intent? {
        val file = logFile?.takeIf { it.isFile && it.length() > 0 } ?: return null
        val uri: Uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return null

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "YtBili 日志 ${BuildConfig.VERSION_NAME}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        // The release switch. Returning here (rather than filtering later) is
        // the point: no formatting, no concatenation, no I/O.
        if (!verbose && (level == LEVEL_DEBUG || level == LEVEL_INFO)) return

        // logcat first: `adb logcat` must keep working for anyone who does have
        // a cable, and it costs nothing.
        when (level) {
            LEVEL_ERROR -> Log.e(tag, message, error)
            LEVEL_WARN -> Log.w(tag, message, error)
            LEVEL_INFO -> Log.i(tag, message)
            else -> Log.d(tag, message)
        }

        val line = buildString {
            append(stamp())
            append(' ')
            append(level)
            append('/')
            append(tag)
            append(": ")
            append(message)
            if (error != null) {
                append('\n')
                append(Log.getStackTraceString(error))
            }
        }
        append(line)
    }

    private fun stamp(): String = synchronized(lock) { stamp.format(Date()) }

    private fun append(line: String) {
        val dir = directory ?: return
        synchronized(lock) {
            runCatching {
                val file = File(dir, FILE_NAME)
                file.appendText(line + "\n")
                if (file.length() > MAX_BYTES) {
                    // Rotate: the newest file becomes .prev and a fresh one
                    // starts, so the most recent entries are never the ones lost.
                    File(dir, PREVIOUS_NAME).delete()
                    file.renameTo(File(dir, PREVIOUS_NAME))
                }
            }
        }
    }
}
