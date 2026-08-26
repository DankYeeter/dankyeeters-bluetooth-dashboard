package dev.dankyeeter.btdashboard.ui.screens.hearing

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.hearing.AudiogramRun
import dev.dankyeeter.btdashboard.hearing.CLINICAL_FREQUENCIES_HZ
import dev.dankyeeter.btdashboard.hearing.ClinicalAudiogram
import dev.dankyeeter.btdashboard.hearing.DerivedCalibration
import dev.dankyeeter.btdashboard.hearing.LowToneArtifact
import dev.dankyeeter.btdashboard.hearing.store.AudiogramStore
import dev.dankyeeter.btdashboard.hearing.fit.DeviceFormFactor
import dev.dankyeeter.btdashboard.hearing.level.VolumeGuard
import java.text.DateFormat
import java.util.Date
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
import dev.dankyeeter.btdashboard.ui.theme.ExplainedRow
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.PanelHeader
import dev.dankyeeter.btdashboard.ui.theme.Pill
import dev.dankyeeter.btdashboard.ui.theme.PillTone
import dev.dankyeeter.btdashboard.hearing.AdjustedReference

/**
 * Hearing-test flow: plain-text intro, optional fit check, a distraction-free
 * full-screen test, the result with the audiogram chart, and the run history
 * with the overlay view.
 */
@Composable
fun HearingTestScreen(viewModel: HearingTestViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (state.phase) {
        HearingPhase.INTRO -> IntroContent(state, viewModel)
        HearingPhase.FIT_CHECK, HearingPhase.TESTING -> RunningContent(state, viewModel)
        HearingPhase.RESULT -> ResultContent(state, viewModel)
        HearingPhase.HISTORY -> HistoryContent(state, viewModel)
    }
}

// --- intro ------------------------------------------------------------------

