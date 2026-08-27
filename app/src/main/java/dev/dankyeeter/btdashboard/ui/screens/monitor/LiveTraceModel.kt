package dev.dankyeeter.btdashboard.ui.screens.monitor

import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxDelta
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LinkObservability
import dev.dankyeeter.btdashboard.monitor.link.live.TxProbeSample

/**
 * One reading on a live graph.
 *
 * Everything here is DERIVED from two measured counter readings, which is why
 * every field is nullable: a window that cannot be measured — the first pass of
 * a run, a counter that restarted, a dump that failed — has no value, and the
 * graph draws a gap rather than a zero. A zero on a throughput line reads as
 * "the link stopped", and that is a completely different fact from "nobody
 * managed to measure this half-second".
 */
data class TracePoint(
    val timestampMs: Long,
    /** DERIVED: media packets per second across the window ending here. */
    val packetsPerSecond: Double?,
    /**
     * DERIVED, and a PROXY for the encoder's rate: codec frames per packet
     * across this window. It moves with the encoder's output size, so it steps
     * when LDAC changes rate — it is not a bitrate and cannot become one.
     */
    val framesPerPacket: Double?,
    /** DERIVED: everything audible counted in this window. Zero is a fact here. */
    val lossCount: Long = 0,
) {
    val hasLoss: Boolean get() = lossCount > 0
}

/**
 * A fixed-length window of [TracePoint]s — the ring buffer behind one graph.
 *
 * Immutable and copied on append. The lists are 20 to 60 entries long, so the
 * copy is cheaper than the bookkeeping a mutable buffer shared with Compose
 * would need, and it means the UI can never be handed a list that changes
 * underneath it mid-draw.
 *
 * Two things bound it, on purpose:
 *
 *  - **time** — anything older than [windowMs] behind the newest point is
 *    dropped, so the window really is the window its label claims;
 *  - **count** — [maxPoints] is a hard ceiling, so a clock that jumps backwards
 *    (or a poller running faster than its own floor) cannot grow this without
 *    limit while the time rule waits for a newer timestamp.
 */
data class LiveTrace(
    val windowMs: Long,
    /** What the spacing should be, for deciding when a gap is a gap. */
    val expectedIntervalMs: Long,
    val maxPoints: Int,
    val points: List<TracePoint> = emptyList(),
    /**
     * Why there is nothing to plot, when there is nothing to plot. Carried
     * rather than derived so the graph can name the reason — an offloaded codec
     * and a link nobody has read yet are both empty and mean opposite things.
     */
    val unavailable: String? = null,
    val observability: LinkObservability = LinkObservability.UNKNOWN,
) {

    /**
     * Appends a point, then trims. Out-of-order and repeated timestamps are
     * dropped: the shared poll flow replays its last reading to a new
     * collector, and counting that twice would put a second spike on the graph
     * for one dropout the user only heard once.
     */
    fun plus(point: TracePoint, expectedIntervalMs: Long = this.expectedIntervalMs): LiveTrace {
        val newest = points.lastOrNull()
        if (newest != null && point.timestampMs <= newest.timestampMs) {
            return copy(expectedIntervalMs = expectedIntervalMs)
        }
        val cutoff = point.timestampMs - windowMs
        val kept = (points + point)
            .filter { it.timestampMs >= cutoff }
            .takeLast(maxPoints)
        return copy(points = kept, expectedIntervalMs = expectedIntervalMs)
    }

    /** Records why the window is empty without putting a point on the graph. */
    fun withReason(reason: String?, observability: LinkObservability): LiveTrace =
        copy(unavailable = reason, observability = observability)

    /** Whether anything at all can be drawn. */
    val hasRate: Boolean get() = points.any { it.packetsPerSecond != null }

    val peakPacketsPerSecond: Double?
        get() = points.mapNotNull { it.packetsPerSecond }.maxOrNull()

    val latestPacketsPerSecond: Double?
        get() = points.lastOrNull { it.packetsPerSecond != null }?.packetsPerSecond

    val lossTotal: Long get() = points.sumOf { it.lossCount }

    /** The instant the axis ends at: the newest reading, never the wall clock. */
    val newestMs: Long? get() = points.lastOrNull()?.timestampMs

    /**
     * Whether the line must break before [index].
     *
     * True when either side of the step has no value, or when the two readings
     * are more than two intervals apart — a missed or late pass. Drawing
     * straight through that would turn "nobody looked" into "it was steady",
     * which are opposite statements and the second one is a lie the eye
     * believes instantly.
     */
    fun breakBefore(index: Int): Boolean {
        if (index <= 0 || index >= points.size) return true
        val previous = points[index - 1]
        val current = points[index]
        if (previous.packetsPerSecond == null || current.packetsPerSecond == null) return true
        return current.timestampMs - previous.timestampMs > expectedIntervalMs * 2
    }

    companion object {
        /** The close-up: ten seconds at two readings a second. */
        fun closeUp(intervalMs: Long) = LiveTrace(
            windowMs = CLOSE_UP_WINDOW_MS,
            expectedIntervalMs = intervalMs,
            // Two spare slots so a pass that lands a few milliseconds early is
            // trimmed by the time rule rather than by the ceiling.
            maxPoints = (CLOSE_UP_WINDOW_MS / intervalMs).toInt() + 2,
        )

        /**
         * The review window: a minute, at whatever rate the panel is polling.
         *
         * Sized against the fastest rate the panel offers rather than the
         * current one, so changing the rate never has to resize the buffer
         * mid-window.
         */
        fun overview(intervalMs: Long) = LiveTrace(
            windowMs = OVERVIEW_WINDOW_MS,
            expectedIntervalMs = intervalMs,
            maxPoints = (OVERVIEW_WINDOW_MS / FASTEST_OVERVIEW_INTERVAL_MS).toInt() + 2,
        )

        const val CLOSE_UP_WINDOW_MS = 10_000L
        const val OVERVIEW_WINDOW_MS = 60_000L

        /** The quickest rate the update-rate chips offer. */
        const val FASTEST_OVERVIEW_INTERVAL_MS = 1_000L
    }
}

