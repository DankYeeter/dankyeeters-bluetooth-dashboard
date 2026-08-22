package dev.dankyeeter.btdashboard.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.dankyeeter.btdashboard.privileged.PrivilegedBootstrap
import dev.dankyeeter.btdashboard.privileged.PrivilegedConnection
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.attach.AttachmentStatus
import dev.dankyeeter.btdashboard.system.shizuku.SecureSettingsState
import dev.dankyeeter.btdashboard.ui.screens.devices.CopyableCommand
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.PanelDivider
import dev.dankyeeter.btdashboard.ui.theme.PanelHeader
import dev.dankyeeter.btdashboard.ui.theme.Pill
import dev.dankyeeter.btdashboard.ui.theme.PillTone
import dev.dankyeeter.btdashboard.ui.theme.Readout

/**
 * How the app gets shell-level access, and what it can and cannot do without it.
 *
 * The app ships exactly one shell identity: its own privileged helper.
 * Shizuku used to be documented here as the secondary path and was removed on
 * Daniel's explicit decision — one access route, owned by this project.
 *
 * There is no INTERNET permission in this project, so nothing here fetches or
 * installs anything. Every route ends in a command the user runs themselves.
 */
@Composable
fun SystemAccessScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val attachment by SystemGraph.eqController.status.collectAsStateWithLifecycle()

    // The live connection, not a cached flag: the helper dies on reboot and the
    // death recipient behind this flow is what turns the screen honest again.
    val helper by PrivilegedConnection.service.collectAsStateWithLifecycle()
    val helperRunning = helper != null

    val bootstrap = remember(context) { PrivilegedBootstrap(context) }

    // Not once per recomposition — adbCommand() mints and stores a token on
    // first use — but re-read whenever the helper connects or dies. Accepting a
    // hand-over spends the token that was in the command, so the line shown
    // after a connect has to be a new one; a screen still offering the spent
    // command would be handing the user something that no longer works.
    //
    // A state rather than a plain value because the user can also ask for a new
    // command by hand, below. Re-reading on the key change is not a second mint:
    // adbCommand() returns the token this process already minted unless it was
    // spent, so the automatic path and the button cannot fight over it.
    var adbCommand by remember(context, helperRunning) {
        mutableStateOf(bootstrap.adbCommand())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("System access", style = MaterialTheme.typography.displayMedium)
        Text(
            "Android does not let an ordinary app equalize other apps' audio, or read " +
                "what the Bluetooth stack negotiated. Both need a shell-level identity. " +
                "This app brings its own helper for that — the route below.",
            style = MaterialTheme.typography.bodyMedium,
        )

        HelperPanel(
            running = helperRunning,
            adbCommand = adbCommand,
            onNewCommand = { adbCommand = bootstrap.newAdbCommand() },
        )
        CapabilityPanel(attachment = attachment, shellAvailable = helperRunning)
        SecureSettingsPanel()
        HeadphoneAppPanel()

        GoldButton(onClick = onDone) { Text("Done") }
    }
}

/**
 * The app's own helper: the primary route, and the one that is actually
 * verified working on this phone.
 *
 * The state is deliberately said twice — once in the pill, once in the
 * readout. Under the Edgy theme a readout is painted with the metal gradient,
 * whose lower stops (Gold.Deep, Gold.Shadow) measure 2.7:1 and 1.3:1 against a
 * panel; the bright half of the ramp carries the glyphs and the dark half is
 * shading. That is fine for a value the user is glancing at, and not fine as
 * the only place a state is stated, so the pill states it in a colour that
 * clears 4.5:1 on its own. Both pairings are pinned in ContrastTest.
 */
