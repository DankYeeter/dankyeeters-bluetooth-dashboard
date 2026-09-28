package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dankyeeter.btdashboard.monitor.link.live.LdacQualityMode
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.RunEnd
import dev.dankyeeter.btdashboard.monitor.link.live.hasReadableRate
import dev.dankyeeter.btdashboard.monitor.optimize.ARM_TARGET_MS
import dev.dankyeeter.btdashboard.monitor.optimize.Arm
import dev.dankyeeter.btdashboard.monitor.optimize.ComparisonResult
import dev.dankyeeter.btdashboard.monitor.optimize.Condition
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionValue
import dev.dankyeeter.btdashboard.monitor.optimize.Fallback
import dev.dankyeeter.btdashboard.monitor.optimize.Measure
import dev.dankyeeter.btdashboard.monitor.optimize.NotComparable
import dev.dankyeeter.btdashboard.monitor.optimize.Verdict
import dev.dankyeeter.btdashboard.monitor.optimize.Verification
import dev.dankyeeter.btdashboard.monitor.optimize.smallestDetectableBaseline
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.tuning.LdacQuality
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * The guided before/after comparison (`UI_SPEC.md` T-047 S3-6, AK-12..14, AK-16).
 *
 * Under the observation run and above the update rate: first "how low did I
 * go", then "does a change help against it". Exactly one phase is on screen.
 * Every sentence is `UI_SPEC.md`'s, character for character; the frame, the
 * AK-16 block and the verdict share one composable, with no second layer.
 *
 * [restoreButton] is the D2 banner itself, handed in by the screen — the one
 * way back, not a second one.
 */
@Composable
internal fun ComparisonSection(
    snapshot: LinkLiveSnapshot,
    state: ComparisonUi,
    onMeasure: (Measure) -> Unit,
    onCompare: () -> Unit,
    onCheck: () -> Unit,
    /** "New comparison", and "Cancel" after a failed pin, when nothing runs to be stopped. */
    onReset: () -> Unit,
    restoreButton: @Composable () -> Unit,
    /** "Cancel" while a comparison runs: the very call the notification's "Stop" makes (AK-T054-2). */
    onCancel: () -> Unit = { ActiveComparison.end(ComparisonEnd.Cancelled) },
    /** Whether the measurement service holds the arms off the screen (AD-039 point 5). */
    keptOffScreen: Boolean = MeasurementService.foreground.collectAsStateWithLifecycle().value,
) {
    val topKbps = topStepKbps(snapshot.codec?.sampleRateHz)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Compare a change, before and after", style = MaterialTheme.typography.titleSmall)
        when (state.phase) {
            ComparisonPhase.CHOOSE -> Choose(snapshot, state, topKbps, onMeasure, onCompare)
            ComparisonPhase.PIN_AND_CHECK ->
                PinAndCheck(state, topKbps, onCompare, if (state.pinFailure == null) onCancel else onReset)
            ComparisonPhase.ARM_A, ComparisonPhase.ARM_B -> ArmProgress(state, keptOffScreen, onCancel, restoreButton)
            ComparisonPhase.INSTRUCT -> Instruct(state, onCheck, onCancel, restoreButton)
            ComparisonPhase.RESULT -> Result(snapshot, state, onReset, restoreButton)
        }
    }
}

