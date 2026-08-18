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
 * Forcing a codec is an on-device, privileged operation (`setCodecConfig`
 * behind BLUETOOTH_PRIVILEGED). The diagnostic flow drives this interface so
 * the flow logic itself stays testable; the production implementation is
 * [NoOpCodecController] until the Shizuku path is verified on hardware.
 */
interface CodecController {
    suspend fun availableCodecs(address: String): List<CodecFamily>

    /** Returns the codec actually in effect afterwards, or null if refused. */
    suspend fun selectCodec(address: String, codec: CodecFamily): CodecFamily?
}

/** Honest stub: codec forcing needs privileges we have not verified yet. */
object NoOpCodecController : CodecController {
    override suspend fun availableCodecs(address: String): List<CodecFamily> = emptyList()
    override suspend fun selectCodec(address: String, codec: CodecFamily): CodecFamily? = null
}