@Composable
private fun HelperPanel(running: Boolean, adbCommand: String, onNewCommand: () -> Unit) {
    val context = LocalContext.current
    Panel {
        PanelHeader(
            "App helper",
            trailing = {
                Pill(
                    if (running) "Running" else "Not running",
                    tone = if (running) PillTone.ACCENT else PillTone.WARN,
                )
            },
        )

        Readout(
            value = if (running) "Running" else "Not running",
            caption = "uid 2000 — shell level, the same access \"adb shell\" already " +
                "has. Not root.",
        )

        PanelDivider()

        Text("Start it", style = MaterialTheme.typography.titleSmall)
        Text(
            "Once per boot, from a computer with ADB — over a cable, or from a shell " +
                "already paired for wireless debugging. Run:",
            style = MaterialTheme.typography.bodySmall,
        )
        CopyableCommand(context, adbCommand)
        Text(
            // Deliberately no count of the whitelisted commands here. Another
            // worker is extending the helper's operation surface right now, and
            // a number in the copy would quietly stop being true; the property
            // that matters — full-vector matching rather than a free shell —
            // holds either way.
            "The command carries a token this app generated. That token is how the " +
                "helper proves to the app that it is the process the user just started, " +
                "and it never leaves the phone. What the helper will run is a fixed " +
                "list, matched in full — it does not hand out a shell.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        GoldOutlinedButton(onClick = onNewCommand) { Text("Generate a new command") }
        Text(
            // Careful with the tense here. Minting only replaces the *pending*
            // token; the token a running helper is already answering to is the
            // active one and is untouched until the new command is actually run
            // and the hand-over accepted. Saying "this revokes the old token"
            // would be wrong on both halves.
            "For when you consider the token in the command above compromised. It " +
                "replaces the line with one carrying a freshly minted token, and the old " +
                "line stops being accepted. A helper that is already running is not " +
                "affected — it keeps serving until you run the new command.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PanelDivider()

        Text("It dies on reboot", style = MaterialTheme.typography.titleSmall)
        Text(
            "There is no way around that without root, and this app does not ask for " +
                "root. Only ADB can put a process on the shell uid, so the helper cannot " +
                "restart itself and the app cannot start it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What shell access buys, with live state where live state exists.
 *
 * Written as three separate capabilities rather than one "everything works"
 * line, because they do not fail together: the EQ has its own fallback and its
 * own reason string, while the two read-only checks simply stop.
 */
@Composable
private fun CapabilityPanel(attachment: AttachmentStatus, shellAvailable: Boolean) {
    Panel {
        PanelHeader("What needs it")

        // The EQ reports what the controller actually did, not what it should
        // have done. Deriving the line from the live status rather than writing
        // it out is what keeps this row true if the attach path changes.
        CapabilityRow(
            name = "System-wide EQ",
            pill = attachment.pill(),
            tone = attachment.tone(),
            body = attachment.describe(),
        )

        PanelDivider()

        CapabilityRow(
            name = "Codec details",
            pill = if (shellAvailable) "Available" else "Cannot check",
            tone = if (shellAvailable) PillTone.ACCENT else PillTone.WARN,
            body = "Android only reports the negotiated codec to apps holding " +
                "BLUETOOTH_PRIVILEGED, which no sideloaded app has. The fallback reads " +
                "it out of a dumpsys of the Bluetooth stack, and that needs the shell. " +
                "Without it the codec reads as unknown — the screen says so rather " +
                "than guessing.",
        )

        PanelDivider()

        CapabilityRow(
            name = "Other-equalizer check",
            pill = if (shellAvailable) "Available" else "Cannot check",
            tone = if (shellAvailable) PillTone.ACCENT else PillTone.WARN,
            body = "Another equalizer stacking on top of this one silently undoes the " +
                "curve. Finding one means dumping audio_flinger's effect chains, which " +
                "needs the shell. Without it the check reports that it cannot check — " +
                "never an all-clear it did not earn.",
        )
    }
}

@Composable
private fun CapabilityRow(name: String, pill: String, tone: PillTone, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Pill(pill, tone = tone)
        }
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * WRITE_SECURE_SETTINGS. Separate from the shell above because it is a
 * permission rather than an identity: once granted it survives a reboot, which
 * makes it the one thing on this screen the user only ever does once.
 */
@Composable
private fun SecureSettingsPanel() {
    val context = LocalContext.current
    val gate = SystemGraph.secureSettings
    val granted = gate.state() == SecureSettingsState.GRANTED

    Panel {
        PanelHeader(
            "WRITE_SECURE_SETTINGS",
            trailing = {
                Pill(
                    if (granted) "Granted" else "Not granted",
                    tone = if (granted) PillTone.ACCENT else PillTone.NEUTRAL,
                )
            },
        )
        Text(
            if (granted) {
                "Granted, and it stays granted across reboots. The absolute-volume " +
                    "toggle can be written system-wide."
            } else {
                "Optional. Only the absolute-volume toggle needs it. It can never be " +
                    "granted from inside an app — you run one ADB command yourself, " +
                    "and unlike the shells above this one survives a reboot."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!granted) CopyableCommand(context, gate.adbGrantCommand())
    }
}

@Composable
private fun HeadphoneAppPanel() {
    Panel {
        PanelHeader("Headphone app")
        Text(
            "Set the EQ inside the Focal & Naim / Noble FoKus app to flat. Their EQ runs " +
                "in the headphone's own DSP, after Bluetooth transmission, so it adds to " +
                "ours instead of replacing it — and no app on Android can read or " +
                "change it. This app never modifies another app's stored settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- live state, rendered ---------------------------------------------------


private fun AttachmentStatus.pill(): String = when (this) {
    is AttachmentStatus.ActiveGlobal -> "Global"
    is AttachmentStatus.ActiveSessions -> "Session only"
    is AttachmentStatus.Unavailable -> "Unavailable"
    AttachmentStatus.Inactive -> "Off"
}

private fun AttachmentStatus.tone(): PillTone = when (this) {
    is AttachmentStatus.ActiveGlobal -> PillTone.ACCENT
    is AttachmentStatus.ActiveSessions, is AttachmentStatus.Unavailable -> PillTone.WARN
    AttachmentStatus.Inactive -> PillTone.NEUTRAL
}

private fun AttachmentStatus.describe(): String = when (this) {
    is AttachmentStatus.ActiveGlobal ->
        "Attached to the output mix, which reaches every app including Tidal."
    is AttachmentStatus.ActiveSessions ->
        "Fell back to session mode: attached to ${sessionIds.size} announced session(s). " +
            "Players that do not announce their audio session are not affected at all, " +
            "and Tidal does not announce reliably."
    is AttachmentStatus.Unavailable -> reason
    // Inactive is both "the user switched the EQ off" and "nothing has applied
    // it yet this session" — it is the controller's initial value. Naming only
    // the first would be a guess dressed up as a fact.
    AttachmentStatus.Inactive ->
        "Not attached. Either the EQ is switched off, or nothing has applied it yet " +
            "since the app started."
}
