package dev.dankyeeter.btdashboard.system

import android.content.Context
import dev.dankyeeter.btdashboard.audio.eq.DynamicsProcessingEqualizerFactory
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.system.airpods.AirPodsScanner
import dev.dankyeeter.btdashboard.system.attach.AudioEffectSessionReceiver
import dev.dankyeeter.btdashboard.system.attach.EqController
import dev.dankyeeter.btdashboard.system.attach.GlobalAttachmentStrategy
import dev.dankyeeter.btdashboard.system.attach.SessionAttachmentStrategy
import dev.dankyeeter.btdashboard.system.devices.AbsoluteVolumeGate
import dev.dankyeeter.btdashboard.system.devices.CodecApplyOutcome
import dev.dankyeeter.btdashboard.system.devices.CodecPreference
import dev.dankyeeter.btdashboard.system.devices.CodecPreferenceController
import dev.dankyeeter.btdashboard.system.devices.DeviceConnectionWatcher
import dev.dankyeeter.btdashboard.system.devices.DeviceProfileApplier
import dev.dankyeeter.btdashboard.system.devices.GlobalSettingsController
import dev.dankyeeter.btdashboard.system.devices.SecureSettingsController
import dev.dankyeeter.btdashboard.system.devices.UnavailableCodecPreferenceController
import dev.dankyeeter.btdashboard.system.devices.DeviceProfileStore
import dev.dankyeeter.btdashboard.system.devices.EqCompensationApplier
import dev.dankyeeter.btdashboard.system.devices.SystemMediaVolumeController
import dev.dankyeeter.btdashboard.system.persist.EqSettingsStore
import dev.dankyeeter.btdashboard.system.persist.AppearanceStore
import dev.dankyeeter.btdashboard.system.setup.SetupStore
import dev.dankyeeter.btdashboard.system.shizuku.SecureSettingsGate

/**
 * Minimal process-wide wiring. Deliberately hand-rolled instead of a DI
 * framework: four objects, one process, no build-time cost.
 */
object SystemGraph {

    @Volatile private var appContext: Context? = null

    private val lock = Any()
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
                    global = GlobalAttachmentStrategy(factory),
                    session = SessionAttachmentStrategy(factory),
                    // The manifest session receiver only earns its wake-ups in
                    // session mode; the controller flips it to match.
                    setSessionReceiverEnabled = { enabled ->
                        AudioEffectSessionReceiver.setComponentEnabled(ctx(), enabled)
                    },
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

    @Volatile
    private var installedCodec: CodecPreferenceController? = null

    /**
     * Installs the codec controller, in the same style as
     * `MonitorGraph.installShellRunner`.
     *
     * Only `:app` can build one — it needs the privileged helper's Binder, and
     * this module deliberately knows nothing about that. Until one is
     * installed, [codecPreferences] answers "cannot check" rather than "no".
     */
    fun installCodecPreferenceController(controller: CodecPreferenceController) {
        installedCodec = controller
    }

    /**
     * Forwards to whatever `:app` installed, resolved on every call.
     *
     * Capturing the controller when the applier is first built would freeze
     * whichever one existed at that moment — and the applier is built lazily by
     * whoever touches it first, which is not guaranteed to be after
     * `Application.onCreate` has finished wiring. One field read per call
     * removes that ordering requirement entirely.
     */
    private val codecPreferences = object : CodecPreferenceController {
        override fun isAvailable(): Boolean = installedCodec?.isAvailable() == true

        override suspend fun apply(address: String, preference: CodecPreference): CodecApplyOutcome =
            (installedCodec ?: UnavailableCodecPreferenceController).apply(address, preference)
    }

    private var _globalSettings: SecureSettingsController? = null

    /**
     * Also what the profile editor reads live values through. Cached like every
     * other field here — the getter used to build a fresh controller per call,
     * and the profile editor calls it once per developer option per refresh.
     */
    val globalSettings: SecureSettingsController
        get() = synchronized(lock) {
            _globalSettings
                ?: GlobalSettingsController(ctx(), secureSettings).also { _globalSettings = it }
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
                secureSettings = globalSettings,
                codec = codecPreferences,
            ).also { _applier = it }
        }

    val deviceConnectionWatcher: DeviceConnectionWatcher
        get() = synchronized(lock) {
            _watcher ?: DeviceConnectionWatcher(
                onConnected = { eqController.ensureAttached() },
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
