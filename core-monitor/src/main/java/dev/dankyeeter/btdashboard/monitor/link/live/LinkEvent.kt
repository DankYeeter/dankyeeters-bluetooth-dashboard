package dev.dankyeeter.btdashboard.monitor.link.live

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType

/**
 * Something changed between two polls that is worth a line on the timeline.
 *
 * Every event here is the difference between two [LinkLiveSnapshot]s, so an
 * event only ever exists because two measurements disagreed. Nothing is
 * inferred from a single reading, and nothing is emitted on the first poll of a
 * session — there is nothing to have changed from.
 */
sealed interface LinkEvent {

    val timestampMs: Long

    /** A finished English sentence, ready to render or export. */
    val detail: String

    /** The negotiated codec changed. */
    data class CodecChanged(
        override val timestampMs: Long,
        val from: CodecFamily?,
        val to: CodecFamily,
        override val detail: String,
    ) : LinkEvent

    /**
     * The LDAC quality *mode* changed — which is a configuration change, not
     * ABR moving between 990 and 330. ABR movement is not observable; see
     * [LdacState].
     */
    data class LdacModeChanged(
        override val timestampMs: Long,
        val from: LdacQualityMode?,
        val to: LdacQualityMode,
        val nominalKbps: Int?,
        override val detail: String,
    ) : LinkEvent

    /**
     * The rate the encoder is actually running at moved — the ABR event the
     * whole inference exists for.
     *
     * Fires on a change in **measured frames per packet**, not on a change in
     * an inferred label, so a step is reported even when neither side of it can
     * be named. [from] and [to] are null in exactly that case, and
     * [framesPerPacketRose] still says which way it went: more frames per
     * packet means smaller frames means a lower bitrate, always.
     */
    data class InferredModeChanged(
        override val timestampMs: Long,
        val from: CodecMode?,
        val to: CodecMode?,
        val fromFramesPerPacket: Double,
        val toFramesPerPacket: Double,
        val confidence: InferenceConfidence,
        val nominalKbps: Int?,
        override val detail: String,
    ) : LinkEvent {
        /** True when the rate dropped. See the class note on monotonicity. */
        val framesPerPacketRose: Boolean get() = toFramesPerPacket > fromFramesPerPacket
    }

    /**
     * Audible loss inside one polling window.
     *
     * Deliberately carries the three counters apart rather than one total: they
     * fail in different places and mean different fixes. [inputUnderruns] is an
     * app that could not produce audio fast enough, [mixerUnderruns] is the
     * output thread running dry, and [txDropped]/[txDropouts]/[txUnderflows]
     * are the Bluetooth stack's own queue.
     */
    data class LossDetected(
        override val timestampMs: Long,
        val windowMs: Long,
        val inputUnderruns: Long,
        val mixerUnderruns: Long,
        val txDropped: Long,
        val txDropouts: Long,
        val txUnderflows: Long,
        override val detail: String,
    ) : LinkEvent

    /** The A2DP stream started or stopped. */
    data class PlaybackChanged(
        override val timestampMs: Long,
        val isPlaying: Boolean,
        override val detail: String,
    ) : LinkEvent

    /** The device connected or dropped. */
    data class ConnectionChanged(
        override val timestampMs: Long,
        val isConnected: Boolean,
        override val detail: String,
    ) : LinkEvent
}

/** One poll: the reading, plus whatever changed since the previous one. */
data class LinkLiveUpdate(
    val snapshot: LinkLiveSnapshot,
    val events: List<LinkEvent> = emptyList(),
)

/**
 * Renders a [LinkEvent] into the record type the existing timeline stores.
 *
 * The live view and the historical timeline want different shapes — the live
 * panel needs the counters apart, the timeline needs one sentence and a
 * severity — so this is a mapping and not a shared base class. Keeping the
 * translation in one function means the timeline never learns the live model.
 */
fun LinkEvent.toMonitorEvent(
    deviceAddress: String?,
    deviceName: String?,
): MonitorEvent = MonitorEvent(
    timestampMs = timestampMs,
    deviceAddress = deviceAddress,
    deviceName = deviceName,
    type = when (this) {
        is LinkEvent.CodecChanged -> MonitorEventType.CODEC_CHANGED
        is LinkEvent.LdacModeChanged -> MonitorEventType.BITRATE_MODE_CHANGED
        is LinkEvent.InferredModeChanged -> MonitorEventType.BITRATE_MODE_CHANGED
        is LinkEvent.LossDetected -> MonitorEventType.DROPOUT
        is LinkEvent.PlaybackChanged ->
            if (isPlaying) MonitorEventType.PLAYING_STARTED else MonitorEventType.PLAYING_STOPPED
        is LinkEvent.ConnectionChanged ->
            if (isConnected) MonitorEventType.ACL_CONNECTED else MonitorEventType.ACL_DISCONNECTED
    },
    detail = detail,
    codec = (this as? LinkEvent.CodecChanged)?.to,
    // Only ever a figure that was established, never a fallback. A pinned mode
    // supplies its spec rate; an inferred one supplies its rate only once the
    // inference actually resolved. A bitrate column that quietly falls back to
    // the codec's headline number is the exact lie this module is built to
    // avoid, so an unresolved inference leaves it empty.
    bitrateKbps = when (this) {
        is LinkEvent.LdacModeChanged -> nominalKbps
        is LinkEvent.InferredModeChanged -> nominalKbps
        else -> null
    },
)
