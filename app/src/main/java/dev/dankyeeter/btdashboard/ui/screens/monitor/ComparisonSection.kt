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
import dev.dankyeeter.btdashboard.monitor.link.live.LdacQualityMode
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.RunEnd
import dev.dankyeeter.btdashboard.monitor.link.live.hasReadableRate
import dev.dankyeeter.btdashboard.monitor.optimize.ARM_TARGET_MS
import dev.dankyeeter.btdashboard.monitor.optimize.Arm
import dev.dankyeeter.btdashboard.monitor.optimize.ComparisonResult
import dev.dankyeeter.btdashboard.monitor.optimize.Condition
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionBook
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
    onReset: () -> Unit,
    restoreButton: @Composable () -> Unit,
) {
    val topKbps = topStepKbps(snapshot.codec?.sampleRateHz)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Compare a change, before and after", style = MaterialTheme.typography.titleSmall)
        when (state.phase) {
            ComparisonPhase.CHOOSE -> Choose(snapshot, state, topKbps, onMeasure, onCompare)
            ComparisonPhase.PIN_AND_CHECK -> PinAndCheck(state, topKbps, onCompare, onReset)
            ComparisonPhase.ARM_A, ComparisonPhase.ARM_B -> ArmProgress(state, onReset, restoreButton)
            ComparisonPhase.INSTRUCT -> Instruct(state, onCheck, onReset, restoreButton)
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
private fun ArmProgress(state: ComparisonUi, onCancel: () -> Unit, restoreButton: @Composable () -> Unit) {
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
        frameLines(armA, armB).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        boundaryLines(snapshot, armA).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(PROCESS_END, style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = onNew) { Text("New comparison") }
    restoreButton()
}

/** The measurement frame (AK-14): durations, step, cadence, book, what was not checked, drift, scope, limit. */
private fun frameLines(armA: Arm, armB: Arm): List<String> {
    val link = armA.run.link
    val step = LdacQuality.chipLabel(LdacQuality.codeOf(link?.mode), link?.sampleRateHz)
    val cadences = (armA.run.cadencesMs + armB.run.cadencesMs).sorted().joinToString(" or ") { formatSpan(it) }
    val books = listOf(armA.start, armA.end, armB.start, armB.end)
    val notChecked = Condition.entries.filter { c -> books.any { it[c] !is ConditionValue.Read } }
    return buildList {
        add("Arm A ran for ${formatSpan(armA.run.observedMs)}, Arm B for ${formatSpan(armB.run.observedMs)}.")
        add("Both pinned to $step, ${link?.codec?.displayName ?: "codec not read"}.")
        add("Read every $cadences.")
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

/** AK-16 (AD-036): the app names the limit of its advice, beside the facts it could read. */
private fun boundaryLines(snapshot: LinkLiveSnapshot, armA: Arm): List<String> {
    val threeMbps = when (snapshot.pairing?.threeMbps) {
        true -> "yes"
        false -> "no"
        null -> "not readable"
    }
    val mtu = snapshot.ldac?.stack?.effectiveMtu?.toString() ?: "not readable"
    return listOf(
        "Whether this pairing can carry ${topStepKbps(armA.run.link?.sampleRateHz)} kbps at all is not something " +
            "this phone can determine — that needs the packet type, and it is not readable without BQR access " +
            "this app does not have.",
        "3 Mbps EDR: $threeMbps — read on this pairing.",
        "Effective MTU: $mtu — read on this pairing.",
        "Packet type and retransmission rate: not readable without BQR.",
    )
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

/** The read-back words (AD-035): Wi-Fi "on" is never a contradiction, only not checkable. */
internal fun readBackSentence(measure: Measure?, book: ConditionBook): String {
    val condition = measure?.condition ?: return "Not checkable from this phone."
    val value = book[condition]
    if (value is ConditionValue.Unreadable) return "Not checkable: ${redactAddresses(value.reason)}."
    return when (measure.verify(value) to condition) {
        Verification.VERIFIED to Condition.WIFI_RADIO -> "Confirmed: Wi-Fi is off on this phone."
        Verification.VERIFIED to Condition.OTHER_ACL_LINKS -> "Confirmed: no other Bluetooth device is connected."
        Verification.VERIFIED to Condition.USB_POWER -> "Confirmed: the phone is running on battery."
        Verification.CONTRADICTED to Condition.OTHER_ACL_LINKS ->
            "Contradicts what this run needs: ${book.shown(condition)} other Bluetooth device(s) " +
                "are still connected."
        Verification.CONTRADICTED to Condition.USB_POWER ->
            "Contradicts what this run needs: the phone is still receiving power."
        Verification.NOT_VERIFIABLE to Condition.WIFI_RADIO ->
            "Not checkable: Wi-Fi being on here does not say which band it used."
        else -> "Not checkable from this phone."
    }
}

/** An arm that ended early, with the observation run's own end line (T-039/T-046) inside it. */
private fun restartLine(restart: ArmRestart): String =
    "Arm ${restart.armName} ended before $ARM_MINUTES minutes " +
        "(${endLine(restart.end, restart.observedMs).removeSuffix(".")}). Starting Arm ${restart.armName} again."

/** The top LDAC step of the link's sample-rate family: 990 kbps, or 909 on 44.1/88.2 kHz. */
private fun topStepKbps(sampleRateHz: Int?): Int =
    requireNotNull(LdacState.nominalKbps(LdacQualityMode.HIGH_QUALITY, sampleRateHz))

private fun ConditionBook.shown(condition: Condition): String =
    (this[condition] as? ConditionValue.Read)?.value ?: "not checkable"

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

private val Condition.displayName: String
    get() = when (this) {
        Condition.WIFI_RADIO -> "Wi-Fi power"
        Condition.WIFI_SCAN_ALWAYS -> "Wi-Fi scanning"
        Condition.OTHER_ACL_LINKS -> "another Bluetooth device"
        Condition.USB_POWER -> "USB power"
        Condition.DISCOVERY_SEEN -> "device discovery"
    }

private val NotComparable.Where.words: String
    get() = when (this) {
        NotComparable.Where.IN_ARM_A -> "during Arm A"
        NotComparable.Where.IN_ARM_B -> "during Arm B"
        NotComparable.Where.BETWEEN_ARMS -> "between the two arms"
    }

internal const val RESULT_TAG = "comparison-result"

internal const val PROCESS_END =
    "A comparison does not survive the app's process ending — Arm A lives only in this app's memory. Leaving " +
        "this screen or the app keeps it; being killed by Android between arms loses it, and the comparison " +
        "starts over."

private val ARM_MINUTES = ARM_TARGET_MS.milliseconds.inWholeMinutes

private const val FORTY_FOUR_KHZ = 44_100

