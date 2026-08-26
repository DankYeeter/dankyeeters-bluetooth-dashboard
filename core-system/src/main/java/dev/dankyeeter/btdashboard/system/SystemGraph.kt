package dev.dankyeeter.btdashboard.system

import android.content.Context
import dev.dankyeeter.btdashboard.system.boot.BootReceiver
import dev.dankyeeter.btdashboard.audio.eq.DynamicsProcessingEqualizerFactory
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.system.airpods.AirPodsScanner
import dev.dankyeeter.btdashboard.system.attach.AudioEffectSessionReceiver
import dev.dankyeeter.btdashboard.system.attach.EqController
import dev.dankyeeter.btdashboard.system.attach.GlobalAttachmentStrategy
import dev.dankyeeter.btdashboard.system.attach.OutputMixReachGate
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import dev.dankyeeter.btdashboard.system.attach.PlaybackSessionHarvester
import dev.dankyeeter.btdashboard.system.attach.SessionAttachmentStrategy
import dev.dankyeeter.btdashboard.system.devices.AbsoluteVolumeGate
import dev.dankyeeter.btdashboard.system.devices.BluetoothRestartController
import dev.dankyeeter.btdashboard.system.devices.HdAudioController
import dev.dankyeeter.btdashboard.system.devices.HdAudioOutcome
import dev.dankyeeter.btdashboard.system.devices.HdAudioPreference
import dev.dankyeeter.btdashboard.system.devices.HdAudioState
import dev.dankyeeter.btdashboard.system.devices.NoSystemPropertyReader
import dev.dankyeeter.btdashboard.system.devices.SystemPropertyReader
import dev.dankyeeter.btdashboard.system.devices.UnavailableBluetoothRestartController
import dev.dankyeeter.btdashboard.system.devices.UnavailableHdAudioController
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
import dev.dankyeeter.btdashboard.system.secure.SecureSettingsGate

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
                    // A global attach reports success even where it is inaudible
                    // (measured: Bluetooth). The controller has to ask first.
                    globalAttachReachesOutput = OutputMixReachGate(ctx())::globalAttachReachesOutput,
                    // Harvesting only makes sense while session mode is the
                    // active strategy; the controller owns that transition.
                    setSessionHarvestEnabled = { enabled ->
                        if (enabled) sessionHarvester.start() else sessionHarvester.stop()
                    },
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

    /**
     * Runs a command as the privileged helper, or returns null without one.
     *
     * Same reasoning as [installCodecPreferenceController]: only `:app` holds
     * the helper's Binder. Installed rather than injected because the helper
     * comes and goes at runtime, so the harvester must resolve it per call
     * instead of capturing whatever existed at construction.
     */
    @Volatile
    private var installedShell: (suspend (List<String>) -> String?)? = null

    fun installPrivilegedShell(run: suspend (List<String>) -> String?) {
        installedShell = run
    }

    /**
     * Tells the harvester the helper is available now.
     *
     * The app reaches session mode faster than the helper connects, so the
     * first harvest finds no helper and returns nothing. Without this nudge the
     * EQ waits for the next playback event - and if music was already playing,
     * that event never comes.
     */
    /**
     * How this module asks the app to bring the privileged helper up.
     *
     * The activation client lives in `:app` - it needs the ADB stack, the key
     * store and the pairing code - and `:core-system` cannot depend on it. The
     * app installs this at startup; anything here that needs a helper and finds
     * none calls it and takes what it gets.
     *
     * Null until the app sets it, and null in tests, where nothing should be
     * opening network ports on its own.
     */
    @Volatile
    var activateHelper: (suspend () -> Boolean)? = null

    fun onPrivilegedHelperConnected() {
        sessionHarvester.onPrivilegedHelperConnected()
        // The boot notice says the EQ is off and offers to fix it. Both have
        // just stopped being true. It is only ever dismissed by being tapped,
        // so activating from inside the app used to leave it standing.
        runCatching { BootReceiver.dismissNotice(ctx()) }
    }

    /**
     * Watches for players that never announce their session and hands their ids
     * to the session strategy.
     *
     * Started only in session mode - see [EqController]. Without the helper it
     * simply reports nothing, which is exactly the behaviour before it existed.
     */
    private val sessionHarvester: PlaybackSessionHarvester by lazy {
        PlaybackSessionHarvester(
            context = ctx(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            runPrivileged = { command -> installedShell?.invoke(command) },
            onSessionsChanged = { sessions -> eqController.onHarvestedSessions(sessions) },
            reassertSettings = { eqController.reassertCurrentSettings() },
        )
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

    @Volatile
    private var installedHdAudio: HdAudioController? = null

    /**
     * Installs the HD-audio controller, exactly as
     * [installCodecPreferenceController] does and for the same reason: only
     * `:app` can reach the privileged helper's Binder.
     */
    fun installHdAudioController(controller: HdAudioController) {
        installedHdAudio = controller
    }

    /**
     * Resolved on every call rather than captured, for the reason spelled out
     * on [codecPreferences]: the applier is built lazily by whichever caller
     * touches it first, which is not guaranteed to be after the app has
     * finished wiring.
     */
    private val hdAudioControl = object : HdAudioController {
        override fun isAvailable(): Boolean = installedHdAudio?.isAvailable() == true

        override suspend fun read(address: String): HdAudioState =
            (installedHdAudio ?: UnavailableHdAudioController).read(address)

        override suspend fun apply(address: String, preference: HdAudioPreference): HdAudioOutcome =
            (installedHdAudio ?: UnavailableHdAudioController).apply(address, preference)
    }

    /** What the profile editor reads HD audio through. Same object as the applier's. */
    val hdAudio: HdAudioController get() = hdAudioControl

    @Volatile
    private var installedRestart: BluetoothRestartController? = null

    fun installBluetoothRestartController(controller: BluetoothRestartController) {
        installedRestart = controller
    }

    /**
     * Cycles the Bluetooth radio, when a helper is there to do it.
     *
     * Not a dependency of the applier: restarting Bluetooth is something the
     * *user* asks for after seeing "stored, but not in force yet", never
     * something a profile does on connect. A profile that cycled the radio on
     * connect would disconnect the device that triggered it.
     */
    val bluetoothRestart: BluetoothRestartController
        get() = installedRestart ?: UnavailableBluetoothRestartController

    @Volatile
    private var installedProperties: SystemPropertyReader? = null

    fun installSystemPropertyReader(reader: SystemPropertyReader) {
        installedProperties = reader
    }

    /** Live values for the read-only rows. Answers "unset" until `:app` installs one. */
    val systemProperties: SystemPropertyReader
        get() = installedProperties ?: NoSystemPropertyReader

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
                hdAudio = hdAudioControl,
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
