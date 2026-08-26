package dev.dankyeeter.btdashboard.monitor.link

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily

/** What the timeline can show. Ordered roughly by how loud it is for the user. */
enum class MonitorEventType {
    ACL_CONNECTED,
    ACL_DISCONNECTED,
    PLAYING_STARTED,
    PLAYING_STOPPED,
    CODEC_CHANGED,
    ACTIVE_DEVICE_CHANGED,
    /** Derived: another device grabbed the stream while this one was playing. */
    TAKEOVER,
    /** Derived: playback stopped without a takeover — a real interruption. */
    INTERRUPTION,
    QUALITY_REPORT,

    /**
     * Measured loss inside one polling window: the Bluetooth tx queue dropped
     * or underflowed, or a mixer track underran. Distinct from [INTERRUPTION],
     * which is inferred from playback simply stopping — this one is a counter
     * that moved.
     */
    DROPOUT,

    /**
     * The codec's configured bitrate *mode* changed — LDAC being pinned to a
     * quality, or unpinned back to adaptive. Deliberately not "the bitrate
     * changed": LDAC's adaptive rate is not observable, so an event claiming to
     * have seen it move would be invented. See `link.live.LdacState`.
     */
    BITRATE_MODE_CHANGED,

    MONITOR_NOTE,
}

/**
 * One entry in the event stream. `detail` is a pre-rendered English sentence so
 * the timeline never has to reconstruct context, and the same string can be
 * exported later.
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
