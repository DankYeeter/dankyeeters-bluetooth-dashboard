package dev.dankyeeter.btdashboard.monitor.codec

import kotlinx.coroutines.flow.Flow

/**
 * The monitor's only view of Bluetooth audio. Every consumer (dashboard,
 * diagnostic, sampler) talks to this interface, so all of them can be tested
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
 * Forcing a codec is an on-device, privileged operation
 * (`BluetoothA2dp.setCodecConfigPreference`, behind BLUETOOTH_PRIVILEGED).
 *
 * This used to be documented as unverified, with [NoOpCodecController] as the
 * only implementation. That has changed: `com.android.shell` was checked on the
 * device and holds `BLUETOOTH_PRIVILEGED: granted=true`, and the app's own
 * privileged helper runs as that uid — so the call is reachable from inside it.
 * The real implementation is `PrivilegedCodecController` in `:app`, which is
 * the only module that can see the helper's Binder.
 *
 * There is deliberately no shell-command path. `cmd bluetooth_manager` offers
 * enable, disable, enableBle, disableBle, factoryReset and wait-for-state, and
 * nothing about codecs; the operation is a typed method on the helper's
 * interface instead.
 *
 * The diagnostic drives this interface, so its flow stays testable with fakes
 * and needs no device.
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

    /**
     * Returns the codec **observed** afterwards, or null when it was refused,
     * unreachable, or had not taken effect by the time we looked. Never the
     * codec that was requested — a request is not a result.
     */
    suspend fun selectCodec(address: String, codec: CodecFamily): CodecFamily?
}

/**
 * What runs when the privileged helper is absent.
 *
 * Still the honest answer, and still needed: without the helper there is no
 * BLUETOOTH_PRIVILEGED anywhere in this app, so nothing can be set and — just
 * as importantly — nothing can be *read back*. Returning "no codecs" and "not
 * applied" is what the callers word as "needs privileged access we do not
 * have", which is exactly true here and would be a lie if the helper were up.
 * That is why this is a fallback rather than something to delete.
 */
object NoOpCodecController : CodecController {
    override suspend fun availableCodecs(address: String): List<CodecFamily> = emptyList()
    override suspend fun selectCodec(address: String, codec: CodecFamily): CodecFamily? = null
}
