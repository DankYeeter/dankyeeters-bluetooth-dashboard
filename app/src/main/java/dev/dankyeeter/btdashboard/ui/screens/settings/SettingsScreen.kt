package dev.dankyeeter.btdashboard.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.privileged.PrivilegedConnection
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.attach.AttachmentStatus
import dev.dankyeeter.btdashboard.system.persist.AccentChoice
import dev.dankyeeter.btdashboard.system.persist.AppearanceChoice
import dev.dankyeeter.btdashboard.transfer.BackupSchema
import dev.dankyeeter.btdashboard.ui.common.describe
import dev.dankyeeter.btdashboard.ui.common.pill
import dev.dankyeeter.btdashboard.ui.common.tone
import dev.dankyeeter.btdashboard.ui.screens.dashboard.DashboardViewModel
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
import dev.dankyeeter.btdashboard.ui.theme.ExplainedRow
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton
import dev.dankyeeter.btdashboard.ui.theme.MetalPalette
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.PanelDivider
import dev.dankyeeter.btdashboard.ui.theme.PanelHeader
import dev.dankyeeter.btdashboard.ui.theme.Pill
import dev.dankyeeter.btdashboard.ui.theme.PillTone

/**
 * Everything that is about the app rather than about the headphone: getting
 * set up, moving data between phones, how the thing works, and how it looks.
 *
 * Setup used to nag from the front screen. It belongs here: a first-run task
 * has no business occupying the screen the user opens every day afterwards.
 */
@Composable
fun SettingsScreen(
    onOpenWizard: () -> Unit = {},
    onOpenOnboarding: () -> Unit = {},
    onOpenEq: () -> Unit = {},
    appearanceViewModel: SettingsViewModel = viewModel(),
    dashboardViewModel: DashboardViewModel = viewModel(),
) {
    val appearance by appearanceViewModel.appearance.collectAsStateWithLifecycle()
    val accentArgb by appearanceViewModel.accentArgb.collectAsStateWithLifecycle()
    val attachment by SystemGraph.eqController.status.collectAsStateWithLifecycle()
    val helper by PrivilegedConnection.service.collectAsStateWithLifecycle()
    val setupSummary by dashboardViewModel.setupSummary.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Setup steps can be satisfied outside the app (a Settings toggle, an ADB
    // command), so the status is re-read every time this screen comes back.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) dashboardViewModel.refreshSetupStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.displayMedium)

        SetupPanel(setupSummary, onOpenWizard)
        SystemAccessPanel(
            helperRunning = helper != null,
            attachment = attachment,
            onOpenOnboarding = onOpenOnboarding,
            onOpenEq = onOpenEq,
        )
        AppearancePanel(appearance, appearanceViewModel::setAppearance)
        AccentPanel(
            accentArgb = accentArgb,
            active = appearance == AppearanceChoice.EDGY,
            onSelectPreset = appearanceViewModel::setAccent,
            onSelectArgb = appearanceViewModel::setAccentArgb,
        )
        BackupPanel(dashboardViewModel)
        HowToPanel()
        AboutPanel()
    }
}

@Composable
private fun SetupPanel(summary: String?, onOpenWizard: () -> Unit) {
    Panel {
        PanelHeader(
            "Setup",
            trailing = {
                Pill(
                    if (summary == null) "Complete" else "Incomplete",
                    tone = if (summary == null) PillTone.ACCENT else PillTone.WARN,
                )
            },
        )
        Text(
            summary ?: "Setup complete — every step is either granted or skipped.",
            style = MaterialTheme.typography.bodyMedium,
        )
        // "Review" and "Finish" say what is left to do; "Run setup again" said
        // what the button does to the app, which is the one thing the user
        // already knows from the panel it sits in.
        GoldButton(onClick = onOpenWizard) {
            Text(if (summary == null) "Review setup" else "Finish setup")
        }
    }
}

/**
 * The shell and what the EQ actually managed to do with it.
 *
 * Two separate rows on purpose. The helper being up does not imply the EQ
 * went global, and saying "system access: OK" would be exactly the kind of
 * summary that hides which of the two is the one that failed.
 */
