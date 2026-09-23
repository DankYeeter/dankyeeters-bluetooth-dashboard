package dev.dankyeeter.btdashboard.monitor.link.live

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import kotlin.math.min

/**
 * Whether two readings [spanMs] apart are too far apart to be joined.
 *
 * The one gap rule of the live view: the graph breaks its line here and the
 * observation run stops counting here. One function, so that the two can not
 * come to disagree about what "a missed reading" is (`UI_SPEC.md` T-039,
 * decision 2).
 */
fun isReadingGap(spanMs: Long, expectedIntervalMs: Long): Boolean =
    spanMs > expectedIntervalMs * GAP_INTERVALS

/** How many poll intervals may pass between two readings before they are a gap. */
private const val GAP_INTERVALS = 2

/** Why a run stopped counting. Each one has its own sentence on screen. */
enum class RunEnd {
    /** The user pressed Stop. */
    STOPPED,

    /** The headphone disconnected, or another one became the link. */
    DISCONNECTED,

    /** LDAC was pinned or unpinned by hand. */
    QUALITY_CHANGED,

    /** The codec is no longer the one the run began on. */
    CODEC_CHANGED,

    /** No reading arrived for longer than [ObservationRun.RUN_GAP_MAX_MS]. */
    READING_GAP,

    /** Playback stayed paused for longer than [ObservationRun.RUN_GAP_MAX_MS]. */
    PAUSED,

    /** Playback went on, but the stack stopped printing the rate. */
    RATE_UNREADABLE,
}

/** Time at one step of the ladder, as the run saw it. */
data class StepDwell(
    /** The lowest reading grouped into this step, in kbps. */
    val kbps: Int,
    /** DERIVED: the covered intervals whose both ends sit on this step. */
    val ms: Long,
)

/**
 * An observation run: a stretch the user started on purpose, with the three
 * figures that tell whether a pinned step would hold (`GOAL.md` AK-17,
 * `UI_SPEC.md` T-039).
 *
 * ## The counting unit is the covered interval
 *
 * A covered interval is the span between two consecutive readings that both
 * carry a measured rate and lie no further apart than [isReadingGap] allows.
 * Every other span is a gap and goes into no figure — only into the gap count,
 * so the screen can name it. Counting intervals rather than readings keeps the
 * figures independent of the poll rate: a 5 s stretch weighs five times a 1 s
 * one, as it should, whatever the cadence was.
 *
 * ## Provenance of each figure (`GOAL.md` AK-8)
 *
 *  - [lowestKbps] — MEASURED, the lowest single reading;
 *  - [observedMs] — MEASURED, the sum of the covered intervals, not the time
 *    since the start;
 *  - [atOrAbovePercent] — DERIVED from [pairMs] and [observedMs];
 *  - [stepDwell] and [movingMs] — DERIVED from [pairMs] and
 *    [MeasuredBitrateTracker.LEVEL_TOLERANCE_KBPS];
 *  - the threshold handed to [atOrAbovePercent] is the user's choice. It is not
 *    a reading and nothing here labels it as one.
 *
 * ## No time source of its own
 *
 * Pure arithmetic over the timestamps the readings carry. It schedules nothing
 * and keeps nothing beyond this object: it lives as long as the screen that
 * holds it (`GOAL.md` AK-4).
 */
