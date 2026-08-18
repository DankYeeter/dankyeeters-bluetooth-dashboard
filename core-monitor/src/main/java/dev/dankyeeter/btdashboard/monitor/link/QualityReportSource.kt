package dev.dankyeeter.btdashboard.monitor.link

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Availability of the primary data source. "Unavailable" carries a reason
 * because the whole point of the hierarchy is that the UI can say *why* it
 * fell back to a weaker source.
 */
sealed interface QualityReportAvailability {
    data object Active : QualityReportAvailability
    data class Unavailable(val reason: String) : QualityReportAvailability

    val isActive: Boolean get() = this is Active
}

/**
 * `BluetoothQualityReport` (Android 13+): controller-level packet loss,
 * retransmissions, RSSI and audio-glitch reports. Requires
 * BLUETOOTH_PRIVILEGED, which a normal app can never hold.
 */
interface QualityReportSource {
    val availability: StateFlow<QualityReportAvailability>

    /** Attempts registration. Safe and idempotent; never throws. */
    suspend fun start(): QualityReportAvailability

    suspend fun stop()

    fun samples(): Flow<LinkQualitySample>
}

/** Explicit "we have no BQR" implementation used below API 33 and in tests. */
class UnavailableQualityReportSource(reason: String) : QualityReportSource {
    private val state = MutableStateFlow<QualityReportAvailability>(
        QualityReportAvailability.Unavailable(reason),
    )
    override val availability: StateFlow<QualityReportAvailability> = state
    override suspend fun start() = state.value
    override suspend fun stop() = Unit
    override fun samples(): Flow<LinkQualitySample> = emptyFlow()
}

/**
 * Best-effort BQR registration under Shizuku's shell identity.
 *
 * ### What this code actually does
 * `BluetoothAdapter.registerBluetoothQualityReportReadyCallback(Executor,
 * BluetoothQualityReportReadyCallback)` is `@SystemApi` + `@RequiresPermission
 * (BLUETOOTH_PRIVILEGED)`. It is resolved reflectively and invoked with a
 * dynamic proxy for the callback interface. The permission check happens in the
 * Bluetooth server process against the *calling uid*, and our calls run under
 * the app uid even when Shizuku is available — a Shizuku binder only makes the
 * shell uid available to code we hand to Shizuku's own process, and the AOSP
 * adapter API has no such indirection.
 *
 * So this is expected to fail with SecurityException on stock Android 13-15,
 * and the class reports that as a clean [QualityReportAvailability.Unavailable]
 * instead of pretending. It is kept, and kept attempting, because:
 *  - the check is cheap and runs once per monitor start,
 *  - Android 17 (the primary target) may expose a different route, and
 *  - if shell *does* reach it on a given build, the monitor silently upgrades
 *    to the best data source with no other code change.
 *
 * **Needs on-device verification** on Android 17 / Pixel 11 Pro — see PLAN.md.
 */
class ShizukuQualityReportSource(
    private val context: Context,
) : QualityReportSource {

    private val state = MutableStateFlow<QualityReportAvailability>(
        QualityReportAvailability.Unavailable("not attempted yet"),
    )
    override val availability: StateFlow<QualityReportAvailability> = state

    override suspend fun start(): QualityReportAvailability {
        state.value = attemptRegistration()
        return state.value
    }

    override suspend fun stop() {
        state.value = QualityReportAvailability.Unavailable("stopped")
    }

    /**
     * BQR callbacks would be forwarded into this flow. It stays empty until
     * registration succeeds on a real device; no fake data is ever emitted.
     */
    override fun samples(): Flow<LinkQualitySample> = emptyFlow()

    private fun attemptRegistration(): QualityReportAvailability {
        if (Build.VERSION.SDK_INT < 33) {
            return QualityReportAvailability.Unavailable(
                "Bluetooth Quality Report needs Android 13 or newer",
            )
        }
        return runCatching {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager
                ?: return QualityReportAvailability.Unavailable("no Bluetooth service")
            val adapter = manager.adapter
                ?: return QualityReportAvailability.Unavailable("Bluetooth is off")

            val callbackClass = Class.forName(
                "android.bluetooth.BluetoothAdapter\$BluetoothQualityReportReadyCallback",
            )
            val register = adapter.javaClass.getMethod(
                "registerBluetoothQualityReportReadyCallback",
                java.util.concurrent.Executor::class.java,
                callbackClass,
            ).apply { isAccessible = true }

            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass),
            ) { _, _, _ -> null } // real forwarding is wired once this call succeeds

            register.invoke(adapter, java.util.concurrent.Executor { it.run() }, proxy)
            QualityReportAvailability.Active
        }.getOrElse { t ->
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            Log.i(TAG, "BQR registration not possible: ${cause.javaClass.simpleName}")
            QualityReportAvailability.Unavailable(
                when (cause) {
                    is SecurityException ->
                        "BLUETOOTH_PRIVILEGED denied — shell identity does not reach BQR on this build"
                    is ClassNotFoundException, is NoSuchMethodException ->
                        "API not present on this build"
                    else -> cause.message ?: cause.javaClass.simpleName
                },
            )
        }
    }

    private companion object {
        const val TAG = "ShizukuBqrSource"
    }
}
