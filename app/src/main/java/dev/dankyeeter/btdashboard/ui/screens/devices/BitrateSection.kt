package dev.dankyeeter.btdashboard.ui.screens.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
import dev.dankyeeter.btdashboard.ui.tuning.LdacQuality
import dev.dankyeeter.btdashboard.ui.tuning.LdacTuningState

/**
 * Whether the bitrate chips belong on this card at all.
 *
 * Pulled out of the Compose code because it is the one rule that decides
 * between offering a control that cannot work and hiding one the user came for.
 * [UNREADABLE_CODEC] is the reason it is an enum rather than a boolean: a
 * connected headphone whose codec Android will not name is neither "this link
 * has a quality knob" nor "it has none", and dropping the section there would
 * make the control vanish exactly on the phones where the privileged helper is
 * not running — which is the state it exists to explain.
 */
internal enum class BitrateSectionState {
    /** The link runs, or the profile asks for, a codec with a quality knob. */
    SHOWN,

    /** Connected, but the codec could not be read, so the chips say so. */
    UNREADABLE_CODEC,

    /** A codec with no quality to pin, and nothing stored. Nothing to show. */
    HIDDEN,
}

/**
 * @param negotiated the `CodecFamily` name the link is running, or null when it
 *   could not be read.
 * @param stored the codec the profile asks for on connect, or null for none.
 */
internal fun bitrateSectionState(
    negotiated: String?,
    stored: String?,
    deviceConnected: Boolean,
): BitrateSectionState = when {
    LdacQuality.supportsQualityPinning(negotiated) -> BitrateSectionState.SHOWN
    // A stored wish keeps the section even when the link is elsewhere or down:
    // it is what the next connect will ask for, and it has to be withdrawable.
    LdacQuality.supportsQualityPinning(stored) -> BitrateSectionState.SHOWN
    deviceConnected && negotiated == null -> BitrateSectionState.UNREADABLE_CODEC
    else -> BitrateSectionState.HIDDEN
}

/**
 * The bitrate chips, on the card of the headphone they belong to.
 *
 * ## Why they are here and not only on the Monitoring tab
 *
 * The Monitoring panel could already move LDAC's playback quality, and only for
 * as long as the link stayed up: the stack renegotiates on every connect, so the
 * choice was gone by the next time the headphones were put on. Here the same
 * four chips write the choice into the device's profile, which is the thing that
 * gets replayed on connect — so this is where "pin 990" becomes a setting rather
 * than a gesture.
 *
 * Both places light the same chip, because both ask [LdacQuality.selected] the
 * same question about the same stored value.
 *
 * ## What this card does not do
 *
 * It does not read the link. One live pass is three `dumpsys` calls, and this is
 * the tab the app opens on — paying that on the start screen to print a rate
 * would be the wrong trade. So the state line names the stored choice and the
 * outcome of the last change, and says where the measured rate is. [measuredKbps]
 * exists for a caller that already has a live reading; on this tab it is null.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BitrateSection(
    state: BitrateSectionState,
    storedQuality: Long,
    tuning: LdacTuningState,
    onPin: (Long) -> Unit,
    onDismissMessage: () -> Unit,
    enabled: Boolean = true,
    /** The negotiated rate, so the chips carry the right ladder. Null → 48 kHz. */
    sampleRateHz: Int? = null,
    /** A live reading, when the caller has one. Null on the Bluetooth tab. */
    measuredKbps: Int? = null,
) {
    if (state == BitrateSectionState.HIDDEN) return

    val selected = LdacQuality.selected(storedQuality)

    ExplainedHeader("Bitrate", BITRATE_EXPLANATION)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LdacQuality.pinnable.forEach { quality ->
            FilterChip(
                selected = selected == quality,
                // Never two requests in flight: each renegotiates the codec, and
                // the second would race the first's read-back.
                enabled = enabled && !tuning.busy,
                onClick = { onPin(quality) },
                label = { Text(LdacQuality.chipLabel(quality, sampleRateHz)) },
            )
        }
    }

    Text(
        stateLine(state, storedQuality, measuredKbps, sampleRateHz),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )

    Text(
        "Changing this renegotiates the codec, so audio cuts out for a moment.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    tuning.message?.let { message ->
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (tuning.messageIsError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        TextButton(onClick = onDismissMessage) { Text("OK") }
    }
}

/**
 * The one line under the chips, in the four states it can honestly be in.
 *
 * A measurement is printed as one and labelled, exactly as the live panel does.
 * Without one the line says what is *stored* — never what the link is doing —
 * and names the tab that can answer that instead, so the absence is a signpost
 * rather than a silence.
 */
internal fun stateLine(
    state: BitrateSectionState,
    storedQuality: Long,
    measuredKbps: Int?,
    sampleRateHz: Int? = null,
): String = when {
    state == BitrateSectionState.UNREADABLE_CODEC ->
        "This headphone is connected and Android will not say which codec it negotiated, " +
            "so a rate can be asked for but not checked here."

    measuredKbps != null -> "$measuredKbps kbps measured right now."

    storedQuality != LdacQuality.NONE ->
        "Stored: ${LdacQuality.chipLabel(storedQuality, sampleRateHz)}, asked for again on " +
            "every connect. Not read live here — Monitoring shows what the link is sending."

    else ->
        "Nothing stored, so the link runs adaptive — which is what ABR means. Not read " +
            "live here — Monitoring shows what the link is sending."
}

private const val BITRATE_EXPLANATION =
    "LDAC's playback quality, pinned for this headphone and asked for again on every " +
        "connect, since the stack renegotiates each time. Measured on this phone, ABR " +
        "was never once seen to reach 990 kbps, so pinning is the only way observed to " +
        "get there."
