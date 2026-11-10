package com.ytstyle.bili.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ytstyle.bili.data.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a downloaded APK to the system installer.
 *
 * Android requires the *user* to allow installs from this app (the per-app
 * "安装未知应用" switch); the app can only take them there. The grant is checked
 * before the intent is launched so the user is guided to that switch instead of
 * getting an install that silently does nothing.
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** True when this app already holds the "install unknown apps" permission. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the per-app "install unknown apps" switch for this package. */
    fun openInstallPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { AppLog.w("update", "no settings activity for unknown sources") }
    }

    /**
     * Launches the installer for [apk].
     *
     * @return false when there is no activity to handle it, so the caller can say
     *   so rather than leaving the user on a screen that did nothing.
     */
    fun install(apk: File): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        }.onFailure {
            // The usual reason is a missing <external-files-path> entry in
            // file_paths.xml; AppLog keeps that visible instead of silent.
            AppLog.e("update", "file provider rejected ${apk.name}", it)
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }
            .onFailure { AppLog.w("update", "no installer activity ${it.javaClass.simpleName}") }
            .isSuccess
    }
}
