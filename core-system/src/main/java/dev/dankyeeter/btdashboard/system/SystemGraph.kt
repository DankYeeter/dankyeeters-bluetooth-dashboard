package dev.dankyeeter.btdashboard.system

import android.content.Context
import dev.dankyeeter.btdashboard.audio.eq.DynamicsProcessingEqualizerFactory
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.system.airpods.AirPodsScanner
import dev.dankyeeter.btdashboard.system.attach.EqController
import dev.dankyeeter.btdashboard.system.attach.GlobalAttachmentStrategy
import dev.dankyeeter.btdashboard.system.attach.SessionAttachmentStrategy
import dev.dankyeeter.btdashboard.system.devices.AbsoluteVolumeGate
import dev.dankyeeter.btdashboard.system.devices.DeviceConnectionWatcher
import dev.dankyeeter.btdashboard.system.devices.DeviceProfileApplier
import dev.dankyeeter.btdashboard.system.devices.DeviceProfileStore
import dev.dankyeeter.btdashboard.system.devices.EqCompensationApplier
import dev.dankyeeter.btdashboard.system.devices.SystemMediaVolumeController
import dev.dankyeeter.btdashboard.system.persist.EqSettingsStore
import dev.dankyeeter.btdashboard.system.persist.AppearanceStore
import dev.dankyeeter.btdashboard.system.setup.SetupStore
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
    private var _deviceProfiles: DeviceProfileStore? = null
    private var _absoluteVolume: AbsoluteVolumeGate? = null
    private var _applier: DeviceProfileApplier? = null
    private var _watcher: DeviceConnectionWatcher? = null
    private var _setupStore: SetupStore? = null
    private var _appearanceStore: AppearanceStore? = null

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

    // ---- Milestone 2: per-device profiles -----------------------------------

    val deviceProfiles: DeviceProfileStore
        get() = synchronized(lock) {
            _deviceProfiles ?: DeviceProfileStore(ctx()).also { _deviceProfiles = it }
        }

    val absoluteVolume: AbsoluteVolumeGate
        get() = synchronized(lock) {
            _absoluteVolume ?: AbsoluteVolumeGate(ctx(), secureSettings).also { _absoluteVolume = it }
        }

    val deviceProfileApplier: DeviceProfileApplier
        get() = synchronized(lock) {
            _applier ?: DeviceProfileApplier(
                profiles = deviceProfiles,
                volume = SystemMediaVolumeController(ctx()),
                compensation = EqCompensationApplier(
                    profiles = HearingGraph.profileStore,
                    settingsStore = settingsStore,
                    controller = eqController,
                ),
                absoluteVolume = absoluteVolume,
            ).also { _applier = it }
        }

    val deviceConnectionWatcher: DeviceConnectionWatcher
        get() = synchronized(lock) {
            _watcher ?: DeviceConnectionWatcher(
                context = ctx(),
                store = deviceProfiles,
                applier = deviceProfileApplier,
            ).also { _watcher = it }
        }

    val appearanceStore: AppearanceStore
        get() = synchronized(lock) {
            _appearanceStore ?: AppearanceStore(ctx()).also { _appearanceStore = it }
        }

    val setupStore: SetupStore
        get() = synchronized(lock) {
            _setupStore ?: SetupStore(ctx()).also { _setupStore = it }
        }

    /** Starts the ACL-connect listener. Idempotent; called from Application. */
    fun startDeviceProfileAutoApply() {
        deviceConnectionWatcher.start()
    }
}