data class ObservationRun(
    /** MEASURED: the sum of all covered intervals — `{T}` on screen. */
    val observedMs: Long = 0L,
    /** Readings that carried a measured rate — `{n}` on screen. */
    val readings: Int = 0,
    /** MEASURED: the lowest single reading of the run, in kbps. */
    val lowestKbps: Int? = null,
    /**
     * Time per pair of consecutive readings, `(from, to) -> ms`.
     *
     * Every figure follows from this without a stored series, including the
     * share over any threshold the user picks later.
     */
    val pairMs: Map<Pair<Int, Int>, Long> = emptyMap(),
    /**
     * Covered time touching a rate beyond [RUN_MAX_LEVELS]: in [observedMs],
     * in no step and in no share.
     */
    val unseparatedMs: Long = 0L,
    /** Whether a rate arrived that the run could no longer keep apart. */
    val levelLimitReached: Boolean = false,
    /** Total length of the spans between two rate readings that were not covered. */
    val gapMs: Long = 0L,
    /** How many such spans there were. */
    val gapCount: Int = 0,
    /** Why the run ended, or null while it is counting. */
    val end: RunEnd? = null,
    private val levels: Set<Int> = emptySet(),
    private val link: RunLink? = null,
    private val lastSeenMs: Long? = null,
    /** The last reading that carried a rate: its timestamp and kbps. */
    private val lastRate: Pair<Long, Int>? = null,
    /** The first poll of the pause in progress, or null while playing. */
    private val pausedSinceMs: Long? = null,
) {

    /**
     * The sample rate of the run's link at its first reading, or null before
     * one. The threshold chips are figured from it, so an ended run keeps the
     * ladder it was measured on, wherever the link has moved since.
     */
    val sampleRateHz: Int?
        get() = link?.sampleRateHz

    /** True until both minimums are met; the figures stay hidden until then. */
    val isCollecting: Boolean
        get() = observedMs < RUN_MIN_OBSERVED_MS || readings < RUN_MIN_READINGS

    /**
     * Feeds one poll. Readings at or before the last one seen are ignored: the
     * shared poll replays its newest reading to every new collector.
     */
    fun plus(snapshot: LinkLiveSnapshot, expectedIntervalMs: Long): ObservationRun {
        val now = snapshot.timestampMs
        val previous = lastSeenMs
        if (end != null || (previous != null && now <= previous)) return this
        if (previous != null && now - previous > RUN_GAP_MAX_MS) return copy(end = RunEnd.READING_GAP)

        // No device block at all is a failed dump: a missed reading, nothing more.
        val device = snapshot.device ?: return copy(lastSeenMs = now)
        val runLink = link ?: snapshot.runLink()
        snapshot.endAgainst(runLink)?.let { return copy(end = it, link = runLink) }

        if (!device.isPlaying) {
            val pausedSince = pausedSinceMs ?: now
            if (now - pausedSince > RUN_GAP_MAX_MS) return copy(end = RunEnd.PAUSED, link = runLink)
            return copy(link = runLink, lastSeenMs = now, pausedSinceMs = pausedSince)
        }
        val playing = copy(link = runLink, lastSeenMs = now, pausedSinceMs = null)
        val kbps = snapshot.ldac?.measuredKbps ?: return playing
        val chained = lastRate != null && lastRate.first == previous
        return playing.withRate(now, kbps, chained, expectedIntervalMs)
    }

    /** Ends a counting run by hand. An ended run stays as it ended. */
    fun stopped(): ObservationRun = if (end == null) copy(end = RunEnd.STOPPED) else this

    /**
     * DERIVED: the share of [observedMs] spent in intervals whose both ends were
     * at or above [thresholdKbps], in whole percent, rounded down.
     *
     * An interval that crosses the threshold counts against the share, and the
     * rounding goes the same way, so the figure is a lower bound.
     */
    fun atOrAbovePercent(thresholdKbps: Int): Int {
        if (observedMs == 0L) return 0
        val above = pairMs.filterKeys { (from, to) -> min(from, to) >= thresholdKbps }.values.sum()
        return (above * PERCENT / observedMs).toInt()
    }

    /** DERIVED: time on each step, longest first. Steps held for no interval are left out. */
    val stepDwell: List<StepDwell>
        get() {
            val stepOf = stepOfLevel()
            return pairMs.entries
                .filter { (pair, _) -> stepOf[pair.first] == stepOf[pair.second] }
                .groupBy({ stepOf.getValue(it.key.first) }, { it.value })
                .map { (step, spans) -> StepDwell(step, spans.sum()) }
                .sortedByDescending { it.ms }
        }

    /** DERIVED: time in intervals whose two ends sit on different steps. */
    val movingMs: Long
        get() {
            val stepOf = stepOfLevel()
            return pairMs.filterKeys { (from, to) -> stepOf[from] != stepOf[to] }.values.sum()
        }

    private fun withRate(now: Long, kbps: Int, chained: Boolean, expectedIntervalMs: Long): ObservationRun {
        val kept = kbps in levels || levels.size < RUN_MAX_LEVELS
        val next = copy(
            readings = readings + 1,
            lowestKbps = min(lowestKbps ?: kbps, kbps),
            levels = if (kept) levels + kbps else levels,
            levelLimitReached = levelLimitReached || !kept,
            lastRate = now to kbps,
        )
        val (fromMs, fromKbps) = lastRate ?: return next
        val span = now - fromMs
        if (!chained || isReadingGap(span, expectedIntervalMs)) {
            return next.copy(gapMs = gapMs + span, gapCount = gapCount + 1)
        }
        val pair = fromKbps to kbps
        return if (pair.first in next.levels && pair.second in next.levels) {
            next.copy(observedMs = observedMs + span, pairMs = pairMs + (pair to (pairMs[pair] ?: 0L) + span))
        } else {
            next.copy(observedMs = observedMs + span, unseparatedMs = unseparatedMs + span)
        }
    }

    /**
     * Each kept rate mapped to its step: rates are taken in ascending order, and
     * one within [MeasuredBitrateTracker.LEVEL_TOLERANCE_KBPS] of the current
     * step's first rate joins it. Both ends of an interval on one step are
     * therefore within the tolerance of the step's figure.
     */
    private fun stepOfLevel(): Map<Int, Int> {
        var step: Int? = null
        return levels.sorted().associateWith { kbps ->
            val current = step?.takeIf { kbps - it <= MeasuredBitrateTracker.LEVEL_TOLERANCE_KBPS } ?: kbps
            step = current
            current
        }
    }

    companion object {

        /** A run that counts only readings newer than [afterMs], the reading on screen at the tap. */
        fun startedAfter(afterMs: Long?): ObservationRun = ObservationRun(lastSeenMs = afterMs)

        /**
         * Observed time before any figure is shown: 2 min.
         *
         * Set, narrowly argued, not measured. The one settling measured took
         * 18.5 s (T-009) and step changes came some 11 s apart, so 120 s is about
         * six settlings and eleven changes — long enough for settled operation to
         * outweigh the settling (`UI_SPEC.md` T-039, parameters). TODO(M-15)
         */
        const val RUN_MIN_OBSERVED_MS = 120_000L

        /**
         * Rate readings before any figure is shown: three times the ten that
         * `UI_SPEC.md` T-002 already requires before anything statistical is said
         * (`RATE_MIN_EVENTS_IN_WINDOW`). At a 5 s cadence this outlasts
         * [RUN_MIN_OBSERVED_MS]; both must be met, so no cadence undercuts either.
         */
        const val RUN_MIN_READINGS = 30

        /**
         * Longest span without any reading, and longest pause, that a run
         * survives: 2 min. A pause counts from its first paused poll to the
         * current one; a playing poll ends it.
         *
         * A convention, not a measurement. A pause longer than a run needs before
         * it may say anything separates two observations rather than sitting
         * inside one.
         */
        const val RUN_GAP_MAX_MS = 120_000L

        /**
         * Distinct rates a run keeps apart. A memory bound for [pairMs] only; past
         * it, new rates count in [observedMs] and in no step, and the screen says
         * so. Four ABR steps were measured in one session; this was never reached.
         */
        const val RUN_MAX_LEVELS = 24

        private const val PERCENT = 100L
    }
}

