package com.ytstyle.bili.data.extcache

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.ytstyle.bili.BuildConfig
import com.ytstyle.bili.data.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/** What the settings page needs to explain the Shizuku state. */
data class ShizukuState(
    val installed: Boolean = false,
    val authorized: Boolean = false,
    val connected: Boolean = false,
    val identity: String = "",
    val error: String? = null,
) {
    /** True when a scan through Shizuku can actually run. */
    val usable: Boolean get() = installed && authorized
}

/**
 * Client side of the Shizuku bridge.
 *
 * Shizuku (or Sui) starts [ExtCacheShellService] as a separate process with the
 * shell/root identity; that process opens the official client's cache files and
 * returns `ParcelFileDescriptor`s over Binder. The app therefore reads the files
 * **without copying them** and without the "all files access" permission — a
 * descriptor is already an authorisation.
 *
 * Everything here is defensive: Shizuku may be absent, stopped after a reboot,
 * or out of date, and none of those may crash the app. Every call into the
 * library is wrapped, and the worst outcome is "the mode is unavailable".
 */
@Singleton
class ShizukuShell @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _state = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    /** The bound service, or null while unbound/dead. */
    private val serviceFlow = MutableStateFlow<IExtCacheService?>(null)

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            _state.update { it.copy(authorized = granted) }
            if (granted) {
                bind()
                AppLog.i("shizuku", "permission granted")
            } else {
                AppLog.i("shizuku", "permission denied")
            }
        }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        // Must not block the binder callback: refresh only reads state and may
        // bind the user service, which is cheap but does talk to Shizuku.
        refresh()
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        serviceFlow.value = null
        _state.value = ShizukuState(installed = false)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = binder?.let { IExtCacheService.Stub.asInterface(it) }
            serviceFlow.value = service
            val identity = runCatching { service?.identity() }.getOrNull().orEmpty()
            _state.update { it.copy(connected = service != null, identity = identity) }
            AppLog.i("shizuku", "user service connected: ${identity.ifBlank { "unknown" }}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceFlow.value = null
            _state.update { it.copy(connected = false, identity = "") }
            AppLog.w("shizuku", "user service disconnected")
        }
    }

    init {
        runCatching {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListener(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
        }
        refresh()
    }

    /** Re-reads install/authorisation state and binds the service when possible. */
    fun refresh() {
        val installed = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!installed) {
            serviceFlow.value = null
            _state.value = ShizukuState(installed = false)
            return
        }
        val authorized = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        _state.update { it.copy(installed = true, authorized = authorized, error = null) }
        if (authorized) bind()
    }

    /** Asks Shizuku for its per-app permission; the result arrives in the listener. */
    fun requestPermission() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            refresh()
            return
        }
        if (runCatching { Shizuku.checkSelfPermission() }
                .getOrDefault(PackageManager.PERMISSION_DENIED) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            bind()
            return
        }
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure {
                _state.update { state ->
                    state.copy(error = "无法请求 Shizuku 权限：${it.message ?: it.javaClass.simpleName}")
                }
            }
    }

    /**
     * Returns a connected service, binding and waiting if necessary.
     *
     * Called from `Dispatchers.IO` paths only. A null result means "this mode
     * cannot run right now", never an exception.
     */
    suspend fun awaitService(timeoutMs: Long = 15_000): IExtCacheService? {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return null
        val authorized = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!authorized) return null

        serviceFlow.value?.let { return it }
        bind()
        return withTimeoutOrNull(timeoutMs) { serviceFlow.first { it != null } }
    }

    /** Non-suspending accessor for the media data source. */
    fun serviceNow(): IExtCacheService? = serviceFlow.value

    private fun bind() {
        if (serviceFlow.value != null) return
        runCatching {
            Shizuku.bindUserService(userServiceArgs(), connection)
        }.onFailure {
            AppLog.w("shizuku", "bindUserService failed: ${it.javaClass.simpleName}")
            _state.update { state ->
                state.copy(error = "无法连接 Shizuku 服务：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ExtCacheShellService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("extcache")
            .debuggable(BuildConfig.DEBUG)
            .version(SERVICE_VERSION)

    private companion object {
        const val PERMISSION_REQUEST_CODE = 0x5A17

        /**
         * Bump when [ExtCacheShellService]'s AIDL changes: Shizuku keeps one
         * user-service process per tag+version, so a new version starts a fresh
         * process instead of reusing one with the old interface.
         */
        const val SERVICE_VERSION = 1
    }
}
