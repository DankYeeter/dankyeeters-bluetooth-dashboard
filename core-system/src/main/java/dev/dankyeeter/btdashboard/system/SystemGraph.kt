package dev.dankyeeter.btdashboard.system

import android.content.Context
import dev.dankyeeter.btdashboard.audio.eq.DynamicsProcessingEqualizerFactory
import dev.dankyeeter.btdashboard.system.airpods.AirPodsScanner
import dev.dankyeeter.btdashboard.system.attach.EqController
import dev.dankyeeter.btdashboard.system.attach.GlobalAttachmentStrategy
import dev.dankyeeter.btdashboard.system.attach.SessionAttachmentStrategy
import dev.dankyeeter.btdashboard.system.persist.EqSettingsStore
import dev.dankyeeter.btdashboard.system.shizuku.SecureSettingsGate
import dev.dankyeeter.btdashboard.system.shizuku.ShizukuManager

/**
 * Minimal process-wide wiring. Deliberately hand-rolled instead of a DI
 * framework: four objects, one process, no build-time cost.
 */
object SystemGraph {

    @Volatile private var appContext: Context? = null

    private val lock = Any()
    private var _shizuku: ShizukuManager? = null
    private var _store: EqSettingsStore? = null
    private var _controller: EqController? = null
    private var _secureSettings: SecureSettingsGate? = null
    private var _airPods: AirPodsScanner? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun ctx(): Context =
        requireNotNull(appContext) { "SystemGraph.init() must be called from Application.onCreate" }

    val shizuku: ShizukuManager
        get() = synchronized(lock) {
            _shizuku ?: ShizukuManager(ctx()).also { _shizuku = it }
        }

    val secureSettings: SecureSettingsGate
        get() = synchronized(lock) {
            _secureSettings ?: SecureSettingsGate(ctx()).also { _secureSettings = it }
        }

    /** Read-only AirPods beacon listener; started/stopped by the Dashboard. */
    val airPodsScanner: AirPodsScanner
        get() = synchronized(lock) {
            _airPods ?: AirPodsScanner(ctx()).also { _airPods = it }
        }

    val settingsStore: EqSettingsStore
        get() = synchronized(lock) {
            _store ?: EqSettingsStore(ctx()).also { _store = it }
        }

    val eqController: EqController
        get() = synchronized(lock) {
            _controller ?: run {
                val factory = DynamicsProcessingEqualizerFactory()
                EqController(
                    global = GlobalAttachmentStrategy(factory, shizuku),
                    session = SessionAttachmentStrategy(factory),
                ).also { _controller = it }
            }
        }
}