/**
 * What a run belongs to: one pairing, one codec, one LDAC setting. Public only
 * because [ObservationRun]'s constructor is. [sampleRateHz] only names the
 * ladder the run's thresholds come from; it ends no run.
 */
data class RunLink(
    val address: String?,
    val codec: CodecFamily?,
    val mode: LdacQualityMode?,
    val adaptive: Boolean?,
    val sampleRateHz: Int?,
)

private fun LinkLiveSnapshot.runLink() =
    RunLink(device?.address, codec?.family, ldac?.mode, ldac?.isAdaptive, codec?.sampleRateHz)

/**
 * Why this reading ends a run on [link], or null when it continues it.
 *
 * A paused stream continues it as a gap; [ObservationRun.plus] ends the run
 * once the pause outlasts [ObservationRun.RUN_GAP_MAX_MS]. A playing stream
 * whose rate the stack no longer prints ends it: the build stopped answering
 * the question the run asks.
 */
private fun LinkLiveSnapshot.endAgainst(link: RunLink): RunEnd? {
    val device = device
    return when {
        device == null || !device.isConnected || device.address != link.address -> RunEnd.DISCONNECTED
        codec?.family != link.codec -> RunEnd.CODEC_CHANGED
        ldac?.mode != link.mode || ldac?.isAdaptive != link.adaptive -> RunEnd.QUALITY_CHANGED
        device.isPlaying && !hasReadableRate -> RunEnd.RATE_UNREADABLE
        else -> null
    }
}

/** Whether this link's rate is a reading at all: LDAC, host-encoded, and printed by the stack. */
val LinkLiveSnapshot.hasReadableRate: Boolean
    get() = ldac?.liveBitrateHonesty == Honesty.MEASURED && codec?.isOffloaded != true
