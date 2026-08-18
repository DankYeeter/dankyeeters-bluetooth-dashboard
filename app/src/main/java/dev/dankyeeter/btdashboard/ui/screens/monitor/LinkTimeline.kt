package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType

/**
 * The link timeline, drawn by hand on a Compose Canvas — no chart library, per
 * PLAN.md. Two lanes share one time axis:
 *
 *  - an RSSI trace (the only continuous value we get without privileged APIs),
 *  - an event lane with a tick per event, colour-coded, where takeovers and
 *    interruptions get a full-height marker because those are the moments
 *    Daniel is actually trying to correlate with what he heard.
 *
 * Everything is defensive about empty data: no samples means an honest empty
 * state rather than an axis with invented bounds.
 */
@Composable
fun LinkTimeline(
    samples: List<LinkQualitySample>,
    events: List<MonitorEvent>,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outline
    val trace = MaterialTheme.colorScheme.primary
    val takeover = MaterialTheme.colorScheme.error
    val normal = MaterialTheme.colorScheme.secondary

    if (samples.isEmpty() && events.isEmpty()) {
        Box(
            modifier.fillMaxWidth().height(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Nothing recorded yet — events appear as soon as something connects or plays.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    val fromMs = minOf(
        samples.minOfOrNull { it.timestampMs } ?: Long.MAX_VALUE,
        events.minOfOrNull { it.timestampMs } ?: Long.MAX_VALUE,
    )
    val toMs = maxOf(
        samples.maxOfOrNull { it.timestampMs } ?: Long.MIN_VALUE,
        events.maxOfOrNull { it.timestampMs } ?: Long.MIN_VALUE,
    )
    val span = (toMs - fromMs).coerceAtLeast(1L)

    Canvas(modifier.fillMaxWidth().height(180.dp)) {
        val traceHeight = size.height * 0.68f
        val laneY = size.height - 16f

        fun x(timestampMs: Long): Float =
            ((timestampMs - fromMs).toFloat() / span) * size.width

        drawLine(
            color = outline,
            start = Offset(0f, traceHeight),
            end = Offset(size.width, traceHeight),
            strokeWidth = 1f,
        )
        drawRssiTrace(samples, traceHeight, trace, ::x)

        events.forEach { event ->
            val isLoud = event.type == MonitorEventType.TAKEOVER ||
                event.type == MonitorEventType.INTERRUPTION ||
                event.type == MonitorEventType.ACL_DISCONNECTED
            val ex = x(event.timestampMs)
            drawLine(
                color = if (isLoud) takeover else normal,
                start = Offset(ex, if (isLoud) 0f else traceHeight),
                end = Offset(ex, laneY),
                strokeWidth = if (isLoud) 2.5f else 1.5f,
            )
        }
    }
}

/**
 * RSSI trace. Samples without an RSSI value (no dumpsys access) simply break
 * the line instead of being interpolated into a number nobody measured.
 */
private fun DrawScope.drawRssiTrace(
    samples: List<LinkQualitySample>,
    traceHeight: Float,
    color: Color,
    x: (Long) -> Float,
) {
    val withRssi = samples.filter { it.rssiDbm != null }
    if (withRssi.size < 2) return

    // Fixed, meaningful bounds: -100 dBm is unusable, -30 dBm is next to the phone.
    val minDbm = -100f
    val maxDbm = -30f
    fun y(dbm: Int): Float {
        val clamped = dbm.toFloat().coerceIn(minDbm, maxDbm)
        return traceHeight - ((clamped - minDbm) / (maxDbm - minDbm)) * traceHeight
    }

    val path = Path()
    var started = false
    var previousTime = withRssi.first().timestampMs
    withRssi.forEach { sample ->
        val gap = sample.timestampMs - previousTime
        val point = Offset(x(sample.timestampMs), y(sample.rssiDbm!!))
        // A gap longer than five minutes means the sampler was idle: don't
        // draw a line across time nothing was measured in.
        if (!started || gap > 5 * 60_000L) {
            path.moveTo(point.x, point.y)
            started = true
        } else {
            path.lineTo(point.x, point.y)
        }
        previousTime = sample.timestampMs
    }
    drawPath(path, color = color, style = Stroke(width = 2f))
}