/**
 * The close-up channel's point.
 *
 * The loss it can count is the Bluetooth stack's own — this probe never reads
 * the two dumps that carry app and mixer underruns. That limit is stated in the
 * close-up's explainer; it must not be quietly rounded to "no loss".
 */
fun TxProbeSample.toTracePoint(): TracePoint = TracePoint(
    timestampMs = timestampMs,
    packetsPerSecond = delta?.packetsPerSecond,
    framesPerPacket = delta?.framesPerPacket,
    lossCount = delta?.let { it.dropped + it.dropouts + it.underflows } ?: 0L,
)

/**
 * The overview channel's point, from the full pass.
 *
 * Counts loss from all three places the path can lose audio, because this
 * channel really does read all three: the app's own mixer track, the output
 * thread, and the Bluetooth stack's queue.
 */
fun LinkLiveSnapshot.toTracePoint(): TracePoint = TracePoint(
    timestampMs = timestampMs,
    packetsPerSecond = txDelta?.packetsPerSecond,
    framesPerPacket = txDelta?.framesPerPacket,
    lossCount = lossCountThisWindow(),
)

/** Everything audible in this window, from every counter that saw some. */
private fun LinkLiveSnapshot.lossCountThisWindow(): Long =
    inputUnderrunDelta +
        (mixer?.fastMixerUnderrunDelta ?: 0L) +
        (mixer?.normalMixerEmptyDelta ?: 0L) +
        (txDelta?.let { it.dropped + it.dropouts + it.underflows } ?: 0L)

/** Kept for symmetry with the probe channel; both graphs read the same shape. */
internal fun A2dpTxDelta.lossCount(): Long = dropped + dropouts + underflows

/**
 * Appends one full-pass reading, carrying its reason for being unplottable.
 *
 * The reason travels with every append rather than being set once: a link that
 * renegotiates from LDAC to AAC becomes unobservable mid-window, and a graph
 * still captioned from the first reading would be explaining the wrong link.
 */
internal fun LiveTrace.append(snapshot: LinkLiveSnapshot, expectedIntervalMs: Long): LiveTrace =
    plus(snapshot.toTracePoint(), expectedIntervalMs)
        .withReason(snapshot.graphReason(), snapshot.observability)

/**
 * Why this snapshot puts nothing on a throughput graph, or null when it does.
 *
 * Each branch is a different fact and they are worded apart on purpose — an
 * offloaded codec is permanent for this link and a first reading is over in two
 * seconds, and "no data" would hide which one the user is looking at.
 */
private fun LinkLiveSnapshot.graphReason(): String? = when {
    observability == LinkObservability.OFFLOADED ->
        "${codec?.family?.displayName ?: "This codec"} is encoded by the controller, so the " +
            "host cannot see the stream — there is no throughput to plot."

    observability == LinkObservability.UNKNOWN && codec == null ->
        "No negotiated codec yet."

    tx == null ->
        "The Bluetooth stack is not reporting its tx queue on this build."

    txDelta == null ->
        "Waiting for two readings — a rate is the change between them."

    else -> null
}