@Composable
private fun IntroContent(state: HearingUiState, viewModel: HearingTestViewModel) {
    val scroll = rememberScrollState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.startTest(runAmbientCheck = granted) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Hearing test", style = MaterialTheme.typography.displayMedium)

        Panel {
            // One sentence on the surface; the method lives behind the
            // question mark. Whoever opens this screen wants to run a test,
            // not read about Hughson and Westlake first.
            ExplainedHeader(
                "What happens",
                explanation = "A modified Hughson-Westlake pure-tone test at 250 to 8000 Hz, one " +
                    "ear at a time. The beeps step quieter and louder around your threshold. " +
                    "Some intervals are deliberately silent — pressing during those only makes " +
                    "the result worse, so press only when you actually hear something, however " +
                    "faint.",
            )
            Text(
                "Short beeps, one ear at a time — press whenever you hear one. " +
                    "Six to eight minutes.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Panel {
            ExplainedHeader(
                "Before you start",
                explanation = "Quiet matters because background noise masks exactly the tones " +
                    "being measured. Keep the listening mode you always use — ANC changes what " +
                    "reaches your ear, so a curve measured with it on fits only listening with " +
                    "it on. The volume is locked during the run so every tone keeps the level " +
                    "it was measured at. And one run carries one lapse in attention; the median " +
                    "of three outvotes it. The microphone measures room noise once before the " +
                    "run; nothing is recorded.",
            )
            Text(
                "• A quiet room\n" +
                    "• Your usual listening mode and volume\n" +
                    "• Earphones in as always, then the fit check\n" +
                    "• Three runs",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Panel {
            // Why the two chips lead to different rules is acoustics, and
            // acoustics is not what the person picking a chip is deciding. The
            // caption underneath says what it means for them; the reason is
            // behind the question mark for whoever wonders.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                ExplainedHeader(
                    "Your headphones",
                    explanation = "In-ears change their bass response completely with the seal, " +
                        "so a run with a loose tip measures the tip and not your ear — that is " +
                        "why the fit check is required for them. Over-ears are far less " +
                        "sensitive to placement, so it is optional there, though still worth " +
                        "20 seconds before a first run. The check plays two low tones and " +
                        "compares them against the baseline it stored the first time.",
                    modifier = Modifier.weight(1f),
                )
                if (state.fitCheckPassed) Pill("Fit check ✓", tone = PillTone.ACCENT)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.formFactor == DeviceFormFactor.IN_EAR,
                    onClick = { viewModel.setFormFactor(DeviceFormFactor.IN_EAR) },
                    label = { Text("In-ear / IEM") },
                )
                FilterChip(
                    selected = state.formFactor == DeviceFormFactor.OVER_EAR,
                    onClick = { viewModel.setFormFactor(DeviceFormFactor.OVER_EAR) },
                    label = { Text("Over-ear") },
                )
            }
            Text(
                if (state.formFactor.fitCheckMandatory) {
                    "Fit check required."
                } else {
                    "Fit check optional."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.message?.let { message ->
            Panel {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                // Most messages here are an aborted run, and an abort with only
                // "Dismiss" under it leaves the person who came to test a
                // hearing curve with nothing to press. The way back in is one
                // tap, so it is one button.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoldButton(
                        onClick = {
                            viewModel.dismissMessage()
                            viewModel.startTest()
                        },
                        enabled = !state.busy && !state.fitCheckRequired,
                    ) { Text("Start again") }
                    TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") }
                }
            }
        }

        Panel {
            PanelHeader("Run it")
            ExplainedRow(
                label = "Quieter test level",
                explanation = "If your points keep coming back hollow at the top of the " +
                    "chart, you hear the quietest tone the app can make at the normal " +
                    "level — the measurement is hitting its own floor, not your ears. " +
                    "This locks the run to a lower media volume, which shifts the whole " +
                    "measurable window down. Runs taken at different levels never mix " +
                    "into one curve; the newest run decides which level counts.",
            ) {
                Switch(checked = state.quietTest, onCheckedChange = viewModel::setQuietTest)
            }
            GoldOutlinedButton(
                onClick = viewModel::startFitCheck,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.fitCheckPassed) "Run fit check again" else "Run fit check") }

            GoldButton(
                onClick = {
                    if (viewModel.needsMicPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.startTest()
                    }
                },
                enabled = !state.busy && !state.fitCheckRequired,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start hearing test") }

            if (state.fitCheckRequired) {
                Pill("Run the fit check first.", tone = PillTone.WARN)
            }
        }

        ClinicalAudiogramPanel(
            state = state,
            onSave = viewModel::saveClinicalAudiogram,
            onClear = viewModel::clearClinicalAudiogram,
            onDerive = viewModel::deriveCalibration,
            onDiscardDerived = viewModel::discardDerivedCalibration,
        )

        // A button, not a panel. The panel around it had a header saying
        // "Stored runs", a large numeral, the word "runs" under the numeral and
        // then this button with the count in it again - four renderings of one
        // number, and only the button did anything.
        if (state.runs.isNotEmpty()) {
            GoldOutlinedButton(onClick = viewModel::showHistory, modifier = Modifier.fillMaxWidth()) {
                Text("Your runs (${state.runs.size})")
            }
        }
    }
}

// --- clinical audiogram -----------------------------------------------------

/**
 * Where an ENT result is entered, and the only absolute reference the app can
 * ever hold.
 *
 * One line on the surface. The reason this is worth typing in — that everything
 * else the app measures is relative and this is not — is a paragraph, so it
 * lives behind the question mark like every other paragraph on this screen.
 */
