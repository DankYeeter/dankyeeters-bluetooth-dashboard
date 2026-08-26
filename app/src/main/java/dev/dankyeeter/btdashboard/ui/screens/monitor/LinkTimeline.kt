package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType

/**
 * The link timeline, drawn by hand on a Compose Canvas — no chart library, per
 * PLAN.md. Several lanes share one time axis.
 *
 * ## Why this is not an RSSI chart any more
 *
 * It was, and it was empty for months. `dumpsys bluetooth_manager` does not
 * report RSSI for a *connected* A2DP link — only for scan results — so the one
 * continuous value the chart was built around never arrived, and the screen
 * showed an axis with nothing on it. That is worse than showing nothing: an
 * empty chart reads as "the link was silent", not as "we cannot measure this".
 *
 * The lanes below are the values that do arrive on a stock build, through the
 * codec API and the dump:
 *
 *  - **Playing** — was audio actually flowing;
 *  - **Codec** — which codec was negotiated, and when it changed;
 *  - **Sample rate** — the other half of the codec story;
 *  - **Signal** — RSSI, drawn *only* when something really provides it (BQR
 *    under the helper), so its absence costs no screen space and makes no claim;
 *  - **Events** — connect, disconnect, takeover, interruption.
 *
 * Stretches the sampler slept through are greyed across every lane. A lane
 * drawn straight through a gap would turn "nobody looked" into "nothing
 * happened", which are opposite statements.
 *
 * The lane list above is also what the Monitor screen's timeline panel says
 * behind its question mark — this text is its source. Keep the two in step; a
 * legend printed under the chart itself would be longer than the chart.
 */
