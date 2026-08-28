package dev.dankyeeter.btdashboard.ui.screens.devices

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.hearing.CalibrationPreset
import dev.dankyeeter.btdashboard.hearing.CompensationProfile
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.codec.CodecReadResult
import dev.dankyeeter.btdashboard.monitor.codec.CodecStatus
import dev.dankyeeter.btdashboard.privileged.PrivilegedCodec
import dev.dankyeeter.btdashboard.privileged.PrivilegedConnection
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.devices.AbsoluteVolumeStatus
import dev.dankyeeter.btdashboard.system.devices.ApplyResult
import dev.dankyeeter.btdashboard.system.devices.BluetoothReadOnlySettings
import dev.dankyeeter.btdashboard.system.devices.BluetoothRestartOutcome
import dev.dankyeeter.btdashboard.system.devices.DeviceKey
import dev.dankyeeter.btdashboard.system.devices.DeviceProfile
import dev.dankyeeter.btdashboard.system.devices.BluetoothDeveloperOptions
import dev.dankyeeter.btdashboard.system.devices.HdAudioState
import dev.dankyeeter.btdashboard.system.devices.ProfileAction
import dev.dankyeeter.btdashboard.ui.tuning.LdacTuning
import dev.dankyeeter.btdashboard.ui.tuning.LdacTuningState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the codec section knows about one device at one moment.
 *
 * All three fields together, because they are read together and must not drift
 * apart: `connected = true` with `negotiated = null` is the real and previously
 * mislabelled case "the headphone is there, Android will not say which codec".
 */
data class CodecInfo(
    /** Codecs the device advertises as selectable, or null when unasked. */
    val offered: List<String>? = null,
    /** What the link is actually running, or null when unreadable. */
    val negotiated: CodecStatus? = null,
    val connected: Boolean = false,
    /**
     * HD audio for the same device, read in the same pass.
     *
     * It rides along here rather than in a flow of its own because it is
     * derived from exactly the same three inputs — which device, whether it is
     * connected, whether the helper is up — and reading it separately would
     * mean two flows recomputing on the same events with no guarantee they
     * agree. The codec section and the HD-audio row would then be able to
     * disagree about whether the device is even connected, which is the drift
     * this data class exists to prevent.
     *
     * Null while nothing has been read yet, as distinct from
     * [HdAudioState.Unreadable], which is an answer.
     */
    val hdAudio: HdAudioState? = null,
)

/** A device the user could have a profile for, whether or not one exists yet. */
data class KnownDevice(
    val deviceKey: String,
    val name: String,
    /** True when Android currently lists it as bonded. */
    val bonded: Boolean,
)

