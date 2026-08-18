package dev.dankyeeter.btdashboard.monitor.sampling

import dev.dankyeeter.btdashboard.monitor.codec.CodecReadResult
import dev.dankyeeter.btdashboard.monitor.codec.CodecStatusSource
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
        val devices = codecSource.connectedDevices()
        // dumpsys is the only source of RSSI without privileged APIs, and the
        // only source at all when the A2DP proxy is unreachable.
        val dump = if (dumpsysSource.isAvailable) dumpsysSource.snapshot() else null
        val dumpByAddress = dump?.devices?.associateBy { it.address }.orEmpty()

        if (devices.isEmpty() && dumpByAddress.isEmpty()) return emptyList()

        val addresses = (devices.map { it.address } + dumpByAddress.keys).distinct()
        return addresses.map { address ->
            val device = devices.firstOrNull { it.address == address }
            val dumped = dumpByAddress[address]
            val codecStatus = device
                ?.let { codecSource.codecStatus(address) }
                ?.let { it as? CodecReadResult.Available }
                ?.status

            val source = when {
                qualityReportSource.availability.value.isActive -> LinkDataSource.QUALITY_REPORT
                codecStatus != null -> LinkDataSource.CODEC_API
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
