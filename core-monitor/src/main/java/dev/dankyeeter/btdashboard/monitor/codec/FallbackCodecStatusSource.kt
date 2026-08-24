package dev.dankyeeter.btdashboard.monitor.codec

import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysLinkSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Reads the codec through the A2DP system API, and falls back to
 * `dumpsys bluetooth_manager` under the helper's shell identity when that fails.
 *
 * The fallback is not a nicety: `BluetoothA2dp.getCodecStatus()` is a hidden
 * `@SystemApi` behind BLUETOOTH_PRIVILEGED, so on stock Android it reliably
 * returns "unsupported" for a normal app — which is why the dashboard used to
 * say "Codec unknown" on a perfectly healthy aptX HD link. The shell *can*
 * read the same numbers out of the dump, so we ask it.
 *
 * Both sources are honest about provenance: [CodecStatus.readVia] says which
 * one produced the values, so the UI can distinguish a real system reading
 * from a scraped one instead of quietly presenting them as equivalent.
 */
class FallbackCodecStatusSource(
    private val primary: CodecStatusSource,
    private val dumpsys: DumpsysLinkSource,
) : CodecStatusSource {

    override val isProfileAvailable: Boolean
        get() = primary.isProfileAvailable || dumpsys.isAvailable

    override suspend fun connectedDevices(): List<BtAudioDevice> {
        val direct = primary.connectedDevices()
        if (direct.isNotEmpty()) return direct
        // The A2DP proxy can be unbound while the link is up (no BLUETOOTH_CONNECT,
        // proxy not yet connected). The dump still lists the active device.
        return runCatching {
            dumpsys.snapshot().devices
                .filter { it.isConnected }
                .map {
                    BtAudioDevice(
                        address = it.address,
                        name = it.name ?: it.address,
                        isActive = it.isActive,
                        isPlaying = it.isPlaying,
                    )
                }
        }.getOrDefault(emptyList())
    }

    /**
     * The primary's flow is the *trigger*; the list that comes out is this
     * class's own. Forwarding the primary's list would skip the dumpsys
     * fallback, so a device the dump can see through an unbound A2DP proxy
     * would be present in [connectedDevices] and absent here - one value
     * disagreeing with itself depending on which way you asked.
     */
    override fun connectedDevicesFlow(): Flow<List<BtAudioDevice>> =
        primary.connectedDevicesFlow()
            .map { connectedDevices() }
            .distinctUntilChanged()

    override suspend fun codecStatus(address: String): CodecReadResult {
        val direct = primary.codecStatus(address)
        if (direct is CodecReadResult.Available) return direct

        val scraped = runCatching { fromDumpsys(address) }.getOrNull()
        // Keep the primary's wording when the fallback has nothing to add —
        // "privileged permission required" is more useful than "not found".
        return scraped ?: direct
    }

    private suspend fun fromDumpsys(address: String): CodecReadResult? {
        if (!dumpsys.isAvailable) return null
        val device = dumpsys.snapshot().devices
            .firstOrNull { sameDevice(it.address, address) && it.isConnected }
            ?: return null
        val family = device.codec ?: return null
        return CodecReadResult.Available(
            CodecStatus(
                family = family,
                sampleRateHz = device.sampleRateHz,
                bitsPerSample = device.bitsPerSample,
                readVia = CodecReadPath.DUMPSYS,
            ),
        )
    }

}

/** Which mechanism produced a [CodecStatus]. */
enum class CodecReadPath {
    /** `BluetoothA2dp.getCodecStatus()` — the real system API. */
    SYSTEM_API,

    /** Scraped out of `dumpsys bluetooth_manager` via the shell identity. */
    DUMPSYS,
}
