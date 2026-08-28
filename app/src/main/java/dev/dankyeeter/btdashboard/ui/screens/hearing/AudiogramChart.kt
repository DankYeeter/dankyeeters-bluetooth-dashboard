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
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.hearing.Audiogram
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import dev.dankyeeter.btdashboard.hearing.ClinicalAudiogram
import dev.dankyeeter.btdashboard.hearing.ThresholdPoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10

/**
 * Audiogram chart drawn by hand on a Compose [Canvas] — no chart library, per
 * PLAN.md. Logarithmic frequency axis; the level axis shows **deviation from
 * this person's own average sensitivity**, with zero in the middle.
 *
 * ## Why a relative axis
 *
 * The raw numbers are the app's internal attenuation in dBFS, and the output
 * is uncalibrated — where absolute "normal hearing" sits depends on how loud
 * this particular headphone plays at a given digital level, which the app
 * does not know. The absolute view therefore painted every curve into one
 * corner of a 90 dB canvas and made a 2 dB difference — a real, audible
 * difference — invisible.
 *
 * What the app *does* know honestly is the shape: how each frequency compares
 * to the listener's own average. That is exactly what the compensation works
 * from, so it is what the chart shows. Zero is the average of the converged
 * thresholds; above zero this ear is more sensitive than its own average,
 * below it less. Both directions exist by construction — an average is not a
 * floor — which also matches how audiograms are read: the norm at zero,
 * better hearing in the minus... plotted up here, because "more sensitive"
 * belongs visually above the line.
 *
 * The axis range adapts to the data so small deviations stay visible instead
 * of drowning in a fixed scale sized for hearing loss.
 *
 * Individual runs are thin translucent lines, the active median is thick, and
 * points that did not converge are hollow so a floor/ceiling artefact is never
 * mistaken for a real measurement.
 */
@Composable
fun AudiogramChart(
    runs: List<AudiogramRun>,
    active: Audiogram?,
    modifier: Modifier = Modifier,
    showLeft: Boolean = true,
    showRight: Boolean = true,
    clinical: ClinicalAudiogram? = null,
    /**
     * The ISO 7029 age-typical curve, already in this chart's deviation space,
     * or empty. One curve rather than two: the model is per person, not per ear.
     */
    ageReference: List<Pair<Int, Double>> = emptyList(),
) {
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val leftColor = colors.primary
    val rightColor = colors.tertiary
    val axisColor = colors.outline
    val labelColor = colors.onSurfaceVariant

    val points = visiblePoints(active, runs, showLeft, showRight)
    val reference = referenceLevel(points)
    // Already in the chart's deviation space, so nothing below has to know that
    // these came off a different scale. See [ClinicalAudiogram.deviationCurve].
    val clinicalLeft = if (showLeft) clinical?.deviationCurve(Ear.LEFT).orEmpty() else emptyList()
    val clinicalRight = if (showRight) clinical?.deviationCurve(Ear.RIGHT).orEmpty() else emptyList()

    Canvas(modifier = modifier.fillMaxWidth().height(260.dp)) {
        val plot = PlotArea(
            left = 44.dp.toPx(),
            top = 12.dp.toPx(),
            right = size.width - 10.dp.toPx(),
            bottom = size.height - 24.dp.toPx(),
            // The clinical curve votes on the range too: a scale sized for the
            // measured points alone would flatten the overlay against the top
            // or bottom edge, which is where the comparison is being made.
            halfRangeDb = halfRangeFor(points, reference, clinicalLeft + clinicalRight + ageReference),
        )
        drawGrid(plot, axisColor, labelColor, measurer)
        drawZeroLine(plot, labelColor, measurer)

        // Furthest back and faintest of the three: this one is not a
        // measurement of anybody, so it must never compete for attention with a
        // curve that is. Drawn in the outline colour rather than in either ear's
        // colour, because it belongs to neither ear.
        drawDeviationCurve(plot, ageReference, axisColor, 2.dp.toPx(), AGE_DASH, alpha = 0.7f)

        // Drawn before the measured curves so those stay on top: this is the
        // reference being compared against, not the subject of the chart.
        drawDeviationCurve(plot, clinicalLeft, leftColor, 2.dp.toPx())
        drawDeviationCurve(plot, clinicalRight, rightColor, 2.dp.toPx())

        if (reference == null) return@Canvas
        runs.forEach { run ->
            if (showLeft) drawCurve(plot, reference, run.left, leftColor.copy(alpha = 0.25f), 1.5.dp.toPx(), false)
            if (showRight) drawCurve(plot, reference, run.right, rightColor.copy(alpha = 0.25f), 1.5.dp.toPx(), false)
        }
        active?.let {
            if (showLeft) drawCurve(plot, reference, it.left, leftColor, 3.dp.toPx(), true)
            if (showRight) drawCurve(plot, reference, it.right, rightColor, 3.dp.toPx(), true)
        }
    }
}

