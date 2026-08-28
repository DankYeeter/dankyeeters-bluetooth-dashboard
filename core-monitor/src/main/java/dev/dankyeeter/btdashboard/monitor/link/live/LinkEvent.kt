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
 *
 * ## Why the sentence is rebuilt here
 *
 * [LinkEvent.detail] is written by the poller for the poller: "A2DP stream
 * started", "Audio loss: 3 app underrun(s)". Both are accurate and neither is
 * English a listener would use — one names a Bluetooth profile, the other has a
 * plural marker from a `printf` in it. The structured fields are right here, so
 * the timeline's sentence is built from them by [userDetail] rather than
 * inherited from a string meant for a different reader. The poller keeps its
 * own wording for its own log; nothing has to be changed under this file for
 * the event log to read properly.
 *
 * @param linkCodec the codec the link was running when the event was taken.
 *   Carried so a rate event can name what the rate belongs to — "LDAC 660 → 990
 *   kbps" is a fact, "660 → 990 kbps" is a number with no subject. Null when the
 *   caller does not know, which is honest and simply drops the prefix.
 */
fun LinkEvent.toMonitorEvent(
    deviceAddress: String?,
    deviceName: String?,
    linkCodec: CodecFamily? = null,
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
    detail = userDetail(deviceName),
    // The codec a rate belongs to travels with the rate; a loss or a playback
    // change is about the link rather than about a codec, and claiming one there
    // would put a codec name on a row that never established it.
    codec = when (this) {
        is LinkEvent.CodecChanged -> to
        is LinkEvent.LdacModeChanged, is LinkEvent.MeasuredBitrateChanged -> linkCodec
        else -> null
    },
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

/**
 * The detail layer's sentence for a live event: what happened, in the values it
 * happened in, with no counter names or profile acronyms.
 *
 * This is the *long* form on purpose — it is what opens when somebody taps the
 * row, so it may name the window a loss was counted over and the mode token the
 * stack printed. What it may not do is read like the line of code that produced
 * it, which is the difference between this and [LinkEvent.detail].
 */
private fun LinkEvent.userDetail(deviceName: String?): String {
    val device = deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: "the headphone"
    return when (this) {
        is LinkEvent.ConnectionChanged ->
            if (isConnected) {
                "$device is connected again."
            } else {
                "The link to $device dropped."
            }

        is LinkEvent.PlaybackChanged ->
            if (isPlaying) {
                "Audio started flowing to $device."
            } else {
                "Audio stopped flowing to $device."
            }

        is LinkEvent.CodecChanged ->
            "The link renegotiated from ${from?.displayName ?: "an unread codec"} " +
                "to ${to.displayName}."

        is LinkEvent.LdacModeChanged -> buildString {
            append("LDAC quality changed from ${from?.label ?: "an unread mode"} to ${to.label}.")
            // The spec figure, said to be the spec figure. This is the honesty
            // rule the live panel follows in its own words, kept here so an
            // exported log cannot be read as a measurement.
            nominalKbps?.let { append(" That mode's rated figure is $it kbps.") }
        }

        is LinkEvent.MeasuredBitrateChanged -> buildString {
            if (fromKbps == null) {
                append("The measured rate settled at $toKbps kbps.")
            } else {
                append("The measured rate ")
                append(if (fell) "fell" else "rose")
                append(" from $fromKbps to $toKbps kbps.")
            }
            append(" Measured out of the Bluetooth stack, not a rated figure.")
            qualityModeLabel?.takeIf { it.isNotBlank() }?.let {
                append(" The stack reported quality mode $it.")
            }
        }

        is LinkEvent.LossDetected -> buildString {
            append("Audio was lost")
            if (windowMs > 0) append(" in a ${seconds(windowMs)} s window")
            append(": ")
            append(lossParts().joinToString(", "))
            append(".")
        }

        // Already written for a reader rather than for a log — it is the one
        // live sentence built to be read after the fact, so it is quoted whole.
        is LinkEvent.EncoderStarvation -> detail
    }
}

/**
 * The counters that actually moved, each named for what it means.
 *
 * Carried apart rather than summed, for the reason [LinkEvent.LossDetected]
 * gives: an app that could not produce audio and a radio queue that overflowed
 * are different faults with different fixes, and one total would hide which.
 */
private fun LinkEvent.LossDetected.lossParts(): List<String> = buildList {
    if (inputUnderruns > 0) add(count(inputUnderruns, "app underrun"))
    if (mixerUnderruns > 0) add(count(mixerUnderruns, "mixer underrun"))
    if (txDropped > 0) add(count(txDropped, "dropped packet"))
    if (txDropouts > 0) add(count(txDropouts, "stack dropout"))
    if (txUnderflows > 0) add(count(txUnderflows, "encoder underflow"))
    // Never an empty list: the event only exists because something was counted,
    // but a future counter added upstream must not produce "Audio was lost: ."
    if (isEmpty()) add("no counter named it")
}

/** "1 dropped packet" / "3 dropped packets" — never "3 dropped packet(s)". */
private fun count(value: Long, singular: String): String =
    if (value == 1L) "$value $singular" else "$value ${singular}s"

/**
 * A window in seconds: "2", or "1.8" when the poll really was uneven.
 *
 * Built by integer arithmetic rather than by `String.format`, because this
 * module is locale-free by design and a German default locale would otherwise
 * print "1,8" into a sentence the rest of which is English.
 */
private fun seconds(ms: Long): String {
    val tenths = (ms + 50L) / 100L
    return if (tenths % 10L == 0L) "${tenths / 10L}" else "${tenths / 10L}.${tenths % 10L}"
}
