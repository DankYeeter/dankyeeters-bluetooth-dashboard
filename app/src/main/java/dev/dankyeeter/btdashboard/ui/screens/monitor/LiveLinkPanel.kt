package dev.dankyeeter.btdashboard.ui.screens.monitor

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.dankyeeter.btdashboard.monitor.codec.ChannelMode
import dev.dankyeeter.btdashboard.monitor.link.live.InputStreamSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveCodecSnapshot
import dev.dankyeeter.btdashboard.ui.tuning.LdacQuality
import dev.dankyeeter.btdashboard.ui.tuning.LdacTuningState
import dev.dankyeeter.btdashboard.ui.theme.ExplainedBlock
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
import dev.dankyeeter.btdashboard.ui.theme.ExplainedRow
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.Pill
import dev.dankyeeter.btdashboard.ui.theme.PillTone
import java.util.Locale
import kotlin.math.roundToInt

/**
 * "What is happening on this link right now", and the panel this whole live
 * module exists for.
 *
 * ## The rule every row here follows
 *
 * `LinkLiveSnapshot` labels each of its fields MEASURED, DERIVED, NOMINAL or
 * UNAVAILABLE, and this panel renders that distinction rather than flattening it
 * into numbers that all look equally solid:
 *
 *  - **measured and derived** values are printed plainly — they are facts. The
 *    LDAC rate says "(measured)" out loud, because the whole history of this
 *    screen is people being shown a codec's headline number and taking it for a
 *    reading;
 *  - **nominal** values say what they are ("990 kbps (pinned)", with the note
 *    behind the question mark spelling out that it is the spec figure for the
 *    mode). Where the stack also reports what it is really sending, both are
 *    printed side by side so the two can be compared instead of trusted;
 *  - **unavailable** values are never guessed. On a build that does not print
 *    the LDAC state block there is no readable rate, so the panel prints the
 *    data layer's own sentence about *why*, verbatim.
 *
 * There is no "(proxy)" row any more. There used to be one — frames per packet,
 * offered as a stand-in for the rate — and the device falsified it: the counter
 * under it turns out to be a 20 ms timer tick, so the row was reporting the
 * playing duty cycle in the shape of a quality indicator. It is gone rather than
 * relabelled, because a rate-shaped row next to the real rate would be read as a
 * second opinion about the rate.
 *
 * The loss row is the reason the panel is at the top of the screen: it is quiet
 * when nothing was lost and loud when something was, and every non-zero window
 * also lands on the timeline through the ViewModel, so "it stuttered around
 * half past" can be checked against something afterwards.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveLinkPanel(
    snapshot: LinkLiveSnapshot?,
    intervalMs: Long,
    onIntervalChange: (Long) -> Unit,
    ldacTuning: LdacTuningState,
    onLdacQuality: (Long) -> Unit,
    onDismissLdacMessage: () -> Unit,
    /**
     * What this device's profile stores, or [LdacQuality.NONE] for nothing.
     *
     * The chips are lit from this rather than from the live mode alone, because
     * the Bluetooth tab draws the same four chips for the same headphone off the
     * same stored value — see [LdacQuality.selected].
     */
    storedQuality: Long = LdacQuality.NONE,
    modifier: Modifier = Modifier,
    overviewTrace: LiveTrace = LiveTrace.overview(intervalMs),
    closeUpTrace: LiveTrace = LiveTrace.closeUp(500L),
    closeUpEnabled: Boolean = false,
    onCloseUpEnabled: (Boolean) -> Unit = {},
) {
    Panel(modifier) {
        ExplainedHeader("Live link", LIVE_LINK_EXPLANATION)

        when {
            // Not an error and not zeroes: the first pass takes about half a
            // second, and a panel full of "0" would be read as a measurement.
            snapshot == null -> Text(
                "Waiting for the first reading.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            snapshot.isEmpty -> Text(
                "Nothing on the link could be read.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                LinkHeader(snapshot)
                InputVersusLink(snapshot)
                LdacSection(
                    snapshot,
                    ldacTuning,
                    storedQuality,
                    onLdacQuality,
                    onDismissLdacMessage,
                )
                LossRow(snapshot, intervalMs)
                TxRows(snapshot)
                TraceSection(overviewTrace, closeUpTrace, closeUpEnabled, onCloseUpEnabled)
            }
        }

        UpdateRateRow(intervalMs, onIntervalChange)

        // Why a row above is missing, in the machinery's own words. Behind the
        // question mark because it is the last thing anybody needs to read and
        // the first thing that would swamp the panel if printed in full.
        snapshot?.warnings?.takeIf { it.isNotEmpty() }?.let { warnings ->
            ExplainedHeader(
                "Not readable (${warnings.size})",
                warnings.joinToString("\n\n") { it.replaceFirstChar(Char::uppercase) + "." },
            )
        }
    }
}

