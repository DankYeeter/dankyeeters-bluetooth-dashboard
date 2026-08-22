package dev.dankyeeter.btdashboard.monitor.sampling

import dev.dankyeeter.btdashboard.monitor.codec.CodecReadPath
import dev.dankyeeter.btdashboard.monitor.codec.CodecReadResult
import dev.dankyeeter.btdashboard.monitor.codec.CodecStatusSource
import dev.dankyeeter.btdashboard.monitor.codec.sameDevice
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysLinkSource
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample
import dev.dankyeeter.btdashboard.monitor.link.QualityReportSource

/**
 * Builds one sample per connected device, walking PLAN.md's source hierarchy:
 * BQR first, then the codec-status API, then the dumpsys scrape. Each level
 * fills in what the level above could not, and the resulting sample records
 * which source it actually came from so the UI can be honest about it.
 */
class LinkSampleCollector(
    private val codecSource: CodecStatusSource,
    private val dumpsysSource: DumpsysLinkSource,
    private val qualityReportSource: QualityReportSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** The source currently in charge — shown as a badge on the Monitor screen. */
    fun activeSource(): LinkDataSource = when {
        qualityReportSource.availability.value.isActive -> LinkDataSource.QUALITY_REPORT
        codecSource.isProfileAvailable -> LinkDataSource.CODEC_API
        dumpsysSource.isAvailable -> LinkDataSource.DUMPSYS
        else -> LinkDataSource.NONE
    }

    suspend fun collect(): List<LinkQualitySample> {
        val now = clock()
        // Cheapest possible exit, taken before anything execs a shell command:
        // with neither the A2DP profile nor the shell reachable there is no
        // mechanism that could report a connected device, so the run has
        // nothing to find and every source below would only cost battery
        // discovering that.
        if (!codecSource.isProfileAvailable && !dumpsysSource.isAvailable) return emptyList()

        val devices = codecSource.connectedDevices()
        // No A2DP device and no way to find one another way: stop here rather
        // than paying for a dump we already know cannot contribute a row.
        if (devices.isEmpty() && !dumpsysSource.isAvailable) return emptyList()
        // dumpsys is the only source of RSSI without privileged APIs, and the
        // only source at all when the A2DP proxy is unreachable.
        val dump = if (dumpsysSource.isAvailable) dumpsysSource.snapshot() else null
        // Only connected devices. A `dumpsys bluetooth_manager` lists every
        // address the phone has ever seen — around 200 on a phone in daily use
        // — and taking the whole list wrote one row per stranger per poll,
        // which buried the real link in ~200x its own noise.
        val dumpByAddress = dump?.devices
            ?.filter { it.isConnected }
            ?.associateBy { it.address }
            .orEmpty()

        if (devices.isEmpty() && dumpByAddress.isEmpty()) return emptyList()

        // The A2DP proxy reports the real address; a user-build dump redacts it
        // to "XX:XX:XX:XX:35:6A". Comparing them as strings made one headphone
        // look like two devices — one row with the codec, one with no source at
        // all. The real address wins, because that is what profiles key on.
        val addresses = buildList {
            addAll(devices.map { it.address })
            dumpByAddress.keys.forEach { dumped ->
                if (none { sameDevice(it, dumped) }) add(dumped)
            }
        }
        return addresses.map { address ->
            val device = devices.firstOrNull { it.address == address }
            val dumped = dumpByAddress.entries
                .firstOrNull { sameDevice(it.key, address) }?.value
            val codecStatus = device
                ?.let { codecSource.codecStatus(address) }
                ?.let { it as? CodecReadResult.Available }
                ?.status

            val source = when {
                qualityReportSource.availability.value.isActive -> LinkDataSource.QUALITY_REPORT
                // The codec source may itself have fallen back to the shell, so
                // ask the reading where it came from rather than crediting the
                // system API for rows that `dumpsys` produced.
                codecStatus != null -> when (codecStatus.readVia) {
                    CodecReadPath.SYSTEM_API -> LinkDataSource.CODEC_API
                    CodecReadPath.DUMPSYS -> LinkDataSource.DUMPSYS
                }
                dumped != null -> LinkDataSource.DUMPSYS
                else -> LinkDataSource.NONE
            }

            LinkQualitySample(
                timestampMs = now,
                deviceAddress = address,
                source = source,
                rssiDbm = dumped?.rssiDbm,
                codec = codecStatus?.family ?: dumped?.codec,
                bitrateKbps = codecStatus?.bitrateKbps,
                sampleRateHz = codecStatus?.sampleRateHz ?: dumped?.sampleRateHz,
                isPlaying = device?.isPlaying ?: dumped?.isPlaying ?: false,
            )
        }
    }
}