@Composable
private fun SystemAccessPanel(
    helperRunning: Boolean,
    attachment: AttachmentStatus,
    onOpenOnboarding: () -> Unit,
    onOpenEq: () -> Unit,
) {
    Panel {
        PanelHeader("System access")

        StatusRow(
            label = "App helper",
            pill = if (helperRunning) "Running" else "Not running",
            tone = if (helperRunning) PillTone.ACCENT else PillTone.WARN,
        )

        PanelDivider()

        StatusRow(
            label = "EQ attachment",
            pill = attachment.pill(),
            tone = attachment.tone(),
        )
        Text(
            attachment.describe(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Only where there is something to do about it. Global and session
        // attachment are the EQ working; the other two states are the user
        // wanting the screen that can switch it on or say why it failed, and
        // reading the state here without a way through is a dead end.
        if (attachment is AttachmentStatus.Unavailable ||
            attachment is AttachmentStatus.Inactive
        ) {
            GoldOutlinedButton(onClick = onOpenEq) { Text("Open the EQ screen") }
        }

        PanelDivider()

        // Stated because it is the one way the EQ can be off with no visible
        // cause: "Force stop" puts the app in Android's stopped state, and a
        // stopped app receives no broadcasts at all — not the boot one, not a
        // headphone connecting. Nothing the app can do about it from inside,
        // and nothing the user can guess either. The consequence is the first
        // layer; the mechanism only matters once it has bitten.
        ExplainedRow(
            label = "The equalizer keeps running after you close the app.",
            explanation = "It runs as a background service. \"Force stop\" in Android's " +
                "app settings ends it, and after that Android delivers this app no " +
                "events at all until you open it once.",
            control = {},
        )

        // Not "System access" again — a button labelled after the panel it
        // sits in says nothing about what tapping it does.
        GoldOutlinedButton(onClick = onOpenOnboarding) { Text("Open system access") }
    }
}

/** Label on the left, live state on the right. The app's standard status line. */
@Composable
private fun StatusRow(label: String, pill: String, tone: PillTone) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Pill(pill, tone = tone)
    }
}

@Composable
private fun AppearancePanel(
    appearance: AppearanceChoice,
    onSelect: (AppearanceChoice) -> Unit,
) {
    Panel {
        PanelHeader("Appearance")
        Text(
            "Applies immediately and is remembered across restarts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppearanceChoice.entries.forEach { choice ->
            ThemeRow(
                choice = choice,
                selected = choice == appearance,
                onSelect = { onSelect(choice) },
            )
        }
    }
}

/**
 * The metal the Edgy theme is cast in.
 *
 * Every entry is one colour; the six gradient stops are derived from it by
 * [MetalPalette], so a new accent gets the same metallic treatment as the
 * hand-tuned gold rather than a flat tint.
 *
 * Shown even when Edgy is not the active theme, but plainly marked as
 * inactive: hiding it entirely would make the setting appear and disappear as
 * the user tries themes, which reads like a bug.
 */
@Composable
private fun AccentPanel(
    accentArgb: Long,
    active: Boolean,
    onSelectPreset: (AccentChoice) -> Unit,
    onSelectArgb: (Long) -> Unit,
) {
    Panel {
        PanelHeader(
            "Accent metal",
            trailing = {
                Pill(
                    if (active) "Painting" else "Inactive",
                    tone = if (active) PillTone.ACCENT else PillTone.NEUTRAL,
                )
            },
        )
        Text(
            if (active) {
                "Every gradient, border and readout is derived from this one colour."
            } else {
                "Only the Edgy theme paints metal — the other themes take their accent " +
                    "from your wallpaper. Pick Edgy above to see this."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AccentChoice.entries.forEach { choice ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = choice.argb == accentArgb,
                        role = Role.RadioButton,
                        onClick = { onSelectPreset(choice) },
                    )
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = choice.argb == accentArgb, onClick = null)
                // The swatch is the derived ramp, not the flat seed colour:
                // what the user is choosing between is the metal, and a
                // flat dot would not show the difference between them.
                Box(
                    Modifier
                        .size(width = 44.dp, height = 18.dp)
                        .background(
                            // remembered per entry: five HSL derivations per
                            // recomposition of a list that never changes.
                            remember(choice.argb) {
                                MetalPalette.from(Color(choice.argb)).horizontal
                            },
                            RoundedCornerShape(4.dp),
                        ),
                )
                Text(choice.label, style = MaterialTheme.typography.bodyLarge)
            }
        }

        CustomColourPicker(accentArgb = accentArgb, onCommit = onSelectArgb)
    }
}

/**
 * Any colour → a metal.
 *
 * HSV sliders rather than a colour wheel: three sliders are legible with
 * TalkBack, need no bitmap sampling, and every position is a valid colour.
 * Value is floored at 0.25 — a near-black seed collapses the derived ramp
 * into mud, and 4.5:1 contrast on the readouts becomes unreachable.
 *
 * The commit is explicit ("Apply"), not on every drag: each change repaints
 * the entire app, and a slider that restyles the world while your finger is
 * still moving makes it impossible to compare before and after.
 */
