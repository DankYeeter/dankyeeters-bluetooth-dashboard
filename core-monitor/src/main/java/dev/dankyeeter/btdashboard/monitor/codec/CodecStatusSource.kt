package dev.dankyeeter.btdashboard.monitor.codec

import kotlinx.coroutines.flow.Flow

/**
 * The monitor's only view of Bluetooth audio. Every consumer (dashboard,
 * sampler) talks to this interface, so all of them can be tested
 * against [dev.dankyeeter.btdashboard.monitor.codec.FakeCodecStatusSource]-style
 * doubles without a device.
 */
interface CodecStatusSource {

    /** Connected A2DP devices, or an empty list if the profile is unreachable. */
    suspend fun connectedDevices(): List<BtAudioDevice>

    /** Live view of the connected devices; emits on connect/disconnect. */
    fun connectedDevicesFlow(): Flow<List<BtAudioDevice>>

    suspend fun codecStatus(address: String): CodecReadResult

    /**
     * Whether this source can talk to the A2DP profile at all. False means the
     * dashboard shows "Bluetooth audio profile unavailable" instead of empty.
     */
    val isProfileAvailable: Boolean
}

/**
 * Which codecs can be asked for on a device — a privileged read, behind
 * BLUETOOTH_PRIVILEGED.
 *
 * `com.android.shell` was checked on the device and holds
 * `BLUETOOTH_PRIVILEGED: granted=true`, and the app's own privileged helper runs
 * as that uid — so the read is reachable from inside it. The real
 * implementation is `PrivilegedCodecController` in `:app`, which is the only
 * module that can see the helper's Binder. Setting a codec is
 * `CodecPreferenceController` in `:core-system`.
 */
interface CodecController {
    /**
     * Codecs that can actually be asked for on this device.
     *
     * An empty list means "could not find out" as much as it means "none": the
     * only honest reading, because the capability list itself comes from a
     * privileged read. Callers word it as "needs privileged access", never as
     * "this headphone supports nothing".
     */
    suspend fun availableCodecs(address: String): List<CodecFamily>
}

/**
 * What runs when the privileged helper is absent.
 *
 * Still the honest answer, and still needed: without the helper there is no
 * BLUETOOTH_PRIVILEGED anywhere in this app, so nothing can be read. Returning
 * "no codecs" is what the callers word as "needs privileged access we do not
 * have", which is exactly true here and would be a lie if the helper were up.
 * That is why this is a fallback rather than something to delete.
 */
object NoOpCodecController : CodecController {
    override suspend fun availableCodecs(address: String): List<CodecFamily> = emptyList()
}