@Composable
fun LinkTimeline(
    samples: List<LinkQualitySample>,
    events: List<MonitorEvent>,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()

    if (samples.isEmpty() && events.isEmpty()) {
        Box(
            modifier.fillMaxWidth().height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Nothing recorded yet — lanes fill in as soon as something connects or plays.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        return
    }

    val playing = remember(samples) { spansOf(samples) { s -> true.takeIf { s.isPlaying } } }
    val codecs = remember(samples) { spansOf(samples) { it.codec } }
    val rates = remember(samples) { spansOf(samples) { it.sampleRateHz } }
    val coverage = remember(samples) { coverageSpans(samples) }
    val hasRssi = remember(samples) { hasAny(samples) { it.rssiDbm } }
    // Every codec in this window, in the order it first appeared: the tint each
    // one gets in the lane, and the order the legend names them in.
    val codecOrder = remember(codecs) { codecs.map { it.value }.distinct() }

    val fromMs = minOf(
        samples.minOfOrNull { it.timestampMs } ?: Long.MAX_VALUE,
        events.minOfOrNull { it.timestampMs } ?: Long.MAX_VALUE,
    )
    val toMs = maxOf(
        samples.maxOfOrNull { it.timestampMs } ?: Long.MIN_VALUE,
        events.maxOfOrNull { it.timestampMs } ?: Long.MIN_VALUE,
    )
    val span = (toMs - fromMs).coerceAtLeast(1L)
    val xOf: DrawScope.(Long) -> Float = { ms -> ((ms - fromMs).toFloat() / span) * size.width }
    val hasGaps = remember(coverage, fromMs, toMs) { hasGaps(coverage, fromMs, toMs) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {

        Lane("Playing", 20.dp) {
            drawGaps(coverage, fromMs, toMs, colors.outline, xOf)
            playing.forEach { s ->
                val left = xOf(s.fromMs)
                drawRect(
                    color = colors.primary,
                    topLeft = Offset(left, size.height * 0.25f),
                    size = Size(barWidth(left, xOf(s.toMs)), size.height * 0.5f),
                )
            }
            if (playing.isEmpty()) {
                drawBaseline(colors.outline)
            }
        }

        Lane("Codec", 26.dp) {
            drawGaps(coverage, fromMs, toMs, colors.outline, xOf)
            codecs.forEachIndexed { index, s ->
                val left = xOf(s.fromMs)
                val width = barWidth(left, xOf(s.toMs))
                // The fill still does not carry the information on its own — a
                // tint dark enough to sit behind text cannot clear 3:1 against
                // a black surface — but one tint per codec lets a span whose
                // label did not fit still be matched to a name, which is what
                // the legend under the lane is for.
                drawRect(
                    color = colors.primary.copy(alpha = codecAlpha(codecOrder.indexOf(s.value))),
                    topLeft = Offset(left, 0f),
                    size = Size(width, size.height),
                )
                // A change is the thing worth seeing; the first span has no
                // change behind it, so it gets no divider.
                if (index > 0) {
                    drawLine(
                        color = colors.primary,
                        start = Offset(left, 0f),
                        end = Offset(left, size.height),
                        strokeWidth = 2f,
                    )
                }
                labelInside(measurer, s.value.displayName, left, width, colors.onSurface)
            }
            if (codecs.isEmpty()) drawBaseline(colors.outline)
        }

        // A short span cannot hold its own label, and on a two-hour axis a codec
        // that held for a minute is a sliver. Without this the name was simply
        // gone with nowhere to look it up.
        if (codecOrder.isNotEmpty()) CodecLegend(codecOrder, colors.primary)

        Lane("Sample rate", 30.dp) {
            drawGaps(coverage, fromMs, toMs, colors.outline, xOf)
            if (rates.isEmpty()) {
                drawBaseline(colors.outline)
            } else {
                drawRateSteps(rates, colors.primary, xOf)
                rates.forEachIndexed { index, s ->
                    if (index == 0 || s.value != rates[index - 1].value) {
                        val left = xOf(s.fromMs)
                        labelInside(
                            measurer,
                            "%.1fk".format(s.value / 1000f),
                            left,
                            barWidth(left, xOf(s.toMs)),
                            colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Only when a source really provides it. Reserving a lane for a value
        // this build cannot read is exactly the mistake this screen came from.
        if (hasRssi) {
            Lane("Signal", 44.dp) {
                drawGaps(coverage, fromMs, toMs, colors.outline, xOf)
                drawRssiTrace(samples, colors.primary, xOf)
            }
        }

        Lane("Events", 24.dp) {
            drawBaseline(colors.outline)
            events.forEach { event ->
                val loud = event.type == MonitorEventType.TAKEOVER ||
                    event.type == MonitorEventType.INTERRUPTION ||
                    event.type == MonitorEventType.ACL_DISCONNECTED
                val x = xOf(event.timestampMs)
                drawLine(
                    color = if (loud) colors.error else colors.secondary,
                    start = Offset(x, if (loud) 0f else size.height * 0.35f),
                    end = Offset(x, size.height),
                    strokeWidth = if (loud) 2.5f else 1.5f,
                )
            }
        }

        // Only when there is grey on screen to explain. The full account of the
        // lanes and the window lives in the panel's explainer; this one line
        // stays because grey is the one thing on the chart that means the
        // opposite of what it looks like.
        if (hasGaps) {
            Text(
                "Grey means nothing was recorded then.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.outline,
            )
        }
    }
}

/** One labelled lane on the shared time axis. */
@Composable
private fun Lane(
    label: String,
    height: Dp,
    draw: DrawScope.() -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.width(LaneLabelWidth),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(Modifier.fillMaxWidth().height(height)) { draw() }
    }
}

/** Lane labels and anything aligned to the drawing area share this gutter. */
private val LaneLabelWidth = 78.dp

/**
 * Names every codec tint in the lane above, indented to sit under the drawing.
 *
 * Flowing rather than a single row: four codecs with names as long as
 * "aptX Adaptive" do not fit one phone width, and a legend that runs off the
 * edge is the same failure as a label that does not fit its bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodecLegend(codecs: List<CodecFamily>, accent: Color) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(start = LaneLabelWidth),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        codecs.forEachIndexed { index, codec ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The swatch carries exactly the tint the lane is painted with,
                // so the two can be compared by eye. A brighter "legible"
                // swatch would name a colour that is not on the chart.
                Box(
                    Modifier
                        .size(10.dp)
                        .background(accent.copy(alpha = codecAlpha(index)))
                        .border(1.dp, accent.copy(alpha = 0.45f)),
                )
                Text(
                    codec.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The lane tint for the n-th codec in the window.
 *
 * One hue stepped in opacity rather than a second colour: the panel owns a
 * single accent, and a rainbow of hues in it would read as a different kind of
 * chart. Four steps is more than any two-hour window has ever needed; beyond
 * that they repeat, and the legend still names every one in order.
 */
private fun codecAlpha(index: Int): Float =
    0.16f + 0.16f * (index.coerceAtLeast(0) % 4)

/**
 * A span of a single sample is zero wide. Give every bar a visible minimum so
 * a codec that held for one sample does not silently vanish from the lane.
 */
private fun barWidth(left: Float, right: Float): Float = (right - left).coerceAtLeast(2f)

private fun DrawScope.drawBaseline(color: Color) {
    drawLine(
        color = color.copy(alpha = 0.35f),
        start = Offset(0f, size.height / 2f),
        end = Offset(size.width, size.height / 2f),
        strokeWidth = 1f,
    )
}

/**
 * Whether [drawGaps] will actually paint anything.
 *
 * Same walk as the drawing, so the sentence about grey can only appear when
 * there is grey — a legend for a colour that is not on screen is a puzzle.
 */
private fun hasGaps(
    coverage: List<TimelineSpan<Unit>>,
    fromMs: Long,
    toMs: Long,
): Boolean {
    if (coverage.isEmpty()) return false
    var cursor = fromMs
    coverage.forEach { span ->
        if (span.fromMs > cursor) return true
        cursor = maxOf(cursor, span.toMs)
    }
    return cursor < toMs
}

/** Greys out everything the coverage spans do not cover. */
private fun DrawScope.drawGaps(
    coverage: List<TimelineSpan<Unit>>,
    fromMs: Long,
    toMs: Long,
    color: Color,
    xOf: DrawScope.(Long) -> Float,
) {
    if (coverage.isEmpty()) return
    val grey = color.copy(alpha = 0.12f)
    var cursor = fromMs
    coverage.forEach { span ->
        if (span.fromMs > cursor) {
            val left = xOf(cursor)
            drawRect(grey, Offset(left, 0f), Size(xOf(span.fromMs) - left, size.height))
        }
        cursor = maxOf(cursor, span.toMs)
    }
    if (cursor < toMs) {
        val left = xOf(cursor)
        drawRect(grey, Offset(left, 0f), Size(xOf(toMs) - left, size.height))
    }
}

/** Step trace: a sample rate holds until it changes, so it must not slope. */
private fun DrawScope.drawRateSteps(
    rates: List<TimelineSpan<Int>>,
    color: Color,
    xOf: DrawScope.(Long) -> Float,
) {
    val values = rates.map { it.value }
    val lo = (values.minOrNull() ?: return).toFloat()
    val hi = (values.maxOrNull() ?: return).toFloat()
    // A constant rate belongs in the middle, not pinned to an edge by a
    // degenerate range.
    fun y(v: Int): Float =
        if (hi <= lo) size.height / 2f
        else size.height - ((v - lo) / (hi - lo)) * size.height * 0.8f - size.height * 0.1f

    val path = Path()
    var started = false
    rates.forEach { span ->
        val left = xOf(span.fromMs)
        val right = xOf(span.toMs)
        val yv = y(span.value)
        if (!started) {
            path.moveTo(left, yv)
            started = true
        } else {
            path.lineTo(left, yv)
        }
        path.lineTo(right, yv)
    }
    drawPath(path, color, style = Stroke(width = 2f))
}

/**
 * RSSI trace, kept for the day a privileged source provides it. Samples
 * without a value break the line rather than being interpolated into a number
 * nobody measured.
 */
private fun DrawScope.drawRssiTrace(
    samples: List<LinkQualitySample>,
    color: Color,
    xOf: DrawScope.(Long) -> Float,
) {
    val withRssi = samples.filter { it.rssiDbm != null }.sortedBy { it.timestampMs }
    if (withRssi.size < 2) return

    val minDbm = -100f
    val maxDbm = -30f
    fun y(dbm: Int): Float {
        val clamped = dbm.toFloat().coerceIn(minDbm, maxDbm)
        return size.height - ((clamped - minDbm) / (maxDbm - minDbm)) * size.height
    }

    val path = Path()
    var started = false
    var previousMs = withRssi.first().timestampMs
    withRssi.forEach { sample ->
        val point = Offset(xOf(sample.timestampMs), y(sample.rssiDbm!!))
        if (!started || sample.timestampMs - previousMs > TIMELINE_GAP_MS) {
            path.moveTo(point.x, point.y)
            started = true
        } else {
            path.lineTo(point.x, point.y)
        }
        previousMs = sample.timestampMs
    }
    drawPath(path, color, style = Stroke(width = 2f))
}

/** Draws [text] inside a bar, or nothing at all when it would not fit. */
private fun DrawScope.labelInside(
    measurer: TextMeasurer,
    text: String,
    left: Float,
    width: Float,
    color: Color,
) {
    val layout = measurer.measure(text, TextStyle(fontSize = 9.sp, color = color))
    val padding = 6f
    if (layout.size.width + padding * 2 > width) return
    drawText(
        layout,
        topLeft = Offset(left + padding, (size.height - layout.size.height) / 2f),
    )
}