@Composable
private fun Choose(
    snapshot: LinkLiveSnapshot,
    state: ComparisonUi,
    topKbps: Int,
    onMeasure: (Measure) -> Unit,
    onCompare: () -> Unit,
) {
    Text(
        "This runs two ${ARM_MINUTES}-minute observations back to back: as it is now, then with one " +
            "change applied, both pinned to $topKbps kbps. Pinning a fixed step does not avoid " +
            "dropped-audio incidents — expect to notice some during both arms; that is what lets " +
            "this run count them.",
        style = MaterialTheme.typography.bodyMedium,
    )
    state.alreadyApplies?.let {
        Text("${it.displayName} already applies — there is nothing to compare a change against.")
    }
    state.measure?.let { measure -> state.ended?.let { endedSentence(it, measure) } }?.let { Text(it) }
    Measure.entries.forEach { measure ->
        Row(
            modifier = Modifier.selectable(
                selected = measure == state.measure,
                onClick = { onMeasure(measure) },
                role = Role.RadioButton,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = measure == state.measure, onClick = null)
            Text(measure.displayName)
        }
    }
    // A run on a rate that cannot be read would end at its first reading, after a pin.
    GoldButton(onClick = onCompare, enabled = state.measure != null && snapshot.hasReadableRate) {
        Text("Compare")
    }

    Text("Workarounds", style = MaterialTheme.typography.titleSmall)
    Text(
        "These lower the bitrate on purpose. They are not fixes for the incidents above — they avoid " +
            "them by asking for less.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Fallback.entries.forEach { fallback ->
        Text(fallback.displayName, style = MaterialTheme.typography.bodyMedium)
        Text(
            fallback.sentence(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PinAndCheck(state: ComparisonUi, topKbps: Int, onRetry: () -> Unit, onCancel: () -> Unit) {
    val failure = state.pinFailure
    if (failure == null) {
        Text("Pinning to $topKbps kbps…")
        TextButton(onClick = onCancel) { Text("Cancel") }
    } else {
        Text("Could not pin to $topKbps kbps: ${redactAddresses(failure)}. Nothing was measured.")
        Row {
            TextButton(onClick = onRetry) { Text("Try again") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun ArmProgress(
    state: ComparisonUi,
    keptOffScreen: Boolean,
    onCancel: () -> Unit,
    restoreButton: @Composable () -> Unit,
) {
    val run = state.running?.run ?: return
    state.restart?.let { Text(restartLine(it)) }
    if (state.phase == ComparisonPhase.ARM_B) state.readBack?.let { Text(readBackSentence(state.measure, it)) }
    Text(
        if (run.end == RunEnd.TARGET_REACHED) {
            endLine(RunEnd.TARGET_REACHED, run.observedMs)
        } else {
            "${formatSpan(run.observedMs)} of ${formatSpan(run.targetMs ?: ARM_TARGET_MS)} observed."
        },
    )
    Text(if (keptOffScreen) ARM_KEEPS_COUNTING else ARM_NEEDS_THIS_SCREEN)
    TextButton(onClick = onCancel) { Text("Cancel") }
    restoreButton()
}

@Composable
private fun Instruct(
    state: ComparisonUi,
    onCheck: () -> Unit,
    onCancel: () -> Unit,
    restoreButton: @Composable () -> Unit,
) {
    val measure = state.measure ?: return
    Text(measure.instruction)
    SettingsJump(measure)
    val readBack = state.readBack
    readBack?.let { Text(readBackSentence(measure, it)) }
    if (readBack != null && measure.verifies(readBack) == Verification.CONTRADICTED) {
        Text("Arm B has not started — the condition above still holds.")
        TextButton(onClick = onCheck) { Text("Check again") }
    } else {
        GoldButton(onClick = onCheck) { Text("Start Arm B") }
    }
    TextButton(onClick = onCancel) { Text("Cancel") }
    restoreButton()
}

/** Verdict, frame, AK-16 block and the process sentence — one composable, no second layer (AK-14). */
@Composable
private fun Result(
    snapshot: LinkLiveSnapshot,
    state: ComparisonUi,
    onNew: () -> Unit,
    restoreButton: @Composable () -> Unit,
) {
    val armA = state.armA ?: return
    val armB = state.armB ?: return
    Column(Modifier.testTag(RESULT_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val result = state.result) {
            is ComparisonResult.Compared -> Text(verdictSentence(result.verdict, result.pValue))
            is ComparisonResult.NotComparableResult -> result.reasons.forEach { Text(notComparableSentence(it)) }
            null -> Unit
        }
        frameLines(armA, armB, state.result).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        boundaryLines(snapshot, armA).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(PROCESS_END, style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = onNew) { Text("New comparison") }
    restoreButton()
}

/**
 * The measurement frame (AK-14): durations, each arm's own step and codec (F-018), cadence, the
 * read-back of the counted arm B (F-017), book, what was not checked, drift, scope, limit.
 */
private fun frameLines(armA: Arm, armB: Arm, result: ComparisonResult?): List<String> {
    val cadences = (armA.run.cadencesMs + armB.run.cadencesMs).sorted().joinToString(" or ") { formatSpan(it) }
    val books = listOf(armA.start, armA.end, armB.start, armB.end)
    val notChecked = Condition.entries.filter { c -> books.any { it[c] !is ConditionValue.Read } }
    return buildList {
        add("Arm A ran for ${formatSpan(armA.run.observedMs)}, Arm B for ${formatSpan(armB.run.observedMs)}.")
        add(armLinkLine("A", armA))
        add(armLinkLine("B", armB))
        add("Read every $cadences.")
        // The book at the start of the arm that counted, the one the verification was read from (AD-035).
        if (result is ComparisonResult.Compared) add(readBackSentence(result.measure, result.after.start))
        Condition.entries.forEach { c ->
            add(
                "${c.displayName}: ${armA.start.shown(c)} at the start of Arm A, ${armA.end.shown(c)} at the end. " +
                    "${armB.start.shown(c)} at the start of Arm B, ${armB.end.shown(c)} at the end.",
            )
        }
        if (notChecked.isNotEmpty()) add("Not checked in this run: ${notChecked.joinToString(", ") { it.displayName }}.")
        add(
            "The two arms ran one after another, not at the same time — anything that changed between them " +
                "besides the chosen change is part of this difference too, and this run cannot separate it out.",
        )
        add(
            "This is a relative result about these exact $ARM_MINUTES minutes on each side, not a statement " +
                "about everyday quality.",
        )
        add(
            "At $ARM_MINUTES minutes each, a difference is only detectable if Arm A counted at least " +
                "${smallestDetectableBaseline()} incidents — fewer than that, and even a drop to zero afterwards " +
                "would not be distinguishable from chance.",
        )
    }
}

/** One arm's step and codec from its own link; "pinned" only when the link was not adaptive. */
private fun armLinkLine(armName: String, arm: Arm): String {
    val link = arm.run.link
    val step = if (link?.adaptive == false) {
        LdacQuality.chipLabel(LdacQuality.codeOf(link.mode), link.sampleRateHz)
    } else {
        "not pinned (ABR)"
    }
    return "Arm $armName: $step, ${link?.codec?.displayName ?: "codec not read"}."
}

/**
 * AK-16 (AD-036): the app names the limit of its advice, beside the facts it could read — and
 * those only while the pairing connected now is the one arm A ran on (F-016, AK-15).
 */
private fun boundaryLines(snapshot: LinkLiveSnapshot, armA: Arm): List<String> {
    val threeMbps = when (snapshot.pairing?.threeMbps) {
        true -> "yes"
        false -> "no"
        null -> "not readable"
    }
    val mtu = snapshot.ldac?.stack?.effectiveMtu?.toString() ?: "not readable"
    val pairingFacts = if (snapshot.device?.address == armA.run.link?.address) {
        listOf("3 Mbps EDR: $threeMbps — read on this pairing.", "Effective MTU: $mtu — read on this pairing.")
    } else {
        listOf(
            "3 Mbps EDR and effective MTU are not shown — the connected pairing has changed since this " +
                "comparison ran.",
        )
    }
    return listOf(
        "Whether this pairing can carry ${topStepKbps(armA.run.link?.sampleRateHz)} kbps at all is not something " +
            "this phone can determine — that needs the packet type, and it is not readable without BQR access " +
            "this app does not have.",
    ) + pairingFacts + "Packet type and retransmission rate: not readable without BQR."
}

/**
 * Why the last comparison stopped without a result (UI_SPEC F-019), named from the arm's own
 * end where it has one; null for the user's own "Cancel" or "Stop", which need no reason.
 */
internal fun endedSentence(ended: ComparisonEnd, measure: Measure): String? {
    val why = when (ended) {
        ComparisonEnd.Cancelled -> return null
        ComparisonEnd.SettingsPutBack -> "when settings were put back"
        is ComparisonEnd.LinkChanged -> when (ended.end) {
            RunEnd.CODEC_CHANGED -> "because the codec left LDAC"
            RunEnd.QUALITY_CHANGED -> "because the LDAC quality changed"
            RunEnd.DISCONNECTED -> "because the headphone disconnected"
            RunEnd.SAMPLE_RATE_CHANGED -> "because the sample rate changed"
            RunEnd.RATE_UNREADABLE -> "because the rate stopped being readable"
            else -> "because the link changed"
        }
    }
    return "Comparing ${measure.displayName} was stopped $why — no result, and neither arm counts."
}

internal fun verdictSentence(verdict: Verdict, pValue: Double): String {
    val p = String.format(Locale.ROOT, "%.3g", pValue)
    return when (verdict) {
        Verdict.FEWER_DETECTED ->
            "Fewer dropped-audio incidents were counted after than before — a difference this size is unlikely " +
                "by chance alone (p ≈ $p)."
        Verdict.MORE_DETECTED ->
            "More dropped-audio incidents were counted after than before — a difference this size is unlikely " +
                "by chance alone (p ≈ $p)."
        Verdict.WITHIN_DETECTION_LIMIT ->
            "The incidents counted before and after are within what chance alone can explain (p ≈ $p) — no " +
                "difference is detectable at this length."
        Verdict.NO_BASELINE_EVENTS ->
            "Arm A (before) counted no incidents at all, so no effect is detectable — a reduction from zero " +
                "cannot be measured, whatever Arm B counted."
    }
}

internal fun notComparableSentence(reason: NotComparable): String = when (reason) {
    NotComparable.ArmAIncomplete -> "Arm A did not reach $ARM_MINUTES minutes, so the two arms cannot be compared."
    NotComparable.ArmBIncomplete -> "Arm B did not reach $ARM_MINUTES minutes, so the two arms cannot be compared."
    NotComparable.LinkDiffers ->
        "The link ran at a different step or codec in the two arms, so their counts are not the same measurement."
    NotComparable.CadenceDiffers ->
        "The two arms were read at different intervals, so their counts are not the same measurement."
    NotComparable.DropoutsUncounted ->
        "The incident counter could not be read for part of an arm, so its count is incomplete."
    is NotComparable.ConditionChanged ->
        "${reason.condition.displayName.replaceFirstChar(Char::uppercase)} was different " +
            "${reason.where.words}, so the comparison does not hold."
    is NotComparable.MeasureNotInEffect ->
        "The condition this measure needs (${reason.condition.displayName}) was not in place during the run, " +
            "so it was not actually tested."
}

/** An arm that ended early, with the observation run's own end line (T-039/T-046) inside it. */
private fun restartLine(restart: ArmRestart): String =
    "Arm ${restart.armName} ended before $ARM_MINUTES minutes " +
        "(${endLine(restart.end, restart.observedMs).removeSuffix(".")}). Starting Arm ${restart.armName} again."

/** The top LDAC step of the link's sample-rate family: 990 kbps, or 909 on 44.1/88.2 kHz. */
private fun topStepKbps(sampleRateHz: Int?): Int =
    requireNotNull(LdacState.nominalKbps(LdacQualityMode.HIGH_QUALITY, sampleRateHz))

internal val Measure.displayName: String
    get() = when (this) {
        Measure.NO_DISCOVERY -> "No device discovery or scanning"
        Measure.NO_2_4_GHZ_WIFI -> "Wi-Fi off"
        Measure.NO_SECOND_DEVICE -> "No second Bluetooth device"
        Measure.BODY_OUT_OF_PATH -> "Clear line of sight"
        Measure.USB_CABLE_OFF -> "USB cable unplugged"
        Measure.SINK_ALLOWS_LDAC -> "Headphone set to allow LDAC"
    }

private val Measure.instruction: String
    get() = when (this) {
        Measure.NO_DISCOVERY ->
            "Close any 'pair new device' screen and turn off nearby-device search before this arm — both keep " +
                "scanning while open."
        Measure.NO_2_4_GHZ_WIFI ->
            "Turn Wi-Fi off on this phone for this arm — the band it uses competes with Bluetooth for airtime."
        Measure.NO_SECOND_DEVICE ->
            "Disconnect every other Bluetooth device from this phone, and turn off multipoint on the headphone " +
                "if it has the setting."
        Measure.BODY_OUT_OF_PATH ->
            "Keep this phone close to the headphone and your body out of the direct line between them."
        Measure.USB_CABLE_OFF -> "Unplug the USB cable and run this arm on battery."
        Measure.SINK_ALLOWS_LDAC ->
            "Check the headphone's own app or menu for a sound-quality setting, and choose the option for sound " +
                "quality rather than the one for connection priority."
    }

internal val Fallback.displayName: String
    get() = when (this) {
        Fallback.LOWER_STEP -> "Lower LDAC quality step"
        Fallback.ADAPTIVE_BITRATE -> "Adaptive Bit Rate"
        Fallback.CODEC_CHANGE -> "Switch codec away from LDAC"
        Fallback.FAMILY_44_1_KHZ -> "Switch to the 44.1/88.2 kHz family"
    }

/** Worded against the 48 kHz family, the one a phone streams; the 44.1 kHz step is the comparison. */
private fun Fallback.sentence(): String {
    val top = topStepKbps(null)
    return when (this) {
        Fallback.LOWER_STEP ->
            "Drops the bitrate by a third or two-thirds — fewer incidents because there is less to lose, not " +
                "because the link improved."
        Fallback.ADAPTIVE_BITRATE ->
            "Leaves $top kbps long before the send queue is actually full, so it avoids the incidents by not " +
                "requesting the rate that causes them."
        Fallback.CODEC_CHANGE ->
            "AAC or SBC ask for a third or less of the airtime — a different, lower-bitrate codec, not a " +
                "repaired one."
        Fallback.FAMILY_44_1_KHZ ->
            "The step below $top kbps in that family is ${topStepKbps(FORTY_FOUR_KHZ)}, about 8% less airtime " +
                "— still a lower rate, not a fix."
    }
}

private val NotComparable.Where.words: String
    get() = when (this) {
        NotComparable.Where.IN_ARM_A -> "during Arm A"
        NotComparable.Where.IN_ARM_B -> "during Arm B"
        NotComparable.Where.BETWEEN_ARMS -> "between the two arms"
    }

internal const val RESULT_TAG = "comparison-result"

internal const val PROCESS_END =
    "A comparison does not survive the app's process ending — Arm A and Arm B live only in this app's memory. " +
        "Once an arm has started, leaving this screen, switching apps, or the screen turning off does not pause " +
        "or restart it; only the process ending does, and then the comparison starts over."

/** UI_SPEC F-015, verbatim (AK-T054-1): under the progress line while an arm counts. */
internal const val ARM_KEEPS_COUNTING =
    "This keeps counting if you leave this screen, switch apps, or the screen turns off — only stopping the " +
        "app's process ends it early. Stop it here or from the notification."

/**
 * When Android refused the measurement service, the screen says so instead of the announcement
 * (AD-039 point 5). UI_SPEC names no wording; this follows the deep capture's.
 */
internal const val ARM_NEEDS_THIS_SCREEN =
    "Android did not let this comparison run in the background — keep this screen open until this arm ends."

private val ARM_MINUTES = ARM_TARGET_MS.milliseconds.inWholeMinutes

private const val FORTY_FOUR_KHZ = 44_100

