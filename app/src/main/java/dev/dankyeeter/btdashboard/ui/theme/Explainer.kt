package dev.dankyeeter.btdashboard.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A control label with an explanation the user can pull up.
 *
 * Deliberately tap-to-reveal rather than always-on prose: a screen where every
 * switch carries a paragraph is unreadable, but a switch labelled only
 * "Output limiter" tells a listener nothing about whether to touch it. The
 * question mark is the compromise — silent until asked, then a plain answer.
 *
 * A hover tooltip would be the desktop solution; on a phone there is no hover,
 * so this expands inline and stays open until dismissed.
 */
@Composable
fun ExplainedRow(
    label: String,
    explanation: String,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    var open by rememberSaveable(label) { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            control()
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
            IconButton(onClick = { open = !open }) {
                Icon(
                    Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = if (open) "Hide explanation" else "What is $label?",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(open) {
            Text(
                explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp, end = 8.dp),
            )
        }
    }
}

/**
 * The same disclosure for a control the [ExplainedRow] layout cannot hold.
 *
 * [ExplainedRow] owns its arrangement: control, label, question mark, all on one
 * line. A slider needs the icon beside its track and a heading of its own above
 * it, so this keeps the part that is worth sharing — the open/closed state and
 * the way the explanation is styled when it appears — and hands the question
 * mark back for the caller to place. One implementation instead of a second
 * inline copy that would drift out of step with this one.
 */
@Composable
fun ExplainedBlock(
    label: String,
    explanation: String,
    modifier: Modifier = Modifier,
    content: @Composable (toggle: @Composable () -> Unit) -> Unit,
) {
    var open by rememberSaveable(label) { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content {
            IconButton(onClick = { open = !open }) {
                Icon(
                    Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = if (open) "Hide explanation" else "What is $label?",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(open) {
            Text(
                explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Section heading with the same tap-to-reveal explanation. */
@Composable
fun ExplainedHeader(
    label: String,
    explanation: String,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable(label) { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PanelHeader(
            label,
            trailing = {
                IconButton(onClick = { open = !open }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = if (open) "Hide explanation" else "What is $label?",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        AnimatedVisibility(open) {
            Text(
                explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
