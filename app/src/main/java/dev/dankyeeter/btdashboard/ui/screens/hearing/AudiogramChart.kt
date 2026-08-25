package dev.dankyeeter.btdashboard.ui.screens.hearing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dankyeeter.btdashboard.hearing.Audiogram
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import dev.dankyeeter.btdashboard.hearing.ThresholdPoint
import kotlin.math.log10

/**
 * Audiogram chart drawn by hand on a Compose [Canvas] — no chart library, per
 * PLAN.md. Logarithmic frequency axis, level axis in the app's internal dBFS
 * scale: the top of the chart is the quietest tone the app can produce, so
 * higher curves mean more sensitive hearing.
 *
 * Individual runs are drawn as thin translucent lines and the active median
 * curve as a thick one, which is exactly the overlay view the multi-run
 * workflow needs. Points that did not converge are drawn hollow so a
 * floor/ceiling artefact is never mistaken for a real measurement.
 *
 * ## The reference line
 *
 * A dashed line marks what even hearing would look like: the same threshold at
 * every frequency. It is placed at the best frequency this person actually
 * measured, and the gap below it is how much each other frequency falls short.
 *
 * It is deliberately *not* a clinical 0 dB HL line. This scale is the app's
 * own attenuation in dBFS and the output is uncalibrated - where absolute
 * normal hearing sits depends on how loud this particular headphone plays at a
 * given digital level, which the app does not know. Drawing an absolute line
 * anyway would be a medical-looking claim with nothing behind it. The relative
 * shape, on the other hand, is exactly what the compensation works from.
 */
@Composable
fun AudiogramChart(
    runs: List<AudiogramRun>,
    active: Audiogram?,
    modifier: Modifier = Modifier,
    showLeft: Boolean = true,
    showRight: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val leftColor = colors.primary
    val rightColor = colors.tertiary
    val axisColor = colors.outline
    val labelColor = colors.onSurfaceVariant

    Canvas(modifier = modifier.fillMaxWidth().height(260.dp)) {
        val plot = PlotArea(
            left = 44.dp.toPx(),
            top = 12.dp.toPx(),
            right = size.width - 10.dp.toPx(),
            bottom = size.height - 24.dp.toPx(),
        )
        drawGrid(plot, axisColor, labelColor, measurer)

        runs.forEach { run ->
            if (showLeft) drawCurve(plot, run.left, leftColor.copy(alpha = 0.25f), 1.5.dp.toPx(), false)
            if (showRight) drawCurve(plot, run.right, rightColor.copy(alpha = 0.25f), 1.5.dp.toPx(), false)
        }
        active?.let {
            if (showLeft) drawCurve(plot, it.left, leftColor, 3.dp.toPx(), true)
            if (showRight) drawCurve(plot, it.right, rightColor, 3.dp.toPx(), true)
        }

        // Drawn last so it sits on top of the curves it is meant to be read
        // against, and only from points that converged - a threshold that hit
        // the floor says nothing about how well someone hears.
        referenceLevel(active, runs, showLeft, showRight)?.let { level ->
            drawReferenceLine(plot, level, labelColor, measurer)
        }
    }
}

/**
 * The best converged threshold on screen, or null when nothing converged.
 *
 * "Best" is the quietest tone that was still heard, which on this axis is the
 * smallest number. Ignoring the hollow points matters: a run that hit the
 * floor everywhere - the app could not go quieter - would otherwise place the
 * line at an invented level and make the hearing look even.
 */
private fun referenceLevel(
    active: Audiogram?,
    runs: List<AudiogramRun>,
    showLeft: Boolean,
    showRight: Boolean,
): Double? {
    val points = buildList {
        if (active != null) {
            if (showLeft) addAll(active.left)
            if (showRight) addAll(active.right)
        } else {
            runs.forEach { run ->
                if (showLeft) addAll(run.left)
                if (showRight) addAll(run.right)
            }
        }
    }
    return points.filter { it.converged }.minOfOrNull { it.thresholdDb }
}

