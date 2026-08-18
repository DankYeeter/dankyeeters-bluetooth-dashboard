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
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.devices.AbsoluteVolumeStatus
import dev.dankyeeter.btdashboard.system.devices.ApplyResult
import dev.dankyeeter.btdashboard.system.devices.DeviceKey
import dev.dankyeeter.btdashboard.system.devices.DeviceProfile
import dev.dankyeeter.btdashboard.system.devices.ProfileAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** deviceKey currently open in the editor sheet, or null. */
    private val _editing = MutableStateFlow<String?>(null)
    val editing: StateFlow<String?> = _editing.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _bonded.value = readBondedDevices()
        _absoluteVolumeStatus.value = absoluteVolume.status()
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
            _message.value = "Saved the profile for ${profile.sanitized().name}."
            refresh()
        }
    }

    fun delete(deviceKey: String) {
        viewModelScope.launch {
            store.delete(deviceKey)
            _editing.value = null
            _message.value = "Profile deleted."
        }
    }

    /** "Apply now" — runs the profile against the live system immediately. */
    fun applyNow(profile: DeviceProfile) {
        viewModelScope.launch {
            val actions = applier.applyNow(profile)
            _message.value = describe(actions)
            refresh()
        }
    }

    /** The absolute-volume switch inside the editor writes through immediately. */
    fun setAbsoluteVolumeNow(enabled: Boolean) {
        val written = absoluteVolume.setEnabled(enabled)
        _absoluteVolumeStatus.value = absoluteVolume.status()
        _message.value = if (written) {
            "Absolute volume is now ${if (enabled) "on" else "off"} system-wide. " +
                "Reconnect the headphone for it to take effect."
        } else {
            "The setting could not be written — WRITE_SECURE_SETTINGS is missing."
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    // ---- helpers -------------------------------------------------------------

    private fun describe(actions: List<ProfileAction>): String {
        if (actions.isEmpty()) return "Nothing to apply — every field in this profile is set to \"leave alone\"."
        return actions.joinToString(" ") { action ->
            when (action) {
                is ProfileAction.VolumeSet -> "Media volume set to ${action.percent} %."
                is ProfileAction.CompensationApplied -> "Compensation profile applied."
                is ProfileAction.AbsoluteVolumeSet ->
                    "Absolute volume ${if (action.enabled) "on" else "off"}."
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