/**
 * State for the per-device profile editor.
 *
 * The device list is the union of two sources: profiles we already store (a
 * device seen at least once) and the currently bonded devices. The second half
 * exists so a profile can be prepared before the headphone is next connected,
 * rather than forcing the user to plug in first.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceProfilesViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SystemGraph.deviceProfiles
    private val applier = SystemGraph.deviceProfileApplier
    private val absoluteVolume = SystemGraph.absoluteVolume

    val profiles: StateFlow<List<DeviceProfile>> =
        store.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val compensationProfiles: StateFlow<List<CompensationProfile>> = HearingGraph.profileStore.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val calibrationPresets: List<CalibrationPreset> = HearingGraph.presets.all()

    private val _bonded = MutableStateFlow<List<KnownDevice>>(emptyList())
    val bonded: StateFlow<List<KnownDevice>> = _bonded.asStateFlow()

    private val _absoluteVolumeStatus = MutableStateFlow(absoluteVolume.status())
    val absoluteVolumeStatus: StateFlow<AbsoluteVolumeStatus> = _absoluteVolumeStatus.asStateFlow()

    /** Result of the most recent automatic apply, shown as a plain sentence. */
    val lastAutoApply: StateFlow<ApplyResult?> = SystemGraph.deviceConnectionWatcher.lastResult

    /**
     * Busy and last outcome of the bitrate chips — the very same state the
     * Monitoring panel shows.
     *
     * Not a copy of it. Both screens can move the playback quality of one
     * headphone, and two states would eventually disagree about whether a
     * renegotiation was still in flight.
     */
    val ldacTuning: StateFlow<LdacTuningState> = LdacTuning.state

    /**
     * Whether the privileged helper is answering right now.
     *
     * A live flow rather than a value read once: the helper dies on reboot and
     * arrives when the user runs the ADB command, neither of which the editor
     * can predict. The codec section uses it to say "cannot be set" instead of
     * offering a control that would quietly do nothing.
     */
    val helperConnected: StateFlow<Boolean> = PrivilegedConnection.service
        .map { it != null }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PrivilegedConnection.isConnected,
        )

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * Which device [message] is about, so the screen can put the result under
     * the card that produced it instead of at the foot of the list. Null when
     * the result belongs to no particular device.
     */
    private val _messageDeviceKey = MutableStateFlow<String?>(null)
    val messageDeviceKey: StateFlow<String?> = _messageDeviceKey.asStateFlow()

    /** deviceKey currently open in the editor sheet, or null. */
    private val _editing = MutableStateFlow<String?>(null)
    val editing: StateFlow<String?> = _editing.asStateFlow()

    /**
     * Live `Settings.Global` values per developer-option key. Null value =
     * key unset (Android's own default) — which is exactly what "Use System
     * Default" resolves to, so the editor can show it.
     *
     * Declared before the init block on purpose: `refresh()` writes it, and a
     * property that is textually below `init` is still null while init runs.
     */
    private val _liveDevOptions = MutableStateFlow<Map<String, String?>>(emptyMap())
    val liveDevOptions: StateFlow<Map<String, String?>> = _liveDevOptions.asStateFlow()

    /**
     * Which device the codec fields describe. Set by the open editor card.
     *
     * A one-shot load was not enough and produced a visible wrong answer: the
     * A2DP proxy binds asynchronously, so a card opened right after an app
     * start read "Not connected" for a headphone that was plainly connected,
     * and nothing ever corrected it. Connect and disconnect while the card was
     * open had the same problem in reverse.
     */
    private val watchedDeviceKey = MutableStateFlow<String?>(null)

    /**
     * Everything the codec section needs, recomputed whenever any input moves.
     *
     * ### Why this is a derived property and not something `init` starts
     *
     * The previous version launched a collector from `init`, and that trap was
     * sprung twice in one day: a property declared textually *below* `init` is
     * still null while `init` runs, so `combine` received a null flow and threw
     * a crash that pointed nowhere near the cause. Written as a property
     * initialiser instead, the same mistake is a **compile error** — Kotlin
     * refuses a forward reference here. The hazard is gone rather than
     * documented.
     *
     * ### What makes it move
     *
     *  - [watchedDeviceKey] — which device the card is showing;
     *  - the device list — the same push-based source the dashboard uses; it
     *    emits when the A2DP proxy finishes binding and on every connect;
     *  - the privileged helper — it decides whether the *selectable* list can
     *    be read at all, and it usually arrives long after the app.
     *
     * No polling anywhere. Each of these announces its own change, and
     * `WhileSubscribed` means nothing runs while the screen is closed.
     */
    private val codecInfo: StateFlow<CodecInfo> = combine(
        watchedDeviceKey,
        // Two failure points, both fatal if unguarded: building the source
        // binds the A2DP proxy, and the flow itself can fail later. Neither may
        // take the editor down — "codec unknown" is the honest degradation, and
        // every field below already renders it.
        runCatching { MonitorGraph.codecSource.connectedDevicesFlow() }
            .getOrElse { flowOf(emptyList()) }
            .catch { emit(emptyList()) },
        PrivilegedConnection.service,
    ) { key, devices, _ -> key to devices }
        .mapLatest { (key, devices) -> readCodecInfo(key, devices) }
        .catch { emit(CodecInfo()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CodecInfo())

    val offeredCodecs: StateFlow<List<String>?> = codecInfo
        .map { it.offered }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val negotiatedCodec: StateFlow<String?> = codecInfo
        .map { it.negotiated?.family?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val negotiatedStatus: StateFlow<CodecStatus?> = codecInfo
        .map { it.negotiated }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Whether the watched device is connected *right now* — kept apart from the
     * codec value because the two answer different questions. Merging them told
     * a lie: without the helper the codec read throws and the dumpsys fallback
     * is gone, so a plainly connected headphone was labelled "Not connected".
     */
    val deviceConnected: StateFlow<Boolean> = codecInfo
        .map { it.connected }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Live HD-audio state for the watched device; null before anything is read. */
    val hdAudioState: StateFlow<HdAudioState?> = codecInfo
        .map { it.hdAudio }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Whether the helper can cycle the radio.
     *
     * Derived from the same connection flow [helperConnected] uses rather than
     * asked once: the button is on a panel the user may leave open while the
     * helper connects, and a value read at compose time would still say "no"
     * afterwards.
     */
    val canRestartBluetooth: StateFlow<Boolean> = PrivilegedConnection.service
        .map { SystemGraph.bluetoothRestart.isAvailable() }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SystemGraph.bluetoothRestart.isAvailable(),
        )

    /** True only while a restart is in flight, so the button cannot be double-tapped. */
    private val _restarting = MutableStateFlow(false)
    val restarting: StateFlow<Boolean> = _restarting.asStateFlow()

    /**
     * Live values of the settings the app can only *show*.
     *
     * Null means the property is unset, which is the normal state and is worded
     * as such — see [dev.dankyeeter.btdashboard.system.devices.BluetoothReadOnlySettings].
     */
    private val _readOnlySettings = MutableStateFlow<Map<String, String?>>(emptyMap())
    val readOnlySettings: StateFlow<Map<String, String?>> = _readOnlySettings.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _bonded.value = readBondedDevices()
        _absoluteVolumeStatus.value = absoluteVolume.status()
        _liveDevOptions.value = BluetoothDeveloperOptions.all.associate {
            it.key to SystemGraph.globalSettings.read(it.key)
        }
        _readOnlySettings.value = BluetoothReadOnlySettings.all.associate {
            it.liveValueKey to SystemGraph.systemProperties.read(it.liveValueKey)
        }
    }

    /**
     * Writes one global **now**, system-wide, outside any profile.
     *
     * The counterpart to the per-device picker, not a duplicate of it: that one
     * stores "set this when the Bathys connects", this one answers "set this,
     * for the phone, today". Both go through the same read-back-confirming
     * controller, so neither can report a success the settings database does
     * not actually show.
     *
     * [value] of [BluetoothDeveloperOptions.USE_SYSTEM_DEFAULT] deletes the key
     * rather than writing a guessed default — the same distinction the applier
     * makes, for the same reason.
     */
    fun setGlobalNow(key: String, value: String) {
        val option = BluetoothDeveloperOptions.byKey(key) ?: return
        val settings = SystemGraph.globalSettings
        if (!settings.isWritable()) {
            post(null, "\"${option.label}\" was not changed — WRITE_SECURE_SETTINGS is not granted.")
            return
        }

        val systemDefault = value == BluetoothDeveloperOptions.USE_SYSTEM_DEFAULT
        val ok = if (systemDefault) settings.clear(key) else settings.write(key, value)
        refresh()

        post(
            null,
            when {
                !ok && systemDefault ->
                    "\"${option.label}\" could not be reset — the key still holds a value."
                // The only evidence available that a build ignores a key, said
                // plainly rather than reported as a success.
                !ok ->
                    "\"${option.label}\" did not stick — this Android build may not support it."

                systemDefault ->
                    "\"${option.label}\" is back to Android's own default. " +
                        "Restart Bluetooth for it to take effect."

                else ->
                    "\"${option.label}\" is now ${option.labelFor(value)} system-wide. " +
                        "Restart Bluetooth for it to take effect."
            },
        )
    }

    /**
     * Turns Bluetooth off and on again, so a stored global actually takes hold.
     *
     * The screen has been telling people to do this by hand since the developer
     * options existed. Doing it here is the same action, minus the trip to the
     * quick-settings panel.
     */
    fun restartBluetooth() {
        if (_restarting.value) return
        _restarting.value = true
        viewModelScope.launch {
            val outcome = runCatching { SystemGraph.bluetoothRestart.restart() }
                .getOrElse {
                    BluetoothRestartOutcome.Failed(it.message ?: "the restart threw")
                }
            _restarting.value = false
            post(
                null,
                when (outcome) {
                    BluetoothRestartOutcome.Restarted ->
                        "Bluetooth was turned off and on again. Anything stored above is in " +
                            "force now; your headphones will reconnect on their own."

                    is BluetoothRestartOutcome.Failed ->
                        "The restart did not finish: ${outcome.detail}"

                    is BluetoothRestartOutcome.Unavailable ->
                        "Bluetooth was not restarted: ${outcome.reason}"
                },
            )
            refresh()
        }
    }

    /** Points the codec section at a device. Cheap: it only moves a flow. */
    fun loadOfferedCodecs(deviceKey: String) {
        watchedDeviceKey.value = deviceKey
    }

    /**
     * Reads the codec picture for one device. Pure input → output; the caller
     * decides when, so this never schedules anything of its own.
     */
    private suspend fun readCodecInfo(
        deviceKey: String?,
        devices: List<BtAudioDevice>,
    ): CodecInfo {
        val address = deviceKey
            ?.let { key -> devices.firstOrNull { DeviceKey.fromAddress(it.address) == key } }
            ?.address
        // Not connected: everything unknown rather than stale. A leftover codec
        // would be a claim about a link that no longer exists.
            ?: return CodecInfo()

        val families = runCatching { PrivilegedCodec.controller().availableCodecs(address) }
            .getOrDefault(emptyList())

        val status = runCatching {
            (MonitorGraph.codecSource.codecStatus(address) as? CodecReadResult.Available)?.status
        }.getOrNull()

        // A throw here must not take the codec fields down with it: the two are
        // read through different privileged calls and either can fail alone.
        val hdAudio = runCatching { SystemGraph.hdAudio.read(address) }
            .getOrElse { HdAudioState.Unreadable(it.message ?: "the HD-audio read failed") }

        return CodecInfo(
            // An empty answer is what the no-op controller returns when there
            // is no helper. That is "we could not ask", not "this device
            // supports nothing" — so it stays null.
            offered = families.takeIf { it.isNotEmpty() }?.map { it.name },
            negotiated = status,
            connected = true,
            hdAudio = hdAudio,
        )
    }

    fun hasBluetoothPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

    fun startEditing(deviceKey: String) {
        _editing.value = deviceKey
    }

    fun stopEditing() {
        _editing.value = null
    }

    fun save(profile: DeviceProfile) {
        viewModelScope.launch {
            store.save(profile)
            _editing.value = null
            post(profile.deviceKey, "Saved the profile for ${profile.sanitized().name}.")
            refresh()
        }
    }

    fun delete(deviceKey: String) {
        viewModelScope.launch {
            store.delete(deviceKey)
            _editing.value = null
            post(deviceKey, "Profile deleted.")
        }
    }

    /** "Apply now" — runs the profile against the live system immediately. */
    fun applyNow(profile: DeviceProfile) {
        viewModelScope.launch {
            // Every other field in a profile is a global setting and needs no
            // device. A codec is set *on* a BluetoothDevice, so the applier
            // needs the raw address - and the profile only stores a hash of it.
            // Looking it up among the connected devices is the honest way back:
            // if the headphone is not connected there is no address, and the
            // applier reports that step as not attempted.
            val actions = applier.applyNow(profile, addressFor(profile.deviceKey))
            post(profile.deviceKey, describe(actions))
            refresh()
        }
    }

    /**
     * The raw address of a device we hold a profile for, when it is connected.
     *
     * Matched by re-hashing each connected address rather than by storing raw
     * MACs anywhere - the profile store never learns one, and neither does this.
     */
    private suspend fun addressFor(deviceKey: String): String? =
        runCatching { MonitorGraph.codecSource.connectedDevices() }
            .getOrDefault(emptyList())
            .firstOrNull { DeviceKey.fromAddress(it.address) == deviceKey }
            ?.address


    /**
     * The absolute-volume switch inside the editor writes through immediately.
     *
     * [deviceKey] identifies the card that asked, and is used for nothing else:
     * the setting itself is system-wide. It exists so the sentence that comes
     * back lands under that card rather than at the bottom of the screen.
     */
    fun setAbsoluteVolumeNow(deviceKey: String, enabled: Boolean) {
        val written = absoluteVolume.setEnabled(enabled)
        _absoluteVolumeStatus.value = absoluteVolume.status()
        post(
            deviceKey,
            if (written) {
                "Absolute volume is now ${if (enabled) "on" else "off"} system-wide. " +
                    "Reconnect the headphone for it to take effect."
            } else {
                "The setting could not be written — WRITE_SECURE_SETTINGS is missing."
            },
        )
    }

    /**
     * Pins a playback quality for one device — stored, then asked for live.
     *
     * Write-through rather than a field the Save button commits, and for the
     * same reason the absolute-volume button above it is: this one renegotiates
     * the codec, and an action with an audible consequence should happen when it
     * is tapped rather than at the bottom of a form. [LdacTuning] owns both
     * halves, so the Monitoring panel's chips do exactly this too.
     */
    fun pinLdacQuality(deviceKey: String, quality: Long) {
        if (deviceKey.isBlank()) return
        viewModelScope.launch { LdacTuning.pin(quality, deviceKey = deviceKey) }
    }

    fun dismissLdacMessage() = LdacTuning.dismissMessage()

    fun dismissMessage() {
        _message.value = null
        _messageDeviceKey.value = null
    }

    // ---- helpers -------------------------------------------------------------

    /** One result, tagged with the device whose card should carry it. */
    private fun post(deviceKey: String?, text: String) {
        _messageDeviceKey.value = deviceKey
        _message.value = text
    }

    /**
     * The per-action sentences for one apply run.
     *
     * Not private: the connect banner says the same things about the same
     * actions, and counting them there instead produced a line that was both
     * shorter and wrong.
     */
    fun describe(actions: List<ProfileAction>): String {
        if (actions.isEmpty()) return "Nothing to apply — every field in this profile is set to \"leave alone\"."
        return actions.joinToString(" ") { action ->
            when (action) {
                is ProfileAction.VolumeSet -> "Media volume set to ${action.percent} %."
                is ProfileAction.CompensationApplied -> "Compensation profile applied."
                is ProfileAction.AbsoluteVolumeSet ->
                    "Absolute volume ${if (action.enabled) "on" else "off"}."
                ProfileAction.AbsoluteVolumeReset ->
                    "Absolute volume handed back to the system default."
                is ProfileAction.DeveloperOptionSet -> {
                    val option = BluetoothDeveloperOptions.byKey(action.key)
                    val name = option?.label ?: action.key
                    val value = option?.labelFor(action.value) ?: action.value
                    when {
                        action.alreadySet -> "$name was already $value."
                        // Stored is not the same as in force: the stack reads
                        // these at startup. Saying only "set" would have the
                        // user listening for a change that cannot be there yet.
                        action.needsBluetoothRestart ->
                            "$name set to $value — turn Bluetooth off and on for it to take effect."
                        else -> "$name set to $value."
                    }
                }
                is ProfileAction.CodecSet ->
                    "Codec is now ${action.observed} - read back, not just requested."
                // Deliberately not phrased as a failure. Nothing reachable from
                // an app can tell a refusal apart from a renegotiation still in
                // flight, so both are named.
                is ProfileAction.CodecNotObserved ->
                    "Codec still reads ${action.observed}: ${action.detail}."
                is ProfileAction.HdAudioSet -> {
                    val state = when (action.enabled) {
                        true -> "on"
                        false -> "off — this device is now SBC only"
                        // The stack's "nobody has chosen", which is what "Use
                        // System Default" asks for. Named rather than rounded to
                        // "on", because the two are undone differently.
                        null -> "back to Android's own choice"
                    }
                    if (action.alreadySet) {
                        "HD audio was already $state."
                    } else {
                        "HD audio is now $state — read back, not just requested."
                    }
                }
                is ProfileAction.HdAudioNotObserved -> "HD audio: ${action.detail}."
                is ProfileAction.Skipped -> "Skipped ${action.what}: ${action.reason}."
            }
        }
    }

    /**
     * Bonded devices, best effort. Without BLUETOOTH_CONNECT this returns
     * nothing and the screen says so — it never shows a half-populated list
     * that looks like the user has no headphones paired.
     */
    private fun readBondedDevices(): List<KnownDevice> {
        if (!hasBluetoothPermission()) return emptyList()
        val context = getApplication<Application>()
        val adapter: BluetoothAdapter? = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()
        return runCatching {
            adapter?.bondedDevices.orEmpty().mapNotNull { device ->
                val key = DeviceKey.fromAddress(device.address) ?: return@mapNotNull null
                KnownDevice(
                    deviceKey = key,
                    name = device.name?.takeIf { it.isNotBlank() } ?: device.address,
                    bonded = true,
                )
            }.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }
}
