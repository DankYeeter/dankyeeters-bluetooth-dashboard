package dev.dankyeeter.btdashboard.ui.screens.monitor

import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample

/**
 * A stretch of time over which one value held.
 *
 * @param fromMs first sample carrying [value]
 * @param toMs last sample carrying it; equal to [fromMs] for a lone sample
 */
data class TimelineSpan<T>(
    val fromMs: Long,
    val toMs: Long,
    val value: T,
) {
    val durationMs: Long get() = toMs - fromMs
}

/**
 * Longer than this between two samples and the sampler was idle rather than
 * measuring. Matches the sampler's own idle ceiling, so a quiet stretch reads
 * as "nothing was recorded" instead of "nothing happened" — the two look
 * identical on a chart and mean opposite things.
 */
const val TIMELINE_GAP_MS: Long = 5 * 60_000L

/**
 * Splits [samples] into spans of a constant value.
 *
 * A span ends when the value changes, when the sampler went quiet for longer
 * than [gapMs], or when the value becomes unknown. **Unknown produces no
 * span at all** rather than a span of "unknown": the timeline's job is to show
 * what was measured, and a gap where the codec could not be read must look
 * like a gap, not like a reading.
 *
 * This is separated from the drawing so it can be tested — the Canvas above it
 * cannot be, and this is where all the decisions live.
 */
internal fun <T : Any> spansOf(
    samples: List<LinkQualitySample>,
    gapMs: Long = TIMELINE_GAP_MS,
    select: (LinkQualitySample) -> T?,
): List<TimelineSpan<T>> {
    if (samples.isEmpty()) return emptyList()
    val sorted = samples.sortedBy { it.timestampMs }
    val spans = mutableListOf<TimelineSpan<T>>()

    var value: T? = null
    var startMs = 0L
    var lastMs = 0L

    fun close() {
        value?.let { spans += TimelineSpan(startMs, lastMs, it) }
        value = null
    }

    sorted.forEach { sample ->
        val current = select(sample)
        val quiet = value != null && sample.timestampMs - lastMs > gapMs
        if (current == null || quiet || current != value) {
            close()
            if (current != null) {
                value = current
                startMs = sample.timestampMs
            }
        }
        if (current != null) lastMs = sample.timestampMs
    }
    close()
    return spans
}

/**
 * The stretches the sampler was actually awake for, regardless of what it
 * found. Everything outside these is time nobody looked at, and the timeline
 * greys it out instead of drawing a lane straight through it.
 */
internal fun coverageSpans(
    samples: List<LinkQualitySample>,
    gapMs: Long = TIMELINE_GAP_MS,
): List<TimelineSpan<Unit>> = spansOf(samples, gapMs) { Unit }

/**
 * Whether any sample carries a given field. Drives whether a lane is drawn at
 * all: a lane that is structurally always empty — RSSI on a stock build, for
 * one — should not occupy the screen claiming to be a measurement surface.
 */
internal fun <T : Any> hasAny(
    samples: List<LinkQualitySample>,
    select: (LinkQualitySample) -> T?,
): Boolean = samples.any { select(it) != null }
