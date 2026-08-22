package dev.dankyeeter.btdashboard.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The app's surface primitive.
 *
 * A flat filled rectangle is what made the old screens read as a settings list
 * from 2015. Three things fix that without a single image asset:
 *
 *  1. **A gradient body.** Real surfaces are not one colour. A few percent of
 *     lift at the top and a slight sink at the bottom is enough for the eye to
 *     read the panel as a plane catching light rather than a painted box.
 *  2. **A light-catch edge.** One hairline along the top, brighter than the
 *     rest of the border, is the single cheapest cue for "this has thickness".
 *  3. **Radius with room.** 24 dp corners and 20 dp padding; tight corners on
 *     a full-width card are the most dated thing in mobile UI.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    contentPadding: Int = 20,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val metallic = LocalGoldAccents.current

    // Remembered against the inputs: without this every panel rebuilds three
    // gradient objects on every recomposition, and this is the most-used
    // surface in the app.
    val body = remember(scheme.surfaceContainerHigh, scheme.surfaceContainerLow) {
        Brush.verticalGradient(
            listOf(
                scheme.surfaceContainerHigh,
                scheme.surfaceContainer,
                scheme.surfaceContainerLow,
            ),
        )
    }
    // Under Edgy the edge wears the chosen metal — the *palette*, not Gold:
    // hardcoding Gold here is exactly why a picked silver changed the buttons
    // but not the panel edges. Otherwise it follows the scheme so Light and
    // Dark keep their own identity instead of wearing a costume.
    val palette = LocalMetalPalette.current
    val edge = remember(metallic, palette, scheme.outline) {
        if (metallic) {
            Brush.verticalGradient(
                0f to palette.base.copy(alpha = 0.55f),
                0.35f to palette.deep.copy(alpha = 0.22f),
                1f to Color.Transparent,
            )
        } else {
            Brush.verticalGradient(
                0f to scheme.outline.copy(alpha = 0.40f),
                1f to scheme.outline.copy(alpha = 0.10f),
            )
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(body)
            .border(1.dp, edge, PanelShape),
    ) {
        Column(
            Modifier.padding(contentPadding.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * Panel header: a small tracked label, not a heading competing with the
 * content. The eye should land on the value, not on the word above it.
 */
@Composable
fun PanelHeader(
    label: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            style = EyebrowStyle,
            color = if (LocalGoldAccents.current) {
                LocalMetalPalette.current.base.copy(alpha = 0.85f)
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        trailing?.invoke()
    }
}

/**
 * A large value with its unit and a caption. This is the shape the app's
 * numbers should take: the figure carries the panel, the words support it.
 */
@Composable
fun Readout(
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (LocalGoldAccents.current) {
            Text(value, style = ReadoutStyle.copy(brush = LocalMetalPalette.current.vertical))
        } else {
            Text(value, style = ReadoutStyle, color = MaterialTheme.colorScheme.onSurface)
        }
        caption?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Status pill. Soft-filled rather than outlined: an outline at this size reads
 * as a disabled control, a tinted fill reads as a state.
 */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.NEUTRAL,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = when (tone) {
        PillTone.ACCENT -> if (LocalGoldAccents.current) LocalMetalPalette.current.base else scheme.primary
        PillTone.WARN -> scheme.error
        PillTone.NEUTRAL -> scheme.onSurfaceVariant
    }
    Box(
        modifier
            .clip(PillShape)
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.30f), PillShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = accent)
    }
}

enum class PillTone { NEUTRAL, ACCENT, WARN }

/** Hairline divider inside a panel. Full-strength rules chop a panel in half. */
@Composable
fun PanelDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    )
}

val PanelShape = RoundedCornerShape(24.dp)
private val PillShape = RoundedCornerShape(999.dp)
