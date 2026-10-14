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

