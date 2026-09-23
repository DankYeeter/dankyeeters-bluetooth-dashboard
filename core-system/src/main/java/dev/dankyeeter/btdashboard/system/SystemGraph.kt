package dev.dankyeeter.btdashboard.system

import android.content.Context
import dev.dankyeeter.btdashboard.system.boot.BootReceiver
import dev.dankyeeter.btdashboard.audio.eq.DynamicsProcessingEqualizerFactory
import dev.dankyeeter.btdashboard.audio.eq.MediaVolumeMonitor
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

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun ctx(): Context =
        requireNotNull(appContext) { "SystemGraph.init() must be called from Application.onCreate" }

    val secureSettings: SecureSettingsGate by lazy {
        SecureSettingsGate(ctx())
    }

    /** Read-only AirPods beacon listener; started/stopped by the Dashboard. */
    val airPodsScanner: AirPodsScanner by lazy {
        AirPodsScanner(ctx())
    }

    val settingsStore: EqSettingsStore by lazy {
        EqSettingsStore(ctx())
    }

    /**
     * The live media-volume fraction the ISO 226 tilt is derived from.
     *
     * One per process: it registers a settings observer, and two of them would
     * mean two observers reporting the same number.
     */
    val mediaVolume: MediaVolumeMonitor by lazy {
        MediaVolumeMonitor(ctx())
    }

    val eqController: EqController by lazy {
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
        )
    }

    // ---- Milestone 2: per-device profiles -----------------------------------

    val deviceProfiles: DeviceProfileStore by lazy {
        DeviceProfileStore(ctx())
    }

    val absoluteVolume: AbsoluteVolumeGate by lazy {
        AbsoluteVolumeGate(ctx(), secureSettings)
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

    /** Live values for the read-only rows. Answers "unset" until `:app` installs one. */
    @Volatile
    var systemProperties: SystemPropertyReader = NoSystemPropertyReader
        private set

    fun installSystemPropertyReader(reader: SystemPropertyReader) {
        systemProperties = reader
    }

    /**
     * Also what the profile editor reads live values through. Cached like every
     * other field here — the getter used to build a fresh controller per call,
     * and the profile editor calls it once per developer option per refresh.
     */
    val globalSettings: SecureSettingsController by lazy {
        GlobalSettingsController(ctx(), secureSettings)
    }

    val deviceProfileApplier: DeviceProfileApplier by lazy {
        DeviceProfileApplier(
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
        )
    }

    val deviceConnectionWatcher: DeviceConnectionWatcher by lazy {
        DeviceConnectionWatcher(
            onConnected = { eqController.ensureAttached() },
            context = ctx(),
            store = deviceProfiles,
            applier = deviceProfileApplier,
        )
    }

    val appearanceStore: AppearanceStore by lazy {
        AppearanceStore(ctx())
    }

    val setupStore: SetupStore by lazy {
        SetupStore(ctx())
    }

    /** Starts the ACL-connect listener. Idempotent; called from Application. */
    fun startDeviceProfileAutoApply() {
        deviceConnectionWatcher.start()
    }
}
