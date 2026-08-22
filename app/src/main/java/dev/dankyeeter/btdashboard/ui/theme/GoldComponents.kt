package dev.dankyeeter.btdashboard.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Card that wears a brushed-metal edge under the Edgy theme and is an ordinary
 * Material card everywhere else.
 *
 * The gradient border is what carries the "metal" reading: it is brightest
 * along one diagonal band and dark at both ends, so the edge catches light the
 * way a bevel does. A flat gold outline just looks like a yellow line.
 */
@Composable
fun GoldCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val metallic = LocalGoldAccents.current
    Card(
        modifier = modifier.fillMaxWidth(),
        border = if (metallic) LocalMetalPalette.current.border else null,
        colors = CardDefaults.cardColors(),
    ) {
        content()
    }
}

/**
 * Section heading. Under Edgy the glyphs are filled with the vertical sheen,
 * which reads as engraved metal; otherwise it is a plain title.
 */
@Composable
fun GoldTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    if (LocalGoldAccents.current) {
        // `TextStyle.brush` paints the glyphs themselves, so the sheen follows
        // the letterforms instead of sitting behind them as a background.
        Text(text, modifier = modifier, style = style.copy(brush = LocalMetalPalette.current.vertical))
    } else {
        Text(text, modifier = modifier, style = style)
    }
}

/** Hairline metal rule. Renders nothing outside the Edgy theme. */
@Composable
fun GoldRule(modifier: Modifier = Modifier) {
    if (!LocalGoldAccents.current) return
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalMetalPalette.current.horizontal),
    )
}

/**
 * Primary button. Under Edgy the fill is the vertical ingot gradient rather
 * than `colorScheme.primary`, because a flat fill of the same hue is exactly
 * what makes gold look like yellow paint.
 *
 * The gradient lives on the container, so the button keeps all of Material's
 * shape, ripple and state behaviour and only its paint changes.
 */
@Composable
fun GoldButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    if (!LocalGoldAccents.current) {
        Button(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
        return
    }
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        // Transparent container, gradient painted underneath it.
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.Black,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = LocalMetalPalette.current.deep,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(
            Modifier
                .background(
                    if (enabled) LocalMetalPalette.current.fill
                    else SolidColor(LocalMetalPalette.current.shadow),
                    ButtonShape,
                )
                .padding(ButtonDefaults.ContentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

/** Outlined button with a brushed-metal edge. */
@Composable
fun GoldOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val metallic = LocalGoldAccents.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        border = if (metallic && enabled) LocalMetalPalette.current.border else null,
        content = content,
    )
}

private val ButtonShape = RoundedCornerShape(20.dp)
