package dev.dankyeeter.btdashboard.monitor.sampling

import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample

/** Everything the policy is allowed to look at. Pure input, no Android. */
data class MonitorConditions(
    val nowMs: Long,
    val isPlaying: Boolean,
    val isScreenOn: Boolean,
    /** Set by the dashboard's "watch live" action and by diagnostic soaks. */
    val deepCaptureUntilMs: Long = 0L,
    /** Set by the anomaly detector after a bitrate/RSSI drop. */
    val burstUntilMs: Long = 0L,
)

enum class SamplingMode(val intervalMs: Long) {
    /** "Watch live" / diagnostic soak: full resolution. */
    DEEP(10_000L),
    /** Something looked wrong — tighten up until the window expires. */
    BURST(5_000L),
    /** Normal playback with the screen on. */
    ACTIVE(30_000L),
    /** Playback in the pocket, or idle with the screen on. */
    BACKGROUND(60_000L),
    /** Nothing to measure: no playback, screen off. Events stay armed. */
    STOPPED(0L),
}

sealed interface SamplingDecision {
    data class Poll(val mode: SamplingMode, val reason: String) : SamplingDecision {
        val intervalMs: Long get() = mode.intervalMs
    }

    data class Stopped(val reason: String) : SamplingDecision
}

/**
 * The battery contract from PLAN.md, in one testable function: events are
 * always on (they cost nothing), polling only happens while there is something
 * to measure, and deep capture is opt-in and time-boxed.
 */
object SamplingPolicy {

    fun decide(conditions: MonitorConditions): SamplingDecision = with(conditions) {
        when {
            nowMs < deepCaptureUntilMs ->
                SamplingDecision.Poll(SamplingMode.DEEP, "deep capture requested")
            isPlaying && nowMs < burstUntilMs ->
                SamplingDecision.Poll(SamplingMode.BURST, "anomaly detected — sampling faster")
            isPlaying && isScreenOn ->
                SamplingDecision.Poll(SamplingMode.ACTIVE, "playing, screen on")
            isPlaying ->
                SamplingDecision.Poll(SamplingMode.BACKGROUND, "playing, screen off")
            isScreenOn ->
                SamplingDecision.Poll(SamplingMode.BACKGROUND, "idle, screen on")
            else ->
                SamplingDecision.Stopped("no playback and screen off")
        }
    }

    /** Deep capture window length used by the "watch live" quick action. */
    const val DEEP_CAPTURE_WINDOW_MS = 5 * 60 * 1000L

    /** How long one anomaly keeps the sampler in burst mode. */
    const val BURST_WINDOW_MS = 2 * 60 * 1000L
}

/**
 * Flags the two things that actually precede an audible dropout: the codec
 * scaling its bitrate down (aptX Adaptive does this constantly under load) and
 * the RSSI falling off a cliff. Pure comparison of two consecutive samples.
 */
object AnomalyDetector {

    const val BITRATE_DROP_FRACTION = 0.25
    const val RSSI_DROP_DB = 10
    const val RSSI_FLOOR_DBM = -80

    fun detect(previous: LinkQualitySample?, current: LinkQualitySample): List<String> {
        val reasons = mutableListOf<String>()
        val prevBitrate = previous?.bitrateKbps
        val bitrate = current.bitrateKbps
        if (prevBitrate != null && bitrate != null && prevBitrate > 0 &&
            bitrate < prevBitrate * (1 - BITRATE_DROP_FRACTION)
        ) {
            reasons += "Bitrate dropped from $prevBitrate to $bitrate kbps"
        }
        if (previous?.codec != null && current.codec != null && previous.codec != current.codec) {
            reasons += "Codec changed from ${previous.codec.displayName} to ${current.codec.displayName}"
        }
        val prevRssi = previous?.rssiDbm
        val rssi = current.rssiDbm
        if (prevRssi != null && rssi != null && prevRssi - rssi >= RSSI_DROP_DB) {
            reasons += "Signal dropped by ${prevRssi - rssi} dB (now $rssi dBm)"
        }
        if (rssi != null && rssi <= RSSI_FLOOR_DBM) {
            reasons += "Signal is weak ($rssi dBm)"
        }
        if ((current.droppedPackets ?: 0) > 0) {
            reasons += "${current.droppedPackets} packets lost"
        }
        if ((current.glitchCount ?: 0) > 0) {
            reasons += "${current.glitchCount} audio glitches reported"
        }
        return reasons
    }
}