private fun DrawScope.drawReferenceLine(
    plot: PlotArea,
    levelDb: Double,
    labelColor: Color,
    measurer: TextMeasurer,
) {
    val y = plot.yFor(levelDb)
    drawLine(
        color = labelColor.copy(alpha = 0.8f),
        start = Offset(plot.left, y),
        end = Offset(plot.right, y),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
    )
    // Below the line and clamped into the plot.
    //
    // Above it collided with the top axis label as soon as the line sat near
    // the top - which is exactly where it sits for someone who hears well, so
    // the better the hearing, the more unreadable the chart became. Below the
    // line is the empty half in that case.
    val layout = measurer.measure(
        "even hearing",
        TextStyle(fontSize = 9.sp, color = labelColor),
    )
    val labelY = (y + 3.dp.toPx()).coerceAtMost(plot.bottom - layout.size.height)
    drawText(
        layout,
        topLeft = Offset(plot.right - layout.size.width, labelY),
    )
}

@Composable
fun AudiogramLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendEntry(MaterialTheme.colorScheme.primary, "Left")
        LegendEntry(MaterialTheme.colorScheme.tertiary, "Right")
        Text(
            "thin = single runs · thick = median · dashed = even hearing",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendEntry(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private data class PlotArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Frequency axis bounds, a little wider than the tested range so nothing clips. */
private const val MIN_HZ = 200.0
private const val MAX_HZ = 10_000.0

private const val TOP_DB = -90.0
private const val BOTTOM_DB = 0.0

private val GRID_FREQUENCIES = listOf(250, 500, 1000, 2000, 4000, 8000)
private val GRID_LEVELS = listOf(-90.0, -70.0, -50.0, -30.0, -10.0)

private fun PlotArea.xFor(frequencyHz: Int): Float {
    val fraction = (log10(frequencyHz.toDouble()) - log10(MIN_HZ)) / (log10(MAX_HZ) - log10(MIN_HZ))
    return left + (fraction.coerceIn(0.0, 1.0) * width).toFloat()
}

private fun PlotArea.yFor(levelDb: Double): Float {
    val fraction = (levelDb - TOP_DB) / (BOTTOM_DB - TOP_DB)
    return top + (fraction.coerceIn(0.0, 1.0) * height).toFloat()
}

private fun DrawScope.drawGrid(
    plot: PlotArea,
    axisColor: Color,
    labelColor: Color,
    measurer: TextMeasurer,
) {
    val hairline = 1.dp.toPx()
    val labelStyle = TextStyle(fontSize = 9.sp, color = labelColor)

    GRID_LEVELS.forEach { level ->
        val y = plot.yFor(level)
        drawLine(
            color = axisColor.copy(alpha = 0.35f),
            start = Offset(plot.left, y),
            end = Offset(plot.right, y),
            strokeWidth = hairline,
        )
        val layout = measurer.measure("${level.toInt()} dB", labelStyle)
        drawText(layout, topLeft = Offset(0f, y - layout.size.height / 2f))
    }

    GRID_FREQUENCIES.forEach { hz ->
        val x = plot.xFor(hz)
        drawLine(
            color = axisColor.copy(alpha = 0.2f),
            start = Offset(x, plot.top),
            end = Offset(x, plot.bottom),
            strokeWidth = hairline,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
        )
        val label = if (hz >= 1000) "${hz / 1000}k" else "$hz"
        val layout = measurer.measure(label, labelStyle)
        drawText(layout, topLeft = Offset(x - layout.size.width / 2f, plot.bottom + 4.dp.toPx()))
    }
}

private fun DrawScope.drawCurve(
    plot: PlotArea,
    points: List<ThresholdPoint>,
    color: Color,
    strokeWidth: Float,
    withMarkers: Boolean,
) {
    if (points.isEmpty()) return
    val sorted = points.sortedBy { it.frequencyHz }
    val path = Path()
    sorted.forEachIndexed { index, point ->
        val x = plot.xFor(point.frequencyHz)
        val y = plot.yFor(point.thresholdDb)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = strokeWidth))

    if (!withMarkers) return
    val radius = strokeWidth * 1.6f
    sorted.forEach { point ->
        val center = Offset(plot.xFor(point.frequencyHz), plot.yFor(point.thresholdDb))
        if (point.converged) {
            drawCircle(color, radius, center)
        } else {
            // Hollow marker: the search hit the level floor or ceiling.
            drawCircle(color, radius, center, style = Stroke(width = strokeWidth * 0.6f))
        }
    }
}