@Composable
private fun CustomColourPicker(
    accentArgb: Long,
    onCommit: (Long) -> Unit,
) {
    val current = remember(accentArgb) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(accentArgb.toInt(), hsv)
        hsv
    }
    var hue by remember(accentArgb) { mutableStateOf(current[0]) }
    var sat by remember(accentArgb) { mutableStateOf(current[1]) }
    var value by remember(accentArgb) { mutableStateOf(current[2].coerceAtLeast(MIN_VALUE)) }

    val preview = Color.hsv(hue, sat, value)
    val previewArgb = preview.toArgb().toLong() and 0xFFFFFFFFL

    Text("Custom colour", style = MaterialTheme.typography.titleSmall)

    // Live ramp: what this seed looks like as a metal, before committing.
    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(
                // Changes on every slider movement by design; remember only
                // spares the rebuild when something *else* recomposes the panel.
                remember(previewArgb) { MetalPalette.from(preview).horizontal },
                RoundedCornerShape(4.dp),
            ),
    )

    Text("Hue", style = MaterialTheme.typography.labelMedium)
    Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
    Text("Saturation", style = MaterialTheme.typography.labelMedium)
    Slider(value = sat, onValueChange = { sat = it }, valueRange = 0f..1f)
    Text("Brightness", style = MaterialTheme.typography.labelMedium)
    Slider(value = value, onValueChange = { value = it }, valueRange = MIN_VALUE..1f)

    GoldOutlinedButton(
        onClick = { onCommit(previewArgb) },
        enabled = previewArgb != accentArgb,
    ) { Text("Apply this colour") }
}

/** Below this brightness the derived ramp loses its highlight — see picker doc. */
private const val MIN_VALUE = 0.25f

@Composable
private fun ThemeRow(
    choice: AppearanceChoice,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row is the target: a 20 dp radio button is a poor one.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(choice.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                choice.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Export/import over the Storage Access Framework: the user picks the location,
 * so the app needs no storage permission and never browses files on its own.
 */
@Composable
private fun BackupPanel(viewModel: DashboardViewModel) {
    val message by viewModel.backupMessage.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupSchema.MIME_TYPE),
    ) { uri -> uri?.let(viewModel::export) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::import) }

    Panel {
        PanelHeader("Backup")
        Text(
            "Hearing runs, presets and the EQ curve, for moving to another phone. " +
                "A plain JSON file, saved where you choose. Nothing is uploaded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoldButton(
                onClick = {
                    exportLauncher.launch(
                        BackupSchema.defaultFileName(System.currentTimeMillis()),
                    )
                },
            ) { Text("Export") }
            GoldOutlinedButton(
                onClick = { importLauncher.launch(arrayOf(BackupSchema.MIME_TYPE, "*/*")) },
            ) { Text("Import") }
        }
        message?.let { current ->
            PanelDivider()
            Text(
                current.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (current.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            TextButton(onClick = viewModel::dismissBackupMessage) { Text("Dismiss") }
        }
    }
}

/**
 * Four answers, none of them on screen until asked for.
 *
 * These used to be four permanently open paragraphs, which put a page of prose
 * between the backup buttons and the version number for every user who had
 * already read them once. A settings screen is somewhere people come to change
 * one thing.
 */
@Composable
private fun HowToPanel() {
    Panel {
        PanelHeader("How it works")
        HowTo(
            "Sound profiling",
            "A pulsed-tone test finds the quietest level you still hear at each " +
                "frequency, one ear at a time. Run it three times or more — the app " +
                "uses the per-frequency median, which is what makes the result stable.",
        )
        PanelDivider()
        HowTo(
            "The EQ",
            "Your profile becomes a correction curve. Pick 10, 20 or 31 bands: the " +
                "curve is resampled onto whichever you choose, so switching costs " +
                "nothing. Save any curve under a name and bind it to a headphone.",
        )
        PanelDivider()
        HowTo(
            "Per-device profiles",
            "Bind a preset to a headphone and it is applied the moment that device " +
                "connects — no need to open the app.",
        )
        PanelDivider()
        HowTo(
            // No brand names: which companion app the listener has is not
            // something this screen knows, and the rule holds for all of them.
            "Vendor equalizers",
            "Some headphone apps run their equalizer inside the headphone, where " +
                "Android cannot see it. Set those flat, or their curve stacks on top " +
                "of this one.",
        )
    }
}

/** A title that answers when tapped. No control — there is nothing to set here. */
@Composable
private fun HowTo(title: String, body: String) {
    ExplainedRow(label = title, explanation = body, control = {})
}

@Composable
private fun AboutPanel() {
    val context = LocalContext.current
    // BuildConfig is switched off for the whole project
    // (android.defaults.buildfeatures.buildconfig=false), so the version comes
    // from the installed package instead — the same string, read at runtime.
    // runCatching because getPackageInfo is declared as throwing for a package
    // that does not exist, which cannot happen for our own.
    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }

    Panel {
        PanelHeader("About")
        Text(
            "DankYeeter's Bluetooth Dashboard",
            style = MaterialTheme.typography.bodyMedium,
        )
        version?.let {
            Text(
                "Version $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Nothing you measure leaves the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The app used to carry no INTERNET permission at all, and said so
        // here. It holds one now — starting the helper without a computer needs
        // a socket, and Android gates loopback behind the same permission — so
        // the old sentence was a privacy claim the manifest no longer backed.
        ExplainedHeader(
            "Network access",
            "The app holds the internet permission for one reason: starting its helper " +
                "needs a connection to the debugging service on this phone, at " +
                "127.0.0.1. There is no code that contacts a remote server.",
        )
    }
}

// The rendering of AttachmentStatus lives in ui.common now. It used to be a
// private copy here and another on the System access screen, and the two had
// already drifted into saying different things about the same state.