@Composable
fun AudiogramLegend(
    modifier: Modifier = Modifier,
    showClinical: Boolean = false,
    showAgeReference: Boolean = false,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendEntry(MaterialTheme.colorScheme.primary, "Left")
        LegendEntry(MaterialTheme.colorScheme.tertiary, "Right")
        // Only when a curve is actually drawn: a legend entry for an absent
        // line is a promise the chart does not keep.
        if (showClinical) LegendEntry(MaterialTheme.colorScheme.onSurfaceVariant, "clinic", dotted = true)
        // Named "typical for your age" rather than "ISO 7029": the standard's
        // number tells nobody what the line is, and the one thing that must
        // come across is that it is other people's hearing, not yours.
        if (showAgeReference) {
            LegendEntry(MaterialTheme.colorScheme.outline, "typical for your age", dotted = true)
        }
        Text(
            if (showClinical || showAgeReference) {
                "0 = each curve's own average · above = more sensitive · shapes only, not levels"
            } else {
                "0 = your average · above = more sensitive · hollow = not measurable"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendEntry(color: Color, label: String, dotted: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // A dotted swatch rather than a filled dot, so the key looks like the
        // stroke it stands for.
        Canvas(Modifier.size(width = if (dotted) 18.dp else 10.dp, height = 10.dp)) {
            if (dotted) {
                drawLine(
                    color = color,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = CLINICAL_DASH,
                )
            } else {
                drawCircle(color, radius = size.height / 2f, center = Offset(size.height / 2f, size.height / 2f))
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private fun visiblePoints(
    active: Audiogram?,
    runs: List<AudiogramRun>,
    showLeft: Boolean,
    showRight: Boolean,
): List<ThresholdPoint> = buildList {
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

/**
 * Zero of the deviation axis: the median converged threshold on screen.
 *
 * The median, not the best — an average is a level someone can sit on either
 * side of, which is what makes "plus" possible at all. Hollow points are
 * excluded: a threshold that hit the floor says nothing about hearing and
 * would drag the reference toward wherever the floor happens to be.
 */
private fun referenceLevel(points: List<ThresholdPoint>): Double? {
    val converged = points.filter { it.converged }.map { it.thresholdDb }.sorted()
    if (converged.isEmpty()) return null
    val mid = converged.size / 2
    return if (converged.size % 2 == 1) converged[mid] else (converged[mid - 1] + converged[mid]) / 2.0
}

/**
 * Half of the axis range, adapted to the data.
 *
 * Small deviations get a tight scale — a person 2 dB off their average should
 * see those 2 dB, not a flat line on a canvas sized for a 40 dB loss. The
 * floor of ±6 dB keeps single-decibel noise from filling the whole chart, and
 * only converged points vote: hollow ones sit at the test's own limits and
 * would stretch the scale to exactly the artefact they represent.
 */
private fun halfRangeFor(
    points: List<ThresholdPoint>,
    reference: Double?,
    clinicalDeviations: List<Pair<Int, Double>> = emptyList(),
): Double {
    val clinicalMax = clinicalDeviations.maxOfOrNull { abs(it.second) } ?: 0.0
    if (reference == null) {
        // No measured curve yet, but the clinical overlay can stand alone — a
        // fixed 12 dB range would hide a 25 dB clinical slope entirely.
        return if (clinicalMax == 0.0) 12.0 else (ceil(clinicalMax / 3.0) * 3.0).coerceIn(6.0, 48.0)
    }
    val maxAbs = points.filter { it.converged }.maxOfOrNull { abs(reference - it.thresholdDb) } ?: 0.0
    return (ceil(maxOf(maxAbs, clinicalMax) / 3.0) * 3.0).coerceIn(6.0, 48.0)
}

private data class PlotArea(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val halfRangeDb: Double,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Frequency axis bounds, a little wider than the tested range so nothing clips. */
private const val MIN_HZ = 200.0
private const val MAX_HZ = 10_000.0

private val GRID_FREQUENCIES = listOf(250, 500, 1000, 2000, 4000, 8000)

private fun PlotArea.xFor(frequencyHz: Int): Float {
    val fraction = (log10(frequencyHz.toDouble()) - log10(MIN_HZ)) / (log10(MAX_HZ) - log10(MIN_HZ))
    return left + (fraction.coerceIn(0.0, 1.0) * width).toFloat()
}

/** Deviation in dB (positive = more sensitive) to a y position, zero centered. */
private fun PlotArea.yForDeviation(deviationDb: Double): Float {
    val fraction = (halfRangeDb - deviationDb) / (2 * halfRangeDb)
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

    // Symmetric levels around zero, spaced so there are about three lines per
    // half — enough to read values off, few enough not to turn into graph
    // paper when the range tightens.
    val step = (plot.halfRangeDb / 3.0).let { raw ->
        listOf(1.0, 2.0, 3.0, 5.0, 10.0, 20.0).firstOrNull { it >= raw } ?: 20.0
    }
    var level = step
    val levels = buildList {
        while (level <= plot.halfRangeDb + 1e-9) {
            add(level); add(-level); level += step
        }
    }
    levels.forEach { dev ->
        val y = plot.yForDeviation(dev)
        drawLine(
            color = axisColor.copy(alpha = 0.25f),
            start = Offset(plot.left, y),
            end = Offset(plot.right, y),
            strokeWidth = hairline,
        )
        val text = (if (dev > 0) "+" else "−") + "${abs(dev).toInt()} dB"
        val layout = measurer.measure(text, labelStyle)
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

/** The zero line: this listener's own average, dashed so curves stay legible over it. */
private fun DrawScope.drawZeroLine(plot: PlotArea, labelColor: Color, measurer: TextMeasurer) {
    val y = plot.yForDeviation(0.0)
    drawLine(
        color = labelColor.copy(alpha = 0.8f),
        start = Offset(plot.left, y),
        end = Offset(plot.right, y),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
    )
    val layout = measurer.measure("0 dB", TextStyle(fontSize = 9.sp, color = labelColor))
    drawText(layout, topLeft = Offset(0f, y - layout.size.height / 2f))
}

/**
 * The dot pattern the clinical overlay is drawn with.
 *
 * Tight round dots rather than the long dashes used for the grid and the zero
 * line: those two are furniture, this is data, and at a glance a dashed data
 * curve reads as another piece of furniture. It also has to survive being
 * crossed by a 3 dp measured curve without either line becoming ambiguous.
 */
private val CLINICAL_DASH = PathEffect.dashPathEffect(floatArrayOf(3f, 7f))

/**
 * The dash the age reference is drawn with: long strokes, wide gaps.
 *
 * Deliberately unlike [CLINICAL_DASH]. Both are references rather than
 * measurements, but they are references of completely different standing — one
 * is a calibrated reading of these ears, the other a statistic about a
 * population — and two curves that look alike would invite reading them as
 * equally personal. Long dashes also read as "smooth model" next to the tight
 * dots of a transcribed form.
 */
private val AGE_DASH = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))

/**
 * The clinical curve, already converted into the chart's deviation space.
 *
 * Dotted and without markers, because it is not a measurement this app made and
 * must not be mistaken for one — the solid markered curves are the app's own
 * points, the dots are somebody else's. There is deliberately no absolute
 * alignment between the two: the offset between calibrated dB HL and this app's
 * dBFS thresholds is unknown and unknowable without a measurement microphone in
 * an artificial ear, so both curves are drawn against their own median and only
 * their *shapes* are being compared. Two curves that sit on top of each other
 * here mean the shapes agree, never that the levels do.
 */
private fun DrawScope.drawDeviationCurve(
    plot: PlotArea,
    deviations: List<Pair<Int, Double>>,
    color: Color,
    strokeWidth: Float,
    dash: PathEffect = CLINICAL_DASH,
    alpha: Float = 0.85f,
) {
    if (deviations.size < 2) return
    val path = Path()
    deviations.sortedBy { it.first }.forEachIndexed { index, (hz, deviation) ->
        val x = plot.xFor(hz)
        val y = plot.yForDeviation(deviation)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path,
        color.copy(alpha = alpha),
        style = Stroke(width = strokeWidth, pathEffect = dash),
    )
}

private fun DrawScope.drawCurve(
    plot: PlotArea,
    referenceDb: Double,
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
        val y = plot.yForDeviation(referenceDb - point.thresholdDb)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = strokeWidth))

    if (!withMarkers) return
    val radius = strokeWidth * 1.6f
    sorted.forEach { point ->
        val center = Offset(plot.xFor(point.frequencyHz), plot.yForDeviation(referenceDb - point.thresholdDb))
        if (point.converged) {
            drawCircle(color, radius, center)
        } else {
            // Hollow marker: the search hit the level floor or ceiling.
            drawCircle(color, radius, center, style = Stroke(width = strokeWidth * 0.6f))
        }
    }
}