/** Device, codec and the negotiated format — the three facts of a link. */
@Composable
private fun LinkHeader(snapshot: LinkLiveSnapshot) {
    val device = snapshot.device
    val codec = snapshot.codec

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            // The address is the fallback, never the first choice: a name is
            // what the user calls the thing sitting on their head. Masked when
            // it is used, because the dump is only redacted on user builds and
            // this panel must not be the one screen that prints a real MAC on a
            // developer build. The last two octets stay, which is all it takes
            // to tell two connected headphones apart.
            device?.name?.takeIf { it.isNotBlank() }
                ?: device?.address?.let(::maskAddress)
                ?: "No device",
            style = MaterialTheme.typography.titleMedium,
        )
        codec?.family?.let { Pill(it.displayName, tone = PillTone.ACCENT) }
        // "Connected" and "playing" are different facts, and only the second one
        // explains why every counter below is standing still.
        if (device?.isConnected == true && device.isPlaying != true) {
            Pill("Idle", tone = PillTone.NEUTRAL)
        }
    }

    Text(
        codec?.formatLine() ?: "Negotiated format not reported.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * What the app produced against what the link carries.
 *
 * The single most useful line on the panel: an app feeding 44.1 kHz into a link
 * negotiated at 96 kHz is being resampled, and nowhere else on the phone are
 * those two numbers shown together.
 */
@Composable
private fun InputVersusLink(snapshot: LinkLiveSnapshot) {
    val input = snapshot.inputs.topPlaying()
    val context = LocalContext.current
    val appName = input?.let { stream -> remember(stream.uid) { appLabel(context, stream.uid) } }

    Text(
        when {
            input == null -> "Nothing is playing into this link right now."
            else -> buildString {
                append("In: $appName")
                input.sampleRateHz?.let { append(" ${formatKhz(it)}") }
                input.pcmFormat?.bits?.let { append("/$it") }
                append("  →  Link: ")
                append(snapshot.codec?.shortFormat() ?: "not reported")
            }
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * LDAC's configured rate, and the control that changes it.
 *
 * Split in two because the two halves answer different questions: the row says
 * what the link is set to (and, in adaptive mode, why that cannot be a number),
 * the chips are the only place in the app where that setting can be moved for
 * the link that is playing right now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LdacSection(
    snapshot: LinkLiveSnapshot,
    tuning: LdacTuningState,
    storedQuality: Long,
    onLdacQuality: (Long) -> Unit,
    onDismissMessage: () -> Unit,
) {
    val ldac = snapshot.ldac ?: return
    val sampleRateHz = snapshot.codec?.sampleRateHz
    // One rule for both screens: the stored wish first, the live mode second.
    val selected = LdacQuality.selected(storedQuality, ldac.mode)

    // First layer: one short line. The note behind it is the data layer's own
    // sentence and is printed verbatim — summarising it would lose exactly the
    // part a user needs in order to do something about it.
    ExplainedRow(
        label = ldac.rateLine(),
        explanation = ldac.note,
        control = {
            Pill(
                ldac.mode.label,
                tone = if (ldac.isAdaptive) PillTone.NEUTRAL else PillTone.ACCENT,
            )
        },
    )

    ExplainedBlock("LDAC quality", LDAC_TUNING_EXPLANATION) { toggle ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Live tuning", style = MaterialTheme.typography.bodyLarge)
            toggle()
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LdacQuality.pinnable.forEach { quality ->
                FilterChip(
                    selected = selected == quality,
                    // Never two requests in flight: each one renegotiates the
                    // codec, and the second would race the first's read-back.
                    enabled = !tuning.busy,
                    onClick = { onLdacQuality(quality) },
                    // The rate ladder follows the sample-rate family — 990/660/330
                    // at 48 and 96 kHz, 909/606/303 at 44.1 and 88.2. Labelling
                    // every chip "990" would be off by 8 % on half of all links.
                    label = { Text(LdacQuality.chipLabel(quality, sampleRateHz)) },
                )
            }
        }
        Text(
            "Changing this renegotiates the codec: the audio cuts out for a moment, " +
                "and the result below is read back rather than assumed.",
            style = MaterialTheme.typography.bodySmall,
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
}

/**
 * The row the panel exists for: was anything lost in the last window.
 *
 * Quiet when nothing was — a green tick for "no dropouts" trains the eye to
 * skip the row, and this is the one row that must be noticed when it changes.
 */
@Composable
private fun LossRow(snapshot: LinkLiveSnapshot, intervalMs: Long) {
    val tx = snapshot.txDelta
    // The tx window is measured between two polls and is the honest figure; the
    // configured interval is only used when there is no tx block to measure.
    val windowSeconds = ((tx?.windowMs ?: intervalMs) / 100L).toDouble().roundToInt() / 10.0
    val parts = buildList {
        snapshot.inputUnderrunDelta.takeIf { it > 0 }?.let { add(plural(it, "app underrun")) }
        val mixer = (snapshot.mixer?.fastMixerUnderrunDelta ?: 0L) +
            (snapshot.mixer?.normalMixerEmptyDelta ?: 0L)
        mixer.takeIf { it > 0 }?.let { add(plural(it, "mixer underrun")) }
        tx?.dropped?.takeIf { it > 0 }?.let { add(plural(it, "dropped packet")) }
        tx?.dropouts?.takeIf { it > 0 }?.let { add(plural(it, "stack dropout")) }
        tx?.underflows?.takeIf { it > 0 }?.let { add(plural(it, "encoder underflow")) }
    }

    when {
        parts.isNotEmpty() -> Text(
            "Audio lost: ${parts.joinToString(", ")} in the last ${trimZero(windowSeconds)} s.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        // A delta needs two readings. Saying "no loss" off a single cumulative
        // total would be a claim about a window nobody measured.
        tx == null && snapshot.inputs.none { it.underrunDelta != null } -> Text(
            "Nothing to compare yet — loss is the change between two readings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> Text(
            "No loss this window.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The Bluetooth stack's own queue — when it applies to this link at all. */
@Composable
private fun TxRows(snapshot: LinkLiveSnapshot) {
    val codec = snapshot.codec
    if (codec != null && codec.isOffloaded) {
        // Not a silent blank: the counters below exist, are non-zero, and belong
        // to whatever host-encoded session ran last. Reading them here would
        // report a perfectly healthy link that nothing is measuring.
        Text(
            "${codec.family.displayName} is encoded by the controller, so the Bluetooth " +
                "stack's packet counters do not describe this link.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    snapshot.txDelta?.packetsPerSecond?.let { pps ->
        // Not a throughput row and no longer worded as one. The counter behind
        // it ticks with the 20 ms media timer rather than with the radio, so it
        // says whether the stack is handing audio over at all — which is worth a
        // line, and is a different question from how much.
        ExplainedRow(
            label = "Encoder queue: ${pps.roundToInt()} handovers/s",
            explanation = ENQUEUE_EXPLANATION,
            control = {},
        )
    }

    snapshot.ldac?.stack?.savedTxQueueLength?.takeIf { it > 0 }?.let { queued ->
        // Only when it is not zero: a "0" here every second trains the eye past
        // the row, and a backlog is exactly the thing worth noticing.
        Text(
            "LDAC transmit queue backlog: $queued",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * The two graphs: a minute to look back over, and ten seconds to watch.
 *
 * They are stacked rather than tabbed because they answer in sequence — the
 * minute says *whether and roughly when*, the close-up says *exactly when* — and
 * a user chasing a stutter wants to switch the second one on while the first
 * one is still showing the mark that made them look.
 */
@Composable
private fun TraceSection(
    overview: LiveTrace,
    closeUp: LiveTrace,
    closeUpEnabled: Boolean,
    onCloseUpEnabled: (Boolean) -> Unit,
) {
    ExplainedHeader("Throughput", TRACE_EXPLANATION)

    LabelledTraceGraph("Last 60 seconds", overview)

    LabelledTraceGraph(
        title = "Last 10 seconds",
        trace = closeUp,
        // This channel never reads the two dumps that carry app and mixer
        // underruns, so its quiet state is narrower than the other graph's.
        quietText = "no stack loss in this window",
        trailing = {
            // A switch would read as a setting. This is a thing you turn on for
            // a minute while something is wrong, so it wears the same chip the
            // rest of the panel uses for a choice.
            FilterChip(
                selected = closeUpEnabled,
                onClick = { onCloseUpEnabled(!closeUpEnabled) },
                label = { Text(if (closeUpEnabled) "Watching" else "Watch closely") },
            )
        },
    )
    if (!closeUpEnabled) {
        Text(
            // The cost is stated where the button is, not behind the question
            // mark: it is the reason this one is not simply always on.
            "Off by default — it reads the Bluetooth stack twice a second.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** How often the panel polls. The measured cost of each rate is in the explainer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateRateRow(intervalMs: Long, onIntervalChange: (Long) -> Unit) {
    ExplainedRow(
        label = "Update rate",
        explanation = UPDATE_RATE_EXPLANATION,
        control = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                INTERVALS.forEach { (ms, label) ->
                    FilterChip(
                        selected = intervalMs == ms,
                        onClick = { onIntervalChange(ms) },
                        label = { Text(label) },
                    )
                }
            }
        },
    )
}

// ---- values -----------------------------------------------------------------

/** The three poll rates, slowest cost first. Named in seconds, as a user thinks. */
private val INTERVALS = listOf(1_000L to "1 s", 2_000L to "2 s", 5_000L to "5 s")

// The four quality modes AOSP can be asked for, their `codecSpecific1` values
// and their labels now live in [LdacQuality] — one ladder for this panel and the
// Bluetooth tab's chips, rather than two that could drift.

private const val LIVE_LINK_EXPLANATION =
    "One reading of the whole audio path, from the app that is playing to the " +
        "Bluetooth radio.\n\n" +
        "A rate marked \"measured\" is what the Bluetooth stack says it is sending right " +
        "now; a rate marked \"pinned\" is the spec figure for that mode. Where a value " +
        "cannot be read on this phone, the panel says why instead of guessing. It polls " +
        "only while this screen is in front of you."

private const val LDAC_TUNING_EXPLANATION =
    "Pins LDAC to one playback quality for the headphone that is connected now.\n\n" +
        "Measured on this phone, adaptive was never once seen to reach 990 kbps — it " +
        "moves between about 330 and 660. If you want 990, pin High quality.\n\n" +
        "The change interrupts playback briefly and does not survive a reconnect, so " +
        "store it in the device's profile to get it back. It needs the privileged " +
        "helper; without it nothing can be set or read back."

private const val ENQUEUE_EXPLANATION =
    "How often the Bluetooth stack hands a buffer of encoded audio to the radio.\n\n" +
        "This is a liveness figure, not a throughput one: measured on this phone it sits " +
        "at about 50 per second in every LDAC mode, because it is the encoder's 20 ms " +
        "timer rather than the air. A steady figure means audio is flowing; the rate it " +
        "is flowing at is the kbps figure above."

private const val TRACE_EXPLANATION =
    "The bitrate the LDAC encoder reports it is sending, read from the Bluetooth stack " +
        "once per reading; adaptive moves it on its own. Every window that lost audio is " +
        "marked in red on the same time axis, and a reading that was missed or came late " +
        "leaves a break in the line rather than a drawn-across guess.\n\n" +
        "Where the phone reports no rate, the line falls back to how often the stack " +
        "hands audio to the radio — a liveness signal, not a rate — and each graph's " +
        "caption names which it is drawing.\n\n" +
        "The 10-second close-up costs 233 ms of work per reading, measured, which is why " +
        "it is off until you ask for it. It sees the Bluetooth stack's own loss only, so " +
        "a quiet close-up is not proof the app kept up."

private const val UPDATE_RATE_EXPLANATION =
    "How often the panel re-reads the link.\n\n" +
        "One pass runs three system dumps and takes roughly half a second of work, " +
        "measured — so at one second the phone spends about half its time producing this " +
        "screen, at two seconds a quarter of it. One second is worth it while you are " +
        "chasing a dropout; five seconds is for leaving the panel open."

// ---- formatting -------------------------------------------------------------

/** "96 kHz · 32 bit · stereo", with whatever was actually reported. */
private fun LiveCodecSnapshot.formatLine(): String {
    val parts = buildList {
        sampleRateHz?.let { add(formatKhz(it)) }
        bitsPerSample?.let { add("$it bit") }
        channelMode.label()?.let { add(it) }
    }
    return if (parts.isEmpty()) "Negotiated format not reported." else parts.joinToString(" · ")
}

/** "LDAC 96 kHz/32" — the compact form for the input-versus-link line. */
private fun LiveCodecSnapshot.shortFormat(): String = buildString {
    append(family.displayName)
    sampleRateHz?.let { append(" ${formatKhz(it)}") }
    bitsPerSample?.let { append("/$it") }
}

private fun ChannelMode.label(): String? = when (this) {
    ChannelMode.MONO -> "mono"
    ChannelMode.STEREO -> "stereo"
    ChannelMode.DUAL_CHANNEL -> "dual channel"
    ChannelMode.UNKNOWN -> null
}

/**
 * The one line the LDAC row leads with, in four states.
 *
 * The states are deliberately not collapsed. "Adaptive and here is what it is
 * doing" and "adaptive and this build will not say" look similar and mean
 * opposite things to somebody deciding whether to pin a quality, and the pinned
 * case shows the spec figure *and* the measurement precisely so that a link
 * quietly not delivering its pinned rate is visible rather than assumed away.
 *
 * "(measured)" is in the first layer rather than only behind the question mark:
 * the line has to be honest to somebody who never taps it, and this is the
 * number that used to be impossible to show at all.
 */
private fun LdacState.rateLine(): String {
    val measured = measuredKbps
    return when {
        measured != null && isAdaptive -> "Adaptive — $measured kbps right now (measured)"
        measured != null && nominalKbps != null ->
            "$nominalKbps kbps (pinned) · $measured kbps measured"

        measured != null -> "$measured kbps right now (measured)"
        isAdaptive -> "Adaptive — rate not observable"
        nominalKbps != null -> "$nominalKbps kbps (pinned)"
        else -> "LDAC quality not readable"
    }
}

/**
 * The app most likely to be the one the user is listening to.
 *
 * Media usage first: a notification chirp and a music player are both "playing",
 * and naming the chirp as the input to a 96 kHz LDAC link would be nonsense.
 */
private fun List<InputStreamSnapshot>.topPlaying(): InputStreamSnapshot? =
    firstOrNull { it.usage?.contains("MEDIA", ignoreCase = true) == true } ?: firstOrNull()

/**
 * The app's own name for its uid, falling back to the number.
 *
 * A uid is what the dump provides and a name is what the user recognises, so it
 * is resolved here rather than shown raw. When the lookup fails the number is
 * kept as-is — inventing "Unknown app" would hide that there really is a process
 * playing, which is the fact the line is making.
 */
private fun appLabel(context: Context, uid: Int): String {
    val pm = context.packageManager
    val packages = runCatching { pm.getPackagesForUid(uid) }.getOrNull().orEmpty()
    val label = packages.firstNotNullOfOrNull { name ->
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString() }.getOrNull()
    }
    return label?.takeIf { it.isNotBlank() } ?: packages.firstOrNull() ?: "uid $uid"
}

/** "44.1 kHz" / "96 kHz" — US formatting, so a German locale cannot print "44,1". */
private fun formatKhz(hz: Int): String {
    val khz = hz / 1000.0
    return if (khz == khz.toInt().toDouble()) {
        "${khz.toInt()} kHz"
    } else {
        String.format(Locale.US, "%.1f kHz", khz)
    }
}

/** "2 s" rather than "2.0 s", "1.8 s" when the window really was uneven. */
private fun trimZero(seconds: Double): String =
    if (seconds == seconds.toInt().toDouble()) {
        seconds.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", seconds)
    }

/** "1 app underrun" / "3 app underruns" — never "1 underrun(s)". */
private fun plural(count: Long, singular: String): String =
    if (count == 1L) "$count $singular" else "$count ${singular}s"
