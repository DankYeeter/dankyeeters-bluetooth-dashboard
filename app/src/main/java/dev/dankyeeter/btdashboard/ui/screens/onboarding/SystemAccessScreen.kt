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
import dev.dankyeeter.btdashboard.system.secure.SecureSettingsState
import dev.dankyeeter.btdashboard.ui.common.describe
import dev.dankyeeter.btdashboard.ui.common.pill
import dev.dankyeeter.btdashboard.ui.common.tone
import dev.dankyeeter.btdashboard.ui.screens.activate.ActivateStep
import dev.dankyeeter.btdashboard.ui.screens.devices.CopyableCommand
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
import dev.dankyeeter.btdashboard.ui.theme.ExplainedRow
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.PanelDivider
import dev.dankyeeter.btdashboard.ui.theme.PanelHeader
import dev.dankyeeter.btdashboard.ui.theme.Pill
import dev.dankyeeter.btdashboard.ui.theme.PillTone

/**
 * How the app gets shell-level access, and what it can and cannot do without it.
 *
 * The app ships exactly one shell identity: its own privileged helper.
 * Shizuku used to be documented here as the secondary path and was removed on
 * Daniel's explicit decision — one access route, owned by this project.
 *
 * The helper starts from this phone, not from a computer: the app pairs with
 * the wireless-debugging service running on the device itself. The ADB line
 * further down is the fallback for a phone where that pairing cannot be used,
 * and it is a command the user runs themselves — nothing here fetches or
 * installs anything.
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
            "Some of what this app does needs shell-level access, which its own " +
                "helper provides.",
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

        GoldButton(onClick = onDone) { Text("Back to settings") }
    }
}

/**
 * The app's own helper: started here, on the phone, with the computer route
 * kept only as a fallback.
 *
 * The state is stated once, by the pill. It used to be said twice, in a
 * [dev.dankyeeter.btdashboard.ui.theme.Readout] as well, because under the Edgy
 * theme a readout is painted with the metal gradient, whose lower stops
 * (Gold.Deep, Gold.Shadow) measure 2.7:1 and 1.3:1 against a panel — fine for a
 * value being glanced at, not fine as the only place a state is stated. The
 * pill clears 4.5:1 on its own, so it can carry the state alone; both pairings
 * stay pinned in ContrastTest.
 */
@Composable
private fun HelperPanel(running: Boolean, adbCommand: String, onNewCommand: () -> Unit) {
    val context = LocalContext.current
    Panel {
        ExplainedHeader(
            "App helper",
            "Android does not let an ordinary app equalize other apps' audio, or read " +
                "what the Bluetooth stack negotiated. Both need a shell-level identity, " +
                "which is what the helper runs at — uid 2000, the same access adb shell " +
                "has. Not root.",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Pill(
                if (running) "Running" else "Not running",
                tone = if (running) PillTone.ACCENT else PillTone.WARN,
            )
        }

        PanelDivider()

        ExplainedHeader(
            "Starting the helper",
            "Shell level is something only Android's debugging service can grant, and " +
                "the app pairs with that service on this phone. If pairing cannot be " +
                "used, the same command can be run from a computer with ADB.",
        )
        // The one activation control the app has, not a second copy of it: the
        // gate, the setup process and this screen must offer the same thing, or
        // which one the user reached would decide how activation behaves.
        ActivateStep()
        Text(
            "Start the helper here. It needs no computer.",
            style = MaterialTheme.typography.bodySmall,
        )

        PanelDivider()

        Text(
            "Fallback — only needed if pairing fails.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        ExplainedRow(
            label = "Replaces the command above with one carrying a new token.",
            // Careful with the tense here. Minting only replaces the *pending*
            // token; the token a running helper is already answering to is the
            // active one and is untouched until the new command is actually run
            // and the hand-over accepted. Saying "this revokes the old token"
            // would be wrong on both halves.
            explanation = "For when you consider the token in the command above " +
                "compromised. The old line stops being accepted; a helper that is " +
                "already running keeps serving until you run the new command.",
            control = {
                GoldOutlinedButton(onClick = onNewCommand) { Text("Replace the command") }
            },
        )

        PanelDivider()

        ExplainedHeader(
            "Why it stops at reboot",
            "Only Android's debugging service can put a process at shell level, so the " +
                "helper cannot survive a restart. The app restarts it for you — after " +
                "the first pairing that is one tap, or automatic.",
        )
        Text("It stops at every reboot.", style = MaterialTheme.typography.bodySmall)
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
        PanelHeader("What the helper unlocks")

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
            body = "Without the helper the codec reads as unknown.",
            explanation = "Android only reports the negotiated codec to apps holding " +
                "BLUETOOTH_PRIVILEGED, which no sideloaded app has. The fallback reads " +
                "it out of a dumpsys of the Bluetooth stack, which needs the shell — " +
                "without it the screen says unknown rather than guessing.",
        )

        PanelDivider()

        CapabilityRow(
            name = "Other-equalizer check",
            pill = if (shellAvailable) "Available" else "Cannot check",
            tone = if (shellAvailable) PillTone.ACCENT else PillTone.WARN,
            body = "Without the helper this check cannot run, so it never reports " +
                "all-clear.",
            explanation = "Another equalizer stacking on top of this one silently undoes " +
                "the curve, and finding one needs the shell. Without it the check " +
                "reports that it cannot check, never an all-clear it did not earn.",
        )
    }
}

/**
 * @param body the one line the user reads without asking for more.
 * @param explanation the mechanism behind that line, folded away. Null where
 *   the body is already derived from live state and has nothing behind it.
 */
@Composable
private fun CapabilityRow(
    name: String,
    pill: String,
    tone: PillTone,
    body: String,
    explanation: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Pill(pill, tone = tone)
        }
        if (explanation == null) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // No control: the row is a statement, and the only thing to do with
            // it is ask why.
            ExplainedRow(label = body, explanation = explanation, control = {})
        }
    }
}

/**
 * WRITE_SECURE_SETTINGS. Separate from the helper above because it is a
 * permission rather than an identity: the helper grants it on every connect,
 * but once granted it stays granted across reboots — so on a phone where the
 * helper cannot start, granting it once from a computer keeps the
 * absolute-volume toggle working for good.
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
                "Granted, and it survives reboots."
            } else {
                "The helper grants this the moment it attaches."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!granted) {
            ExplainedHeader(
                "If the helper cannot start",
                "This permission can also be granted from a computer with ADB. It " +
                    "survives reboots, unlike the helper.",
            )
            CopyableCommand(context, gate.adbGrantCommand())
        }
    }
}

@Composable
private fun HeadphoneAppPanel() {
    Panel {
        // No brand names. Which companion app the listener has is not something
        // this screen knows, and naming two of them made the sentence read as a
        // list rather than as a rule that applies to all of them.
        ExplainedHeader(
            "Headphone-side equalizers",
            "That equalizer runs in the headphone's own DSP, after Bluetooth " +
                "transmission, so it adds to ours instead of replacing it — and no app " +
                "on Android can read or change it.",
        )
        Text(
            "Some headphone apps have their own equalizer inside the headphone. Set " +
                "it flat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
