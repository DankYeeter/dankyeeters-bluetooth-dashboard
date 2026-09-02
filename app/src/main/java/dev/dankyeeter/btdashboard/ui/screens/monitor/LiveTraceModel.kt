package dev.dankyeeter.btdashboard.ui.screens.monitor

import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxDelta
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LinkObservability
import dev.dankyeeter.btdashboard.monitor.link.live.TxProbeSample

/**
 * One reading on a live graph.
 *
 * Every value is nullable, and that is the honesty rule of the whole file: a
 * moment that could not be read — the first pass of a run, a counter that
 * restarted, a dump that failed — has no value, and the graph draws a gap rather
 * than a zero. A zero on a rate line reads as "the link stopped", and that is a
 * completely different fact from "nobody managed to measure this half-second".
 *
 * The two series are not interchangeable and the difference is the point of this
 * whole rebuild:
 *
 *  - [bitrateKbps] is **MEASURED** — the stack's own figure for what the encoder
 *    is sending. When it is there it is what the graph plots, because it is the
 *    thing the user is actually asking about;
 *  - [packetsPerSecond] is DERIVED and is a liveness signal, not a throughput
 *    one. It is the fallback for a link whose rate is not printed, where a line
 *    that dips to zero still means "the stack stopped handing audio over".
 */
data class TracePoint(
    val timestampMs: Long,
    /** MEASURED: the encoder's live bitrate at this instant, in kbps. */
    val bitrateKbps: Double? = null,
    /** DERIVED: enqueue ticks per second across the window ending here. */
    val packetsPerSecond: Double? = null,
    /**
     * DERIVED: everything this window counted as lost, or null when not one
     * of the counters could be differenced.
     *
     * Zero and null are different readings and the caption tells them apart. A
     * zero says "every counter this channel reads stood still"; a null says the
     * window has no countable loss in it at all — the first pass of a run, a
     * counter that restarted, a codec the controller encodes. Calling that zero
     * would put a window nobody measured into the denominator of "{k} of {n}
     * windows lost something" (AK-T002-11, `GOAL.md` AK-3).
     */
    val lossCount: Long? = null,
    /**
     * MEASURED: whether the stack's send queue held anything at this reading,
     * or null where the reading does not carry it.
     *
     * One bit rather than the length, because the length is not what the panel
     * is allowed to say anything about: a single queued packet on a step change
     * is the regulator working, and only the *share* of readings over a window
     * separates that from a link at its limit (AK-T009-29, `UI_SPEC.md` T-009).
     * Null on the close-up channel, whose probe never reads the queue — see
     * [TxProbeSample.toTracePoint].
     */
    val txQueueNotEmpty: Boolean? = null,
) {
    val hasLoss: Boolean get() = (lossCount ?: 0L) > 0

    /**
     * What this point contributes to the line, measured figure first.
     *
     * A single accessor rather than a branch at each call site, so the graph,
     * the peak, the caption and the gap rule can never disagree about which
     * series they are describing.
     */
    val plotValue: Double? get() = bitrateKbps ?: packetsPerSecond
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
    val hasRate: Boolean get() = points.any { it.plotValue != null }

    val peakValue: Double?
        get() = points.mapNotNull { it.plotValue }.maxOrNull()

    val latestValue: Double?
        get() = points.lastOrNull { it.plotValue != null }?.plotValue

    /**
     * What the plotted line is measuring, for the caption.
     *
     * Decided by the window rather than by the newest point, so a single reading
     * that lost the bitrate field does not relabel the axis mid-graph. A window
     * that carries any measured bitrate is a bitrate graph.
     */
    val unitLabel: String
        get() = if (points.any { it.bitrateKbps != null }) "kbps" else "packets/s"

    /** True when the line is the measured bitrate rather than the liveness fallback. */
    val isMeasuredBitrate: Boolean get() = points.any { it.bitrateKbps != null }

    /**
     * AK-T002-11's `k`: how many **marks** the graph drew.
     *
     * One window that lost something is one mark, whether it lost one packet or
     * 525 of them, so this is a count of windows and never of events. The
     * caption said `525 loss marks` under a single mark until DR-002.
     */
    val lossWindowCount: Int get() = points.count { it.hasLoss }

    /** AK-T002-11's `n`: windows whose loss could be counted at all. */
    val measuredWindowCount: Int get() = points.count { it.lossCount != null }

    /** AK-T002-11's `m`: readings that carry no countable loss, named rather than hidden. */
    val unmeasuredWindowCount: Int get() = points.count { it.lossCount == null }

    /**
     * AK-T009-29: the share of readings in this window whose send queue was not
     * empty, or null when no reading in it could say.
     *
     * The denominator is the readings that carried the queue, not every reading
     * in the window — a share over samples that were never taken would be the
     * same lie as drawing a line across a gap. Null rather than 0.0 for the same
     * reason: nothing read is not "the queue was empty".
     */
    val queuePressureFraction: Double?
        get() {
            val known = points.mapNotNull { it.txQueueNotEmpty }
            return if (known.isEmpty()) null else known.count { it }.toDouble() / known.size
        }

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
        if (previous.plotValue == null || current.plotValue == null) return true
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
 *
 * Note the bitrate comes from the reading and the loss from the difference, so
 * at 2 Hz the very first sample of a run already draws a point.
 */
fun TxProbeSample.toTracePoint(): TracePoint = TracePoint(
    timestampMs = timestampMs,
    bitrateKbps = bitrateKbps?.toDouble(),
    packetsPerSecond = delta?.packetsPerSecond,
    // No delta, no count: the rate is a reading and survives, the loss is a
    // difference and does not exist yet.
    lossCount = delta?.lossCount,
    // This probe reads the tx block and the LDAC bitrate, not the queue length,
    // so it says nothing about queue pressure rather than saying "empty".
    txQueueNotEmpty = null,
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
    bitrateKbps = ldac?.measuredKbps?.toDouble(),
    packetsPerSecond = txDelta?.packetsPerSecond,
    lossCount = lossCountThisWindow(),
    txQueueNotEmpty = ldac?.stack?.savedTxQueueLength?.let { it > 0 },
)

/**
 * Everything this window counted as lost, from every counter that saw some — or
 * null when not one of them could be differenced.
 *
 * The stack's share of it is [A2dpTxDelta.lossCount], which reads the one
 * definition of what counts as loss ([A2dpTxDelta.lossByChannel]) rather than
 * naming the counters again here. Encoder underflows are not among them: a mark
 * on the graph claims something was lost at that instant, and the counter that
 * rose through 39 minutes of clean playback cannot make that claim — it would
 * have drawn about 23 marks over music that was fine (AK-T009-24).
 */
private fun LinkLiveSnapshot.lossCountThisWindow(): Long? {
    val inputUnderruns = inputs.mapNotNull { it.underrunDelta }.takeIf { it.isNotEmpty() }?.sum()
    val counted = listOfNotNull(
        inputUnderruns,
        mixer?.fastMixerUnderrunDelta,
        mixer?.normalMixerEmptyDelta,
        txDelta?.lossCount,
    )
    return if (counted.isEmpty()) null else counted.sum()
}

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
    // A measured bitrate is a reading, not a difference, so once it is there
    // none of the "waiting for a second poll" branches below apply.
    ldac?.measuredKbps != null -> null

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
