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
     * The LDAC quality *mode* changed — which is a configuration change: somebody
     * pinned a quality or handed the link back to ABR.
     *
     * Distinct from [MeasuredBitrateChanged], which is ABR moving on its own
     * inside whatever mode is configured. One is a decision, the other is the
     * radio.
     */
    data class LdacModeChanged(
        override val timestampMs: Long,
        val from: LdacQualityMode?,
        val to: LdacQualityMode,
        val nominalKbps: Int?,
        override val detail: String,
    ) : LinkEvent

    /**
     * The rate the encoder is actually sending moved, and stayed moved.
     *
     * Both figures are **measured** kbps out of the stack's own LDAC state — no
     * inference, no spec table. Which is also why it needs a filter: ABR changes
     * the number constantly, so this only fires for a level that settled and is
     * far enough from the last reported one to be worth a line.
     * [MeasuredBitrateTracker] holds that rule and the measurements behind it.
     */
    data class MeasuredBitrateChanged(
        override val timestampMs: Long,
        /** MEASURED: the last level this event stream reported, or null for the first. */
        val fromKbps: Int?,
        /** MEASURED: the level that has now settled. */
        val toKbps: Int,
        /** Which gate let it through, for anyone auditing the timeline's noise. */
        val reason: BitrateStepReason,
        /** MEASURED, verbatim: the stack's quality-mode token at the time, e.g. `ABR`. */
        val qualityModeLabel: String?,
        override val detail: String,
    ) : LinkEvent {
        /** True when the link got worse — the direction a listener notices. */
        val fell: Boolean get() = fromKbps != null && toKbps < fromKbps
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

    /**
     * The Bluetooth encoder was starving, and here is what the audio processing
     * graph looked like while it was.
     *
     * Distinct from [LossDetected], which fires on *any* loss in a window and is
     * therefore common and cheap. This one fires only on a sustained encoder
     * underflow rate and carries a one-shot forensic capture with it — see
     * [EncoderStarvationTripwire] for the thresholds and for the incident this
     * was built from.
     *
     * The capture is a correlation and says so: it reports the effect instances
     * that were attached at the same moment, and makes no claim that they were
     * the cause. That is the whole point — the moment a next occurrence records
     * this, the accumulation hypothesis is either confirmed or dead.
     */
    data class EncoderStarvation(
        override val timestampMs: Long,
        val report: EncoderStarvationReport,
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
        is LinkEvent.MeasuredBitrateChanged -> MonitorEventType.BITRATE_MODE_CHANGED
        is LinkEvent.LossDetected -> MonitorEventType.DROPOUT
        is LinkEvent.EncoderStarvation -> MonitorEventType.ENCODER_STARVATION
        is LinkEvent.PlaybackChanged ->
            if (isPlaying) MonitorEventType.PLAYING_STARTED else MonitorEventType.PLAYING_STOPPED
        is LinkEvent.ConnectionChanged ->
            if (isConnected) MonitorEventType.ACL_CONNECTED else MonitorEventType.ACL_DISCONNECTED
    },
    detail = detail,
    codec = (this as? LinkEvent.CodecChanged)?.to,
    // Only ever a figure that was established, never a fallback. A measured step
    // supplies the rate the stack reported; a pinned mode supplies its spec
    // figure, which is all a configuration change has. A bitrate column that
    // quietly falls back to the codec's headline number is the exact lie this
    // module is built to avoid, so an adaptive mode change leaves it empty.
    bitrateKbps = when (this) {
        is LinkEvent.LdacModeChanged -> nominalKbps
        is LinkEvent.MeasuredBitrateChanged -> toKbps
        else -> null
    },
)
