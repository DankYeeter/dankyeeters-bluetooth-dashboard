package dev.dankyeeter.btdashboard.system.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Detects and manages the Shizuku connection.
 *
 * All Shizuku calls are wrapped: when the app is not installed the
 * `rikka.shizuku` classes still load, but every call throws
 * `IllegalStateException`/`NoClassDefFoundError` variants depending on version.
 * We never let that reach the UI — worst case we report [ShizukuState.Error]
 * and the app runs in session mode.
 */
class ShizukuManager(private val context: Context) {

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.Error("not checked"))
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            _state.value = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                readyState()
            } else {
                ShizukuState.PermissionDenied
            }
        }

    /** Registers listeners. Call from `Application.onCreate` or the activity. */
    fun register() = runSafely {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun unregister() = runSafely {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }

    /** Recomputes [state] from scratch. Cheap; call on every resume. */
    fun refresh() {
        _state.value = computeState()
    }

    private fun computeState(): ShizukuState = try {
        when {
            !isShizukuInstalled() -> ShizukuState.NotInstalled
            !Shizuku.pingBinder() -> ShizukuState.InstalledNotRunning
            Shizuku.isPreV11() -> ShizukuState.Error("Shizuku version is too old (pre-v11)")
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> readyState()
            Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.PermissionDenied
            else -> ShizukuState.NotAuthorized
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Shizuku state check failed", t)
        ShizukuState.Error(t.message ?: t::class.java.simpleName)
    }

    private fun readyState(): ShizukuState = try {
        ShizukuState.Ready(uid = Shizuku.getUid(), apiVersion = Shizuku.getVersion())
    } catch (t: Throwable) {
        ShizukuState.Error(t.message ?: "could not read Shizuku identity")
    }

    /** Triggers the Shizuku permission dialog. Result arrives via [state]. */
    fun requestPermission(requestCode: Int = PERMISSION_REQUEST_CODE) = runSafely {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(requestCode)
        }
    }

    fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private inline fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku call failed", t)
            _state.value = ShizukuState.Error(t.message ?: "Shizuku unavailable")
        }
    }

    companion object {
        private const val TAG = "ShizukuManager"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val PERMISSION_REQUEST_CODE = 4711

        /** No Play Store in this project — the onboarding links here. */
        const val SHIZUKU_RELEASES_URL = "https://github.com/RikkaApps/Shizuku/releases"
    }
}
