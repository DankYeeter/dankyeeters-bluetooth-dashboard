package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * One live rate graph across a fixed window, with every window that lost audio
 * marked on the same axis.
 *
 * ## What it is for
 *
 * Two questions, answered by looking rather than reading: **what rate is the
 * link running at**, and **exactly when did it dip**. A dropout is a fall in the
 * line and a mark under it at the same x, so "it stuttered just now" becomes a
 * place on an axis instead of a memory.
 *
 * The plotted series is [TracePoint.plotValue]: the measured LDAC bitrate where
 * the stack reports it, and the enqueue rate as a liveness fallback where it
 * does not. The caption names which of the two it is — the shapes look alike and
 * only one of them is the thing the user came for.
 *
 * ## Drawn by hand, and why the axis ends where it does
 *
 * Canvas rather than a chart library, like the timeline beside it. The axis
 * ends at the **newest reading**, not at the wall clock: if the poller stalls,
 * the picture freezes instead of growing an empty stretch that would read as a
 * measured silence. Anything older than the window falls off the left.
 *
 * A missing or late reading is a **break in the line**, never a straight
 * segment across it — see [LiveTrace.breakBefore]. Interpolating there would
 * draw the smoothest possible link over the exact moment nobody measured it.
 *
 * The scaffolding is a small local copy of the timeline's lane layout rather
 * than a shared helper: the timeline's lanes are private to that file, and one
 * ten-line Row is a cheaper thing to keep in step than a shared component that
 * would have to serve two very different drawings.
 */
@Composable
fun LinkTraceGraph(
    trace: LiveTrace,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
) {
    val colors = MaterialTheme.colorScheme

    if (!trace.hasRate) {
        // Never an empty pair of axes: a blank chart is read as "the link was
        // silent", which is the one thing it does not mean.
        Box(
            modifier.fillMaxWidth().height(height),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                trace.unavailable ?: "Waiting for two readings — a rate is the change between them.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        return
    }

    val newest = trace.newestMs ?: return
    val from = newest - trace.windowMs
    // The line is scaled against its own peak with a little headroom, so a
    // steady link fills the box and a dip is visible rather than a rounding
    // error on an axis that runs to some invented maximum.
    val peak = (trace.peakValue ?: 1.0).coerceAtLeast(1.0) * 1.15

    Canvas(modifier.fillMaxWidth().height(height)) {
        val xOf = { ms: Long ->
            ((ms - from).toDouble() / trace.windowMs).coerceIn(0.0, 1.0).toFloat() * size.width
        }
        val yOf = { value: Double ->
            size.height - (value / peak).coerceIn(0.0, 1.0).toFloat() * size.height
        }

        drawBaseline(colors.outline)

        // Loss first, so the line is drawn over its own marks rather than under
        // them: the rate is the subject, the marks are the annotation.
        trace.points.filter { it.hasLoss }.forEach { point ->
            drawLossMark(xOf(point.timestampMs), colors.error)
        }

        drawTrace(trace, xOf, yOf, colors.primary)
    }
}

/**
 * The rate line, in unbroken runs.
 *
 * Each run is its own [Path] so a break really is a break — one path with a
 * `moveTo` in the middle would still join at the ends on some renderers.
 */
private fun DrawScope.drawTrace(
    trace: LiveTrace,
    xOf: (Long) -> Float,
    yOf: (Double) -> Float,
    color: Color,
) {
    var run = Path()
    var runLength = 0
    var lastPoint: TracePoint? = null

    fun flush() {
        if (runLength > 1) {
            drawPath(run, color = color, style = Stroke(width = 2.5f))
        } else if (runLength == 1) {
            // A single reading with nobody to join is still a measurement, and
            // a path of one point draws nothing at all.
            lastPoint?.plotValue?.let { value ->
                drawCircle(
                    color = color,
                    radius = 2.5f,
                    center = Offset(xOf(lastPoint!!.timestampMs), yOf(value)),
                )
            }
        }
        run = Path()
        runLength = 0
    }

    trace.points.forEachIndexed { index, point ->
        val value = point.plotValue
        if (value == null) {
            flush()
            lastPoint = null
            return@forEachIndexed
        }
        val x = xOf(point.timestampMs)
        val y = yOf(value)
        if (trace.breakBefore(index) || runLength == 0) {
            flush()
            run.moveTo(x, y)
            runLength = 1
        } else {
            run.lineTo(x, y)
            runLength++
        }
        lastPoint = point
    }
    flush()
}

/** A window that lost audio: a full-height stripe with a solid foot. */
private fun DrawScope.drawLossMark(x: Float, color: Color) {
    drawLine(
        color = color.copy(alpha = 0.30f),
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = 3f,
    )
    drawLine(
        color = color,
        start = Offset(x, size.height - 4f),
        end = Offset(x, size.height),
        strokeWidth = 3f,
    )
}

private fun DrawScope.drawBaseline(color: Color) {
    drawLine(
        color = color.copy(alpha = 0.35f),
        start = Offset(0f, size.height),
        end = Offset(size.width, size.height),
        strokeWidth = 1f,
    )
}

/**
 * A graph with its heading and the two numbers worth reading off it.
 *
 * The caption is the part that survives a screenshot: the peak gives the line a
 * scale it otherwise has none of, and the loss count says whether the marks are
 * worth looking for.
 */
@Composable
fun LabelledTraceGraph(
    title: String,
    trace: LiveTrace,
    modifier: Modifier = Modifier,
    /**
     * How to say "nothing was lost" for *this* channel.
     *
     * The close-up reads one dump and can only see the Bluetooth stack's own
     * loss, so a bare "no loss" there would claim something about the app and
     * the mixer that this channel never looked at.
     */
    quietText: String = "no loss in this window",
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            trailing?.invoke()
        }
        LinkTraceGraph(trace)
        Text(
            trace.caption(quietText),
            style = MaterialTheme.typography.labelSmall,
            color = if (trace.lossTotal > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * "396 kbps now · peak 660 · 2 loss marks", with only the parts that exist.
 *
 * The unit is read off the window rather than hard-coded, because the same graph
 * draws two different series — the measured bitrate, and the enqueue rate as a
 * liveness fallback on a link that does not report one. A caption that said
 * "kbps" over the fallback would turn a stand-in into a claim.
 */
private fun LiveTrace.caption(quietText: String): String {
    if (!hasRate) return ""
    return buildList {
        latestValue?.let { add("${it.roundToInt()} $unitLabel now") }
        peakValue?.let { add("peak ${it.roundToInt()}") }
        add(
            when (lossTotal) {
                0L -> quietText
                1L -> "1 loss mark"
                else -> "$lossTotal loss marks"
            },
        )
    }.joinToString(" · ")
}
