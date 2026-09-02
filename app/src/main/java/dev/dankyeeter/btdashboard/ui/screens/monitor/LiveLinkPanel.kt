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
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxDelta
import dev.dankyeeter.btdashboard.monitor.link.live.InputStreamSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveCodecSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.TxLossChannel
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
                FormatLine(snapshot)
                LdacSection(
                    snapshot,
                    ldacTuning,
                    storedQuality,
                    onLdacQuality,
                    onDismissLdacMessage,
                    queuePressureNote(overviewTrace),
                )
                LossRow(snapshot, intervalMs)
                TxRows(snapshot)
                TraceSection(overviewTrace, closeUpTrace, closeUpEnabled, onCloseUpEnabled)
            }
        }

        UpdateRateRow(intervalMs, onIntervalChange)

        // Why a row above is missing. The count is the first layer — it says
        // *that* something could not be read, which is the part affecting how
        // much of this panel to trust — and the machinery's own words are behind
        // the question mark, introduced rather than dumped: they name shell
        // commands and exit codes, which is a fine thing to find when you go
        // looking and a terrible thing to be handed unasked.
        snapshot?.warnings?.takeIf { it.isNotEmpty() }?.let { warnings ->
            ExplainedHeader(
                "${warnings.size} value${if (warnings.size == 1) "" else "s"} could not be read",
                "The link was read anyway; these parts of it did not answer.\n\n" +
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
}

/**
 * What the app produced against what the link carries, on one line.
 *
 * The single most useful line on the panel: an app feeding 44.1 kHz into a link
 * negotiated at 96 kHz is being resampled, and nowhere else on the phone are
 * those two numbers shown together.
 *
 * It used to be two lines. The header printed the negotiated format in full
 * ("96 kHz · 32 bit · stereo") and this line printed it again in short form
 * ("→ Link: LDAC 96 kHz/32") directly underneath — the same three facts twice,
 * in two notations, so that a reader had to check whether they disagreed. One
 * line, one notation: the input on the left, the link on the right, the codec
 * already named by the pill above.
 */
@Composable
private fun FormatLine(snapshot: LinkLiveSnapshot) {
    val input = snapshot.inputs.topPlaying()
    val context = LocalContext.current
    val appName = input?.let { stream -> remember(stream.uid) { appLabel(context, stream.uid) } }
    val out = snapshot.codec?.formatLine()

    Text(
        buildString {
            if (input == null) {
                append("Nothing playing")
            } else {
                append("In $appName")
                input.sampleRateHz?.let { append(" ${formatKhz(it)}") }
                input.pcmFormat?.bits?.let { append("/$it") }
            }
            append("  →  ")
            // Never invented: a link whose negotiated format the dump did not
            // print says so, in the same place the format would have been.
            append(out ?: "format not reported")
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
    /**
     * The send-queue sentence, when there is one — see [queuePressureNote].
     *
     * It joins this row's second layer rather than getting a line of its own
     * because queue pressure is an early indicator about the *step ladder*, not
     * an event: it says the link is at its limit, and what was actually lost is
     * the loss row's sentence to make (`UI_SPEC.md` T-009, AK-T009-29).
     */
    queuePressureNote: String?,
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
        explanation = listOfNotNull(ldac.note.takeIf { it.isNotBlank() }, queuePressureNote)
            .joinToString("\n\n"),
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
            // One name for the control, not two. The block was headed "LDAC
            // quality" and then labelled "Live tuning" on the next line, which
            // reads as two settings stacked on one row of chips.
            Text("LDAC quality", style = MaterialTheme.typography.bodyLarge)
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
        // The paragraph that stood here — "changing this renegotiates the codec:
        // the audio cuts out for a moment, and the result below is read back
        // rather than assumed" — is now the last sentence of this block's own
        // explanation. It answers a question somebody asks once, and it was
        // printed under the chips forever.
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
 *
 * ## Why the encoder underflows stand apart
 *
 * They used to be named inside the red sentence. Two measurements say they do
 * not belong there: the counter did not move at all through the 990 arm where
 * stack dropouts ran throughout, and it climbed from 2 to 25 across 39 minutes
 * of flawless playback (`docs/perf/T-008-experimente.md`,
 * `docs/perf/T-011-messung.md`). At a 2 s cadence that second run would have
 * printed some 23 red "Audio lost" lines over music that was fine. So the
 * number stays on the screen — it is a measurement and AK-2 keeps it — but on
 * its own line, in the quiet colour, as a bare count over a stated window and
 * with no word about what it means.
 */
@Composable
private fun LossRow(snapshot: LinkLiveSnapshot, intervalMs: Long) {
    val tx = snapshot.observableTxDelta
    // The tx window is measured between two polls and is the honest figure; the
    // configured interval is only used when there is no tx block to measure.
    val windowSeconds = ((tx?.windowMs ?: intervalMs) / 100L).toDouble().roundToInt() / 10.0
    val parts = buildList {
        snapshot.inputUnderrunDelta.takeIf { it > 0 }?.let { add(plural(it, "app underrun")) }
        val mixer = (snapshot.mixer?.fastMixerUnderrunDelta ?: 0L) +
            (snapshot.mixer?.normalMixerEmptyDelta ?: 0L)
        mixer.takeIf { it > 0 }?.let { add(plural(it, "mixer underrun")) }
        // The stack's channels are not listed again here. Which of its counters
        // may say "lost" is one decision and it is taken once, in
        // [A2dpTxDelta.lossByChannel]; this row only puts words to what it finds
        // there. Rebuilding the list by hand is how "stack dropouts" once ended
        // up in the sentence without a single test holding it there (QA-010).
        tx?.lossByChannel?.forEach { (channel, count) ->
            count.takeIf { it > 0 }?.let { add(plural(it, channel.singularLabel())) }
        }
    }

    when {
        // R-A / AK-T002-12 (DR-004): the counter is the subject, never the
        // audio. "Audio lost: " used to lead this sentence and was the
        // loudest R-A violation in the app; dropped rather than replaced,
        // because `parts` already reads exactly like the OCCASIONAL wording
        // for a single channel ("{N} {kanal} in the last {W}") strung
        // together for however many channels moved. No rate or age suffix
        // is added — the window stays the measured poll interval, never
        // `LOSS_WINDOW_MS` — so the sentence cannot be mistaken for
        // OCCASIONAL/DISTURBED and claims no frequency nobody measured.
        parts.isNotEmpty() -> Text(
            "${parts.joinToString(", ")} in the last ${trimZero(windowSeconds)} s.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        // A delta needs two readings. Saying "no loss" off a single cumulative
        // total would be a claim about a window nobody measured — so the refusal
        // stays, in four words instead of a sentence explaining subtraction.
        tx == null && snapshot.inputs.none { it.underrunDelta != null } -> Text(
            "Loss needs two readings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // R-A / AK-T002-12 (DR-004): the counter is the subject, never the
        // audio. This is the CLEAN/ALL_FIVE sentence the wording table in
        // `UI_SPEC.md` (T-002, "Formulierungen erste Ebene") has prescribed
        // since 2026-08-30; the pre-spec "No loss this window." shown here
        // before carried the forbidden string "no loss" and made the audio
        // the subject of a claim this row cannot back.
        else -> Text(
            "No counter moved in the last ${trimZero(windowSeconds)} s.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Shown only when it moved: a "0" printed every two seconds teaches the eye
    // to skip the line. The sentence is the first layer and says only what was
    // counted; what the number is worth is behind the question mark, because
    // read on its own — under a line that has just given the all-clear — the
    // word "underflow" reads as a fault (DR-001, AK-T017-1).
    tx?.underflows?.takeIf { it > 0 }?.let { underflows ->
        ExplainedRow(
            label = "${plural(underflows, "encoder underflow")} in the last " +
                "${trimZero(windowSeconds)} s.",
            explanation = UNDERFLOW_EXPLANATION,
            // The quiet style the row had as a plain Text. The disclosure is
            // what changes here, not the emphasis.
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            control = {},
        )
    }
}

/**
 * The tx window this panel may read, or null when it may read none of it.
 *
 * `LiveLinkSource.readPass` already withholds `tx` and `txDelta` on a codec the
 * controller encodes, and that is where the rule belongs. This is the second
 * lock on the same door, and it is here because of what is behind it: the loss
 * row prints a *verdict*, and a verdict must not rest on an invariant kept one
 * module away and covered by a single test. The counters of an offloaded link
 * are whatever the last host-encoded session left in them — read here they
 * would show a frozen, perfectly healthy link that nothing is measuring
 * (QA-009).
 *
 * Only the stack's own counters are withheld. The app and mixer underrun
 * counters keep counting whoever does the encoding, so they keep their say.
 */
private val LinkLiveSnapshot.observableTxDelta: A2dpTxDelta?
    get() = txDelta.takeIf { codec?.isOffloaded != true }

/**
 * What the panel calls each loss channel, singular; [plural] adds the "s".
 *
 * Exhaustive on purpose. The channels live in `core-monitor`, which has no
 * business holding display text, so the words live here — and this `when` is
 * what keeps the two ends together: a channel added to [TxLossChannel] does not
 * compile until this panel has a word for it.
 */
private fun TxLossChannel.singularLabel(): String = when (this) {
    TxLossChannel.DROPPED_PACKETS -> "dropped packet"
    TxLossChannel.STACK_DROPOUTS -> "stack dropout"
}

/**
 * The Bluetooth stack's own queue — when it applies to this link at all.
 *
 * ## The row that is not here any more
 *
 * "Encoder queue: 50 handovers/s" used to sit at the top of this section. It was
 * honest — the counter behind it really does tick with the encoder's 20 ms timer
 * — and it was still wrong to show: it is an internal liveness proxy whose value
 * is 50 on every healthy link and whose only reading a user could take from it
 * was "a number changed". Anybody who can act on it is reading the trace graph
 * instead, where the same liveness shows as a line that is drawn rather than
 * broken. A row that is always the same number teaches the eye to skip the
 * section it lives in.
 *
 * ## The other row that is not here any more
 *
 * "Bluetooth is falling behind: {N} packets queued." sat here in error colour,
 * in the first layer, on every reading with a queue longer than zero. The
 * measurements say that is a false alarm generator: a non-empty send queue
 * occurs in 0-1.4 % of readings on a **healthy** link (1/70, 0/70, 2/262 — and
 * in T-007 the only two were on step-downs, which is the regulator doing its
 * job) against 79-81 % under overload (55/70, 129/160). A line that fires on a
 * single sample cannot tell those apart, so in any long session it would blink
 * red over nothing at all. What replaced it is the *share* over a window, on
 * the ladder row's second layer, from `LADDER_QUEUE_PRESSURE_FRACTION` up —
 * AK-T009-29, and [queuePressureNote] builds the sentence.
 */
@Composable
private fun TxRows(snapshot: LinkLiveSnapshot) {
    val codec = snapshot.codec ?: return
    if (!codec.isOffloaded) return

    // Not a silent blank: the counters exist, are non-zero, and belong to
    // whatever host-encoded session ran last. Reading them here would report
    // a perfectly healthy link that nothing is measuring.
    Text(
        "${codec.family.displayName} is encoded by the controller — " +
            "loss counters do not apply.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    // The cost line that used to sit under this chip — "off by default, it reads
    // the Bluetooth stack twice a second" — said what the section's own
    // explanation already says, and said it permanently to everyone who had
    // already left the chip alone. The chip's off state is the message.
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
        "Bluetooth radio. \"Measured\" is what the stack says it is sending and " +
        "\"pinned\" is the spec figure for the mode; where a value cannot be read, the " +
        "panel says why instead of guessing."

private const val LDAC_TUNING_EXPLANATION =
    "Pins LDAC to one playback quality for the headphone connected now; it needs the " +
        "privileged helper and is lost on reconnect unless the device's profile stores " +
        "it. Measured on this phone, adaptive was never once seen to reach 990 kbps, so " +
        "pin High quality if you want it. Changing it renegotiates the codec: the audio " +
        "cuts out for a moment, and the result above is read back rather than assumed."

private const val TRACE_EXPLANATION =
    "The bitrate the LDAC encoder reports it is sending, with every window that lost " +
        "audio marked red on the same time axis; a missed reading leaves a break rather " +
        "than a drawn-across guess. The 10-second close-up sees stack loss only and " +
        "costs a measured 233 ms per reading, which is why it is off until you ask."

private const val UNDERFLOW_EXPLANATION =
    "This count carries no verdict, in either direction: the same counter stayed at zero on " +
        "a link where stack dropouts ran throughout, and it climbed here through 39 minutes of " +
        "playback with nothing else wrong."

/**
 * The share of readings with a non-empty send queue at which the queue becomes
 * worth a sentence.
 *
 * Measured, not chosen: the resting values are 1/70 = 1.43 % (T-008 arm A0),
 * 0/70 (arm A') and 2/262 = 0.76 % (T-007), the overload values 55/70 = 79 %
 * and 129/160 = 81 %. 0.20 sits 14x above the highest resting value and 4x
 * below the lowest overload one — the best-evidenced threshold in `UI_SPEC.md`
 * (T-009, "Warteschlangendruck statt Einzelpaket-Alarm").
 *
 * ## How coarsely it can be met
 *
 * Those readings were taken at a fast cadence; the panel's slowest is 5 s, and
 * the window is 60 s. That leaves about twelve readings, so one non-empty sample
 * moves the share by some 8.3 points: 3 of 12 is 25 % and speaks, 2 of 12 is
 * 16.7 % and does not. The threshold is met in steps, not exactly.
 *
 * Harmless between the populations it was drawn from. Resting is 0-1.4 % and
 * overload 79-81 %, fourteen times below the line and four times above it, so no
 * single sample can carry a window from one of them across it.
 *
 * Whether an operating state exists *between* those two is **not measured** —
 * neither for it nor against it. M-11, the measurement that would settle it, has
 * no known method: the only lever that produces dropouts at all is pinning 990,
 * and that moves the ladder step at the same time. So this says where the
 * evidence stops instead of reading past it, which is the rule R-E rests on.
 */
private const val LADDER_QUEUE_PRESSURE_FRACTION = 0.20

/**
 * The ladder row's second-layer sentence about the send queue, or null while
 * the queue is not the story.
 *
 * Null in three different situations that all mean "do not say anything": no
 * reading in the window carried the queue at all, the share is below the
 * threshold, or there is no window yet. The subject of the sentence is the
 * queue — a counter — rather than "Bluetooth", which keeps it a reading instead
 * of a verdict (R-A).
 */
internal fun queuePressureNote(trace: LiveTrace): String? {
    val fraction = trace.queuePressureFraction ?: return null
    if (fraction < LADDER_QUEUE_PRESSURE_FRACTION) return null
    val percent = (fraction * 100).roundToInt()
    return "The send queue was not empty in $percent % of the readings in the last " +
        "${trimZero(trace.windowMs / 1000.0)} s."
}

private const val UPDATE_RATE_EXPLANATION =
    "How often the panel re-reads the link. One pass takes roughly half a second of " +
        "work, measured — one second is worth it while chasing a dropout, five seconds " +
        "for leaving the panel open."

// ---- formatting -------------------------------------------------------------

/**
 * "96 kHz · 32 bit · stereo", with whatever was actually reported, or null when
 * the dump named none of the three.
 *
 * Null rather than a sentence: the caller places this inside a line that has a
 * left-hand side, and a full stop in the middle of it would end a sentence that
 * had not started. The refusal is worded where it is shown.
 */
private fun LiveCodecSnapshot.formatLine(): String? {
    val parts = buildList {
        sampleRateHz?.let { add(formatKhz(it)) }
        bitsPerSample?.let { add("$it bit") }
        channelMode.label()?.let { add(it) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
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

/**
 * "2 s" rather than "2.0 s", "1.8 s" when the window really was uneven.
 *
 * `internal` rather than `private`: [LabelledTraceGraph]'s default quiet
 * caption needs the same "{W} s" formatting for its own window (DR-004), and
 * a second copy of this one-line rule would be the kind of drift that let the
 * two files' wording diverge from `UI_SPEC.md` in the first place.
 */
internal fun trimZero(seconds: Double): String =
    if (seconds == seconds.toInt().toDouble()) {
        seconds.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", seconds)
    }

/** "1 app underrun" / "3 app underruns" — never "1 underrun(s)". */
private fun plural(count: Long, singular: String): String =
    if (count == 1L) "$count $singular" else "$count ${singular}s"