@Composable
private fun ClinicalAudiogramPanel(
    state: HearingUiState,
    onSave: (ClinicalAudiogram) -> Unit,
    onClear: () -> Unit,
    onDerive: () -> Unit,
    onDiscardDerived: () -> Unit,
) {
    val clinical = state.clinicalAudiogram
    var editing by rememberSaveable { mutableStateOf(false) }

    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            ExplainedHeader(
                "Clinical audiogram",
                explanation = "An audiometer at a practice is calibrated: 0 dB HL is the " +
                    "average threshold of young, normally hearing ears, and anything up to " +
                    "20 dB HL is read as normal. That makes it an absolute measurement. " +
                    "Everything this app measures itself is not — the tones go through your " +
                    "headphones at whatever volume you had set, so the numbers are only " +
                    "meaningful next to each other, and the app can honestly describe the " +
                    "shape of your hearing but never its level.\n\n" +
                    "Entering the clinic's values gives the app that missing level. It draws " +
                    "them over your own curve so the two shapes can be compared, it can tell " +
                    "you when a self-test result contradicts them, and the equaliser can be " +
                    "built from them instead of from the headphone measurement.\n\n" +
                    "Nothing leaves the phone, and nothing here is a diagnosis — it is the " +
                    "clinic's reading, stored as you type it.",
                modifier = Modifier.weight(1f),
            )
            if (clinical != null) Pill("stored", tone = PillTone.ACCENT)
        }
        Text(
            "From an ENT hearing test — the calibrated reference this app cannot " +
                "measure itself.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (clinical != null) {
            Text(
                listOfNotNull(
                    clinical.source.takeIf { it.isNotBlank() },
                    clinical.measuredOn.takeIf { it.isNotBlank() },
                    "${clinical.leftDbHl.size} left · ${clinical.rightDbHl.size} right",
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Said here because it is the one thing a reader of a *normal*
            // audiogram most needs to hear, and the EQ screen is where the
            // consequence shows up rather than where the values live.
            if (clinical.withinNormalLimits) {
                Text(
                    "All values are inside normal limits — there is no loss to correct.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        GoldOutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (clinical == null) "Enter clinical audiogram" else "Edit clinical audiogram")
        }

        // Only once both halves of the transfer exist. Offering it earlier would
        // be offering a button whose only possible outcome is an explanation of
        // why it cannot work.
        if (state.canDeriveCalibration || state.derivedCalibration != null) {
            CalibrationTransferSection(
                derived = state.derivedCalibration,
                canDerive = state.canDeriveCalibration,
                onDerive = onDerive,
                onDiscard = onDiscardDerived,
            )
        }
    }

    if (editing) {
        ClinicalAudiogramDialog(
            existing = clinical,
            onSave = {
                editing = false
                onSave(it)
            },
            onClear = {
                editing = false
                onClear()
            },
            onDismiss = { editing = false },
        )
    }
}

/**
 * The calibration transfer, inside the clinical panel because that is the half
 * of it a user has to go and fetch.
 *
 * Two sentences on the surface, and they are the whole idea: the same ears were
 * measured twice, so whatever is left over after subtracting one from the other
 * is the headphone. The arithmetic and the caveats live behind the question
 * mark, as everywhere else on this screen.
 */
@Composable
private fun CalibrationTransferSection(
    derived: DerivedCalibration?,
    canDerive: Boolean,
    onDerive: () -> Unit,
    onDiscard: () -> Unit,
) {
    ExplainedHeader(
        "Headphone calibration from your audiogram",
        explanation = "The clinic measured your ears on calibrated equipment; this app " +
            "measured the same ears through your headphones. Subtract one from the other " +
            "and the ears cancel out — what is left is the headphone's own frequency " +
            "response, measured at your ear rather than on a laboratory rig.\n\n" +
            "The overall level is thrown away, because it depends on the volume the test " +
            "ran at and means nothing. What is kept is the shape, which is exactly what a " +
            "calibration preset is.\n\n" +
            "Two honest limits. This describes these headphones on your ears — your " +
            "seal, your ear canals, the way you wore them that day — so it is better than " +
            "any published average for you, and useless to anybody else. And it is only as " +
            "good as the two measurements behind it: a run with a loose fit or a mistyped " +
            "value from the form goes straight into the result, which is why the app names " +
            "the disagreements it can see instead of quietly averaging them away.",
    )
    Text(
        "Both measurements are of the same ears, so their difference is your headphones.",
        style = MaterialTheme.typography.bodyMedium,
    )

    if (derived != null) {
        Text(
            "Derived for ${derived.displayDeviceName} · " +
                DateFormat.getDateInstance(DateFormat.MEDIUM)
                    .format(Date(derived.createdAtMillis)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Kept on screen rather than shown once when it was derived. A caveat
        // that decides how much to trust a preset has to be readable at the
        // moment somebody wonders about the preset, not only at the moment it
        // was made.
        derived.warnings.forEach { warning ->
            Text(
                warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    GoldOutlinedButton(
        onClick = onDerive,
        enabled = canDerive,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (derived == null) "Derive headphone calibration" else "Derive again")
    }
    if (derived != null) {
        TextButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
            Text("Discard")
        }
    }
}

/**
 * The editor: two columns, one row per frequency on a standard ENT form.
 *
 * Every field may stay blank, because practices routinely leave 125 Hz and the
 * inter-octaves off the form, and a blank has to remain a blank — filling it in
 * as 0 dB HL would record perfect hearing at a frequency nobody tested. That is
 * why the values are kept as text here and only parsed on save.
 *
 * The frequency list is the clinical one, not [dev.dankyeeter.btdashboard.hearing.TEST_FREQUENCIES_HZ]:
 * this is a transcription of a document, so it has to have a line for every line
 * on the document, and a form with 750 Hz filled in would otherwise lose it.
 */
@Composable
private fun ClinicalAudiogramDialog(
    existing: ClinicalAudiogram?,
    onSave: (ClinicalAudiogram) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val left = remember(existing) {
        mutableStateMapOf<Int, String>().apply {
            existing?.leftDbHl?.forEach { (hz, db) -> put(hz, db.asEntryText()) }
        }
    }
    val right = remember(existing) {
        mutableStateMapOf<Int, String>().apply {
            existing?.rightDbHl?.forEach { (hz, db) -> put(hz, db.asEntryText()) }
        }
    }
    var measuredOn by remember(existing) { mutableStateOf(existing?.measuredOn.orEmpty()) }
    var source by remember(existing) { mutableStateOf(existing?.source.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clinical audiogram") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Thresholds in dB HL, exactly as printed on the form. Leave a " +
                        "frequency blank if it was not tested.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Hz", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp))
                    Text("Left", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text("Right", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                }
                CLINICAL_FREQUENCIES_HZ.forEach { hz ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            formatHz(hz),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(56.dp),
                        )
                        DbHlField(left[hz].orEmpty(), { left[hz] = it }, Modifier.weight(1f))
                        DbHlField(right[hz].orEmpty(), { right[hz] = it }, Modifier.weight(1f))
                    }
                }
                OutlinedTextField(
                    value = measuredOn,
                    onValueChange = { measuredOn = it },
                    label = { Text("Date on the form") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text("Where it came from") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        ClinicalAudiogram(
                            leftDbHl = left.parsedDbHl(),
                            rightDbHl = right.parsedDbHl(),
                            measuredOn = measuredOn.trim(),
                            source = source.trim(),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing != null) TextButton(onClick = onClear) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun DbHlField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        // Signed, because an ENT form can print −5 or −10 dB HL: hearing better
        // than the young-normal median is ordinary, and a keyboard that cannot
        // type the minus sign would quietly turn it into a 5 dB loss.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        placeholder = { Text("—", style = MaterialTheme.typography.bodySmall) },
        modifier = modifier,
    )
}

/**
 * Text to stored values: unparseable and out-of-range entries are dropped
 * rather than coerced.
 *
 * Coercing would store a number the form does not contain. The audiometric
 * range runs from about −10 dB HL (better than the young-normal median, which
 * is perfectly ordinary) to the 120 dB HL limit of the equipment, so anything
 * outside that is a typo, and a typo is better lost than recorded as a finding.
 */
private fun Map<Int, String>.parsedDbHl(): Map<Int, Double> = mapNotNull { (hz, text) ->
    text.trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it in MIN_DB_HL..MAX_DB_HL }
        ?.let { hz to it }
}.toMap()

/** 10.0 -> "10", 12.5 -> "12.5": audiometric steps are whole numbers. */
private fun Double.asEntryText(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()

private const val MIN_DB_HL = -10.0
private const val MAX_DB_HL = 120.0

// --- running ----------------------------------------------------------------

/**
 * Distraction-free: black background, no navigation, one large response button.
 * The screen deliberately shows neither the current level nor the frequency —
 * that would bias the listener into pressing when a tone is "due".
 *
 * Deliberately outside the app's panel/metal design system, and the only screen
 * that is. Panels, accents and gradients all exist to be looked at; here the
 * listener is supposed to be listening, and a gold-rimmed surface in the corner
 * of the eye is a distraction with a measurable cost — a missed faint tone is a
 * wrong threshold. Black ground, white type, one circular button.
 */
@Composable
private fun RunningContent(state: HearingUiState, viewModel: HearingTestViewModel) {
    BackHandler { viewModel.cancelRun() }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val ear = state.presenting?.ear
                Text(
                    when (state.phase) {
                        HearingPhase.FIT_CHECK -> "Fit check"
                        else -> when (ear) {
                            Ear.LEFT -> "Left ear"
                            Ear.RIGHT -> "Right ear"
                            null -> "Getting ready"
                        }
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Text(
                    "Press when you hear a beep — even a very faint one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
                val presenting = state.presenting
                if (presenting != null) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // A full run is ten minutes of near-identical screens. Without
                    // a moving number there is no way to tell it apart from a
                    // frozen one — which is exactly how the first build read.
                    // The frequency is safe to show; the level is not, because
                    // knowing it would bias the answer.
                    Text(
                        "Tone ${presenting.frequencyIndex + 1} of ${presenting.frequencyCount}" +
                            " · ${formatHz(presenting.frequencyHz)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                } else {
                    CircularProgressIndicator()
                    Text(
                        "Getting the audio stream ready…",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            Button(
                onClick = viewModel::onUserResponse,
                shape = CircleShape,
                modifier = Modifier.size(220.dp),
            ) {
                Text("I hear it", style = MaterialTheme.typography.displayMedium)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Fades in, fades out, asks nothing. A message that has to be
                // dismissed turns a keypress into a decision, and this one has
                // nothing to decide - it only explains why the volume did not
                // move.
                AnimatedVisibility(
                    visible = state.volumeLockedNotice,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Text(
                        "Volume is locked during the test.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // Named for what it does once tones are running: there is no
                // pause and no resume, so eight minutes of answers go with it.
                // "Cancel test" read like backing out of something that had not
                // started yet.
                TextButton(onClick = viewModel::cancelRun) {
                    Text(
                        if (state.presenting != null) "Stop and discard this run" else "Cancel",
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

// --- result -----------------------------------------------------------------

@Composable
private fun ResultContent(state: HearingUiState, viewModel: HearingTestViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Run finished", style = MaterialTheme.typography.displayMedium)

        state.message?.let { message ->
            Panel {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                // The message says the run is probably too good to trust. Both
                // answers to that are one tap, and neither used to exist here:
                // the run could not be deleted from this screen and the message
                // could not be got rid of at all.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.lastRun?.let { lastRun ->
                        GoldButton(
                            onClick = {
                                viewModel.deleteRun(lastRun.id)
                                viewModel.backToIntro()
                            },
                        ) { Text("Discard this run") }
                    }
                    TextButton(onClick = viewModel::dismissMessage) { Text("Keep it") }
                }
            }
        }

        // Only drawn when there is something in it: an empty panel with a
        // header is a promise of information the screen does not have.
        if (state.lastReliability != null || state.lastRun?.ambientNoiseDbA != null) {
            Panel {
                // Both numbers in here come with a caveat that decides how much
                // weight to give them, and both caveats are a paragraph. The
                // panel shows the figures; the paragraph is one tap away.
                ExplainedHeader(
                    "This run",
                    explanation = "Some intervals during the test are deliberately silent. A " +
                        "press during one of those is a false positive, and a run with several " +
                        "of them has thresholds that look better than your hearing is. The " +
                        "room-noise figure comes from the phone's microphone, which is not a " +
                        "calibrated meter — it is good enough to tell a quiet room from a noisy " +
                        "one and nothing finer.",
                )
                state.lastReliability?.let {
                    Text(it.summary, style = MaterialTheme.typography.bodySmall)
                }
                state.lastRun?.ambientNoiseDbA?.let {
                    Text(
                        "Room noise: roughly ${it.toInt()} dB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        state.lowToneArtifact?.let { LowToneNotice(it) }

        Panel {
            ExplainedHeader(
                "Your hearing",
                explanation = "Each point is the quietest level you still answered at that " +
                    "frequency. A hollow point is one the test could not pin down: the tone was " +
                    "still inaudible at the loudest level the app allows, or still audible at " +
                    "the quietest. Both ends are limits of the measurement, not of your ears." +
                    if (state.clinicalAudiogram != null) {
                        "\n\nThe dotted curve is your clinical audiogram. Both curves are drawn " +
                            "against their own average, because the app's scale and the clinic's " +
                            "dB HL cannot be lined up without a measurement microphone — so what " +
                            "you can compare here is the shape, never the height."
                    } else {
                        ""
                    },
            )
            AudiogramChart(
                runs = state.runs,
                active = state.audiogram,
                clinical = state.clinicalAudiogram,
            )
            AudiogramLegend(showClinical = state.clinicalAudiogram != null)

            // Counted the way the curve is actually built, not by counting
            // every run on the phone.
            //
            // This line used to read state.runs.size, which is every run ever
            // stored for every headphone - so a phone with six runs from two
            // devices announced "6 runs stored — the thick curve is the median"
            // over a curve drawn from three of them, or from none at all when
            // they all belonged to a headphone that was not connected. The
            // number the user is promised has to be the number in the curve.
            val counted = remember(state.runs, state.selectedRunIds, state.currentDeviceKey) {
                AudiogramStore.selectionOf(state.runs, state.selectedRunIds, state.currentDeviceKey).size
            }
            Text(
                when (counted) {
                    0 -> "No run from this headphone counts yet."
                    1 -> "One run in the curve. Two more make the median meaningful."
                    2 -> "Two runs in the curve. One more and it is on solid ground."
                    else -> "Three runs in the curve — the thick line is the per-frequency median."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            state.audiogram?.let { audiogram ->
                val hollow = (audiogram.left + audiogram.right).count { !it.converged }
                if (hollow > 0) {
                    // What is on screen, and nothing about why - the why is in
                    // the header's explanation, where it is read once rather
                    // than after every run.
                    Text(
                        if (hollow == 1) {
                            "One point is drawn hollow."
                        } else {
                            "$hollow points are drawn hollow."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        GoldButton(onClick = viewModel::backToIntro, modifier = Modifier.fillMaxWidth()) {
            Text("Run another test")
        }
        GoldOutlinedButton(onClick = viewModel::showHistory, modifier = Modifier.fillMaxWidth()) {
            Text("Manage runs")
        }
    }
}

/**
 * The advisory for raised low tones, and the one line it comes down to.
 *
 * Not an error and not framed as one: nothing has gone wrong with the app, and
 * the run is kept. It is a reading aid for a curve whose most eye-catching
 * feature is very likely not about the person's ears at all. The reasoning —
 * why a leak and a noisy room both cost bass, and what a contradicting clinical
 * audiogram proves — is behind the question mark, because it is a paragraph and
 * the useful part is one sentence.
 *
 * The wording never says the lows *are* an artifact. "Usually" is doing real
 * work there: a genuine low-frequency loss exists, and this rule cannot rule it
 * out, only report that the odds favour the boring explanation.
 */
@Composable
private fun LowToneNotice(advice: LowToneArtifact.Advice) {
    Panel {
        ExplainedHeader(
            "About the low tones in this run",
            explanation = buildString {
                append(
                    "Both of the things that go wrong in a home test hit the bottom of " +
                        "the range hardest. A seal that leaks — a tip that has worked loose, " +
                        "glasses under an earpad — lets bass escape, so the low tones need to " +
                        "be louder before you hear them. And room noise is bass-heavy almost " +
                        "everywhere: traffic, ventilation, the building itself. Either one " +
                        "raises 250 and 500 Hz while leaving the middle of the range alone, " +
                        "which looks exactly like a low-frequency hearing loss.",
                )
                if (advice.clinicalContradicts) {
                    append(
                        "\n\nYour clinical audiogram is flat and normal at those same " +
                            "frequencies, measured on calibrated equipment. Same ears, so the " +
                            "raised lows in this run came from the headphones or the room, not " +
                            "from your hearing. This is the classic pattern: a headphone app " +
                            "shows a low-frequency dip that the clinic does not.",
                    )
                }
                if (advice.roomWasNoisy) {
                    append(
                        "\n\nThe microphone measured this room as loud before the run. That is " +
                            "an uncalibrated estimate, but loud enough to mask the quiet low " +
                            "tones the test was asking about.",
                    )
                }
                append(
                    "\n\nWorth a retake: reseat the earphones, find a quieter room, and see " +
                        "whether the lows move. If they do, they were never yours.",
                )
            },
        )
        Text(
            "Raised low tones in a headphone test usually mean seal leakage or room " +
                "noise, not your ears.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// --- history ----------------------------------------------------------------

@Composable
private fun HistoryContent(state: HearingUiState, viewModel: HearingTestViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Your runs", style = MaterialTheme.typography.displayMedium)

        Panel {
            ExplainedHeader(
                "All runs overlaid",
                // The "one run carries a lapse in attention, three outvote it"
                // half of this already stands on the intro screen, under
                // "Before you start". Said twice it is not twice as convincing;
                // what is left here is the part only this screen answers, which
                // is why the cap is three and not more.
                explanation = "Every run is drawn thin; the thick curve is the median of the " +
                    "runs you chose, and that median is what the equaliser corrects for. " +
                    "Three at most — averaging a dozen sessions from different weeks would " +
                    "blur the very change you would want to see. Swap which three count as " +
                    "often as you like; nothing is lost by trying.",
            )
            Text(
                "The thick curve is the median of the runs you chose — up to three count.",
                style = MaterialTheme.typography.bodyMedium,
            )
            AudiogramChart(
                runs = state.runs,
                active = state.audiogram,
                clinical = state.clinicalAudiogram,
            )
            AudiogramLegend(showClinical = state.clinicalAudiogram != null)
        }

        // Which runs count, resolved the same way the store resolves it: an
        // empty selection means the three newest, so the screen never shows a
        // different set than the equaliser is using — and only runs measured
        // through the connected headphone are in the running at all.
        val counted = remember(state.runs, state.selectedRunIds, state.currentDeviceKey) {
            AudiogramStore.selectionOf(state.runs, state.selectedRunIds, state.currentDeviceKey)
                .map { it.id }.toSet()
        }
        val full = counted.size >= AudiogramStore.MAX_SELECTED

        // The level the selection is currently working at, straight from the
        // rule that decides it. A run at any other level cannot join the curve,
        // and until this was drawn the screen gave that rule no sign at all —
        // the row looked ordinary and the switch simply refused.
        val currentVolume = remember(state.runs, state.currentDeviceKey) {
            AudiogramStore.currentVolumeFor(state.runs, state.currentDeviceKey)
        }

        state.runs.sortedByDescending { it.timestampMillis }.forEach { run ->
            // A run from another headphone is shown but cannot be chosen: the
            // curve it holds corrects for a driver that is not on the head
            // right now. It comes back to life the moment its device does.
            val foreign = run.deviceAddressHash != null &&
                state.currentDeviceKey != null &&
                run.deviceAddressHash != state.currentDeviceKey
            // Same idea one axis over: thresholds in dBFS describe the window
            // the media volume put them in, so a run from another level is on
            // the bench until a run is taken at its level again. Only asked of
            // runs that pass the device test — a foreign run has already lost,
            // and "Other device" is the more useful thing to say about it.
            val benched = !foreign && currentVolume != null &&
                !AudiogramStore.isSameVolume(run.volumeFraction, currentVolume)
            RunRow(
                run = run,
                counted = run.id in counted,
                // A fourth is refused rather than pushing one of the others
                // out: which three count decides what the EQ does, and a set
                // that rearranges itself behind your back is not a choice.
                selectable = !foreign && !benched && (run.id in counted || !full),
                foreign = foreign,
                benched = benched,
                onCountedChange = { viewModel.setRunSelected(run.id, it) },
                onDelete = { viewModel.deleteRun(run.id) },
            )
        }
        if (full) {
            Text(
                "Three runs are in use — take one out to put another in.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.runs.isNotEmpty()) {
            // Asked first, because this one takes more than the runs with it.
            //
            // Every other destructive control here removes a single run, which
            // is a twenty-minute mistake at worst. This one also takes the
            // generated compensation curve, which cannot be rebuilt without
            // measuring three runs again - and it used to fire on the first
            // tap, one thumb-width from the Back button.
            var confirming by rememberSaveable { mutableStateOf(false) }

            TextButton(onClick = { confirming = true }) { Text("Delete all runs") }

            if (confirming) {
                AlertDialog(
                    onDismissRequest = { confirming = false },
                    title = { Text("Delete all runs?") },
                    text = {
                        Text(
                            "Every stored run is removed, and the generated " +
                                "${AdjustedReference.NAME} curve goes with them. Saved EQ " +
                                "presets keep the bands they already have.",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirming = false
                                viewModel.deleteAllRuns()
                            },
                        ) { Text("Delete all") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirming = false }) { Text("Cancel") }
                    },
                )
            }
        }
        GoldButton(onClick = viewModel::backToIntro, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun RunRow(
    run: AudiogramRun,
    counted: Boolean,
    selectable: Boolean,
    foreign: Boolean,
    benched: Boolean,
    onCountedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    // One run per panel, with its timestamp as the eyebrow: the date is what
    // tells two runs apart, and everything else on the row is about that date.
    // A run that cannot join the curve fades as a whole — the pill alone reads
    // as state, the fade reads as "not yours right now", which is the actual
    // situation. Both reasons it can happen, wrong device and wrong level, look
    // the same to the person: the switch will not move. So they look the same
    // here, and only the pill says which.
    val eligible = !foreign && !benched
    Panel(contentPadding = 16, modifier = Modifier.alpha(if (eligible) 1f else 0.5f)) {
        PanelHeader(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(run.timestampMillis)),
            trailing = {
                Pill(
                    when {
                        foreign -> "Other device"
                        benched -> "Other level"
                        counted -> "In the curve"
                        else -> "Not used"
                    },
                    tone = if (counted && eligible) PillTone.ACCENT else PillTone.NEUTRAL,
                )
            },
        )
        Text(
            run.deviceName ?: "No device recorded",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Use for the curve",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = counted,
                onCheckedChange = onCountedChange,
                enabled = selectable,
            )
        }
        // Delete sits beside the counts rather than in the header's trailing
        // slot: that slot is sized for a pill, and a full button next to an
        // 11 sp eyebrow makes the header taller than the row it labels.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The point counts are gone: every run measures the same eight
            // frequencies per ear, so "8 left / 8 right points" was printed on
            // every row of every list and told nobody anything. The room noise
            // genuinely differs between runs, which is why it is what remains.
            Text(
                listOfNotNull(
                    run.ambientNoiseDbA?.let { "Room ≈ ${it.toInt()} dB" },
                    // Only worth a word when it differs from the standard test
                    // level: a bench-sitting run at another level should say
                    // why. Compared against the constant the run was recorded
                    // from, and with the same tolerance the selection uses —
                    // a row claiming "Quiet level" about a run the curve counts
                    // as standard would be the two disagreeing on screen.
                    "Quiet level (${(run.volumeFraction * 100).toInt()} %)".takeIf {
                        !AudiogramStore.isSameVolume(
                            run.volumeFraction,
                            VolumeGuard.TEST_VOLUME_FRACTION,
                        )
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GoldOutlinedButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

/** 1000 -> "1 kHz", 250 -> "250 Hz". Locale-free: the app is English-only. */
private fun formatHz(hz: Int): String =
    if (hz >= 1000 && hz % 1000 == 0) "${hz / 1000} kHz" else "$hz Hz"
