package dev.dankyeeter.btdashboard.monitor.link

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily

/**
 * Which layer of the event log a type is allowed to reach.
 *
 * The log has two: a **list** of one-line summaries somebody scans, and a
 * **detail** view they open when one of those lines matters. The split is not
 * cosmetic — it is the rule that keeps the list readable. A type is [LIST] only
 * if its line tells a listener something concrete about their audio; everything
 * that exists to explain the machinery to itself is [DETAIL], still recorded,
 * still exportable, just not competing for the eye with a dropout.
 */
enum class EventLayer {
    /** Worth a line somebody scanning the log should see. */
    LIST,

    /**
     * Recorded and readable, but never in the list.
     *
     * Not "unimportant": these are the rows that carry a state change the app
     * needs (which device is active), a re-statement of something the list
     * already says in better words (the anomaly detector's own summary), or a
     * fallback that exists for a row nothing else could classify.
     */
    DETAIL,
}

/**
 * What the timeline can show.
 *
 * Each entry carries its own audit result rather than leaving the decision to
 * whichever screen renders it: [layer] is whether the type belongs in the list
 * at all, and [loud] is whether it is the kind of thing worth colouring. Both
 * used to be `when` blocks copied between the event log and the timeline, which
 * is how the two ended up disagreeing about whether a disconnect is loud.
 */
enum class MonitorEventType(
    val layer: EventLayer = EventLayer.LIST,
    /** Worth the error colour and a taller tick on the timeline. */
    val loud: Boolean = false,
) {
    ACL_CONNECTED,
    ACL_DISCONNECTED(loud = true),
    PLAYING_STARTED,
    PLAYING_STOPPED,
    CODEC_CHANGED,

    /**
     * Which device Android is currently routing audio to.
     *
     * Detail-layer, and this is the one classification worth arguing about. On
     * its own the line says nothing a listener can act on — the *interesting*
     * half of an active-device change is that it stole the stream, and that is
     * already a first-class [TAKEOVER] with better wording. Left in the list it
     * doubled every takeover and fired on its own for every routing wobble.
     */
    ACTIVE_DEVICE_CHANGED(EventLayer.DETAIL),

    /** Derived: another device grabbed the stream while this one was playing. */
    TAKEOVER(loud = true),

    /** Derived: playback stopped without a takeover — a real interruption. */
    INTERRUPTION(loud = true),

    /**
     * The sampler's own anomaly summary for one pair of samples.
     *
     * Detail-layer: its text is a semicolon-joined list of whatever
     * `AnomalyDetector` noticed, and every finding in it is already reported by
     * a typed event with a better line — a bitrate drop by
     * [BITRATE_MODE_CHANGED], a codec swap by [CODEC_CHANGED], lost packets by
     * [DROPOUT]. It is kept because it is what puts the sampler into burst mode,
     * and reading it afterwards explains why the sampling rate jumped.
     */
    QUALITY_REPORT(EventLayer.DETAIL),

    /**
     * Measured loss inside one polling window: the Bluetooth tx queue dropped
     * or underflowed, or a mixer track underran. Distinct from [INTERRUPTION],
     * which is inferred from playback simply stopping — this one is a counter
     * that moved.
     */
    DROPOUT(loud = true),

    /**
     * The encoder was starving — it had no PCM ready, repeatedly, for several
     * seconds in a row — and a one-shot forensic capture of the audio
     * processing graph was taken while it was happening.
     *
     * Its own type rather than a flavour of [DROPOUT] because it answers a
     * different question. A dropout says the user heard something; this says
     * *the source could not feed the radio*, which is a specific failure with a
     * specific suspect list, and the event carries the evidence needed to work
     * through that list after the fact. See
     * `link.live.EncoderStarvationTripwire`.
     */
    ENCODER_STARVATION(loud = true),

    /**
     * The link's bitrate changed, in one of two ways.
     *
     * Either the configured *mode* moved — LDAC pinned to a quality or handed
     * back to adaptive — or the **measured** rate the stack reports moved and
     * stayed moved. The second kind used to be impossible to state honestly and
     * is now a direct reading; it is filtered so that ABR's ordinary
     * second-to-second wobble does not fill the timeline. See
     * `link.live.LdacStackState` for the reading and
     * `link.live.MeasuredBitrateTracker` for the filter.
     */
    BITRATE_MODE_CHANGED,

    /**
     * Nothing emits this. It is what a stored row decodes to when its `type`
     * column names a constant this build no longer has — a database artifact,
     * by definition unclassifiable, and therefore never a list line.
     */
    MONITOR_NOTE(EventLayer.DETAIL),
}

/**
 * One entry in the event stream. `detail` is a pre-rendered English sentence so
 * the timeline never has to reconstruct context, and the same string can be
 * exported later.
 *
 * `detail` is the **detail layer's** text and is allowed to be a full sentence
 * with values in it. The list never renders it — see [MonitorEventSummary],
 * which derives the one short line a row shows from the typed fields instead.
 */
data class MonitorEvent(
    val timestampMs: Long,
    val deviceAddress: String?,
    val deviceName: String?,
    val type: MonitorEventType,
    val detail: String,
    val codec: CodecFamily? = null,
    val bitrateKbps: Int? = null,
)

/**
 * A periodic measurement. All values are nullable because which source is alive
 * (BQR / codec API / dumpsys) decides which fields can be filled.
 */
data class LinkQualitySample(
    val timestampMs: Long,
    val deviceAddress: String,
    val source: LinkDataSource,
    val rssiDbm: Int? = null,
    val codec: CodecFamily? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val isPlaying: Boolean = false,
    /** BQR only: retransmitted packets since the previous report. */
    val retransmissions: Int? = null,
    /** BQR only: dropped/lost packets since the previous report. */
    val droppedPackets: Int? = null,
    /** BQR only: controller-reported audio glitch ("choppy") count. */
    val glitchCount: Int? = null,
)

/** Which source produced a sample — surfaced in the UI, per PLAN's hierarchy. */
enum class LinkDataSource(val displayName: String, val rank: Int) {
    QUALITY_REPORT("Bluetooth Quality Report", 1),
    CODEC_API("Codec status API", 2),
    DUMPSYS("dumpsys fallback", 3),
    NONE("no source", 4),
}
