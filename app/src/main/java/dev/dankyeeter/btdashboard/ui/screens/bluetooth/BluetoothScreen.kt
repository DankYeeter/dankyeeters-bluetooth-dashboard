package dev.dankyeeter.btdashboard.ui.screens.bluetooth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.system.devices.DeviceKey
import dev.dankyeeter.btdashboard.system.devices.DeviceProfile
import dev.dankyeeter.btdashboard.ui.screens.dashboard.BluetoothCodecSection
import dev.dankyeeter.btdashboard.ui.screens.dashboard.BluetoothDashboardViewModel
import dev.dankyeeter.btdashboard.ui.screens.devices.DeviceProfilesViewModel
import dev.dankyeeter.btdashboard.ui.screens.devices.ProfileEditorCard
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton
import dev.dankyeeter.btdashboard.ui.theme.Panel

/**
 * Settings for the headphone that is connected right now.
 *
 * These used to live two taps away behind "Device profiles", which put the
 * settings for every headphone the app has ever seen in front of the one
 * actually in the user's ears. Now the connected device's own settings are the
 * screen, and everything else is behind one button.
 *
 * Identification is by hashed address. When the A2DP proxy is unbound the only
 * address available comes from `dumpsys`, which user builds redact to
 * `XX:XX:XX:XX:35:6A` — that cannot be hashed to a key, so the section says it
 * cannot identify the device rather than guessing at a profile and showing
 * someone else's volume. It also hands the reader on to the profiles screen,
 * which looks profiles up by stored key and so is not blocked by the redaction.
 *
 * On this tab the card folds its Bluetooth internals away by default: this is
 * the screen the app opens on, and a first screen that scrolls for a page and
 * a half is a worse answer to "what is my headphone doing" than a short one.
 */
@Composable
private fun ConnectedDeviceSettings(
    onOpenDeviceProfiles: () -> Unit,
    dashboard: BluetoothDashboardViewModel = viewModel(),
    profilesViewModel: DeviceProfilesViewModel = viewModel(),
) {
    val state by dashboard.state.collectAsStateWithLifecycle()
    val profiles by profilesViewModel.profiles.collectAsStateWithLifecycle()

    val active = state.rows.firstOrNull { it.device.isActive } ?: state.rows.firstOrNull()

    // Every branch below draws the same card. It is the whole point of this
    // section: the settings are the screen, so they stay on it, and the states
    // differ only in whether the fields carry a device's values and accept
    // edits. Replacing them with a sentence \u2014 which is what this used to do \u2014
    // left the tab looking empty exactly when the user wanted to know what
    // would happen once their headphones connected.
    if (state.loading) {
        ProfileEditorCard(
            initial = DeviceProfile(deviceKey = "", name = ""),
            viewModel = profilesViewModel,
            header = "Device settings",
            showDismissActions = false,
            enabled = false,
            note = "Looking for a connected device\u2026",
            collapsibleAdvanced = true,
        )
        return
    }

    if (active == null) {
        ProfileEditorCard(
            initial = DeviceProfile(deviceKey = "", name = ""),
            viewModel = profilesViewModel,
            header = "Device settings",
            showDismissActions = false,
            enabled = false,
            note = "Nothing connected \u2014 these fill in when a headphone connects.",
            headerExplanation = "This is every setting a headphone can be given. The " +
                "fields carry that device's values and become editable the moment one " +
                "connects.",
            collapsibleAdvanced = true,
        )
        return
    }

    val key = DeviceKey.fromAddress(active.device.address)
    if (key == null) {
        ProfileEditorCard(
            initial = DeviceProfile(deviceKey = "", name = active.device.name),
            viewModel = profilesViewModel,
            header = active.device.name,
            showDismissActions = false,
            enabled = false,
            note = "This Android build hides the address, so this headphone cannot be " +
                "matched here.",
            headerExplanation = "Without an address there is no key to look a stored " +
                "profile up by, so nothing in this card can be edited. The profile still " +
                "applies on connect \u2014 only this inline editor needs the address.",
            collapsibleAdvanced = true,
        )
        // The profiles screen edits by stored key rather than by live address,
        // so the work this card cannot do is possible one screen along. Saying
        // "cannot be edited" and stopping there was the dead end.
        GoldOutlinedButton(onClick = onOpenDeviceProfiles) { Text("Edit in device profiles") }
        return
    }

    ProfileEditorCard(
        initial = profiles.firstOrNull { it.deviceKey == key }
            ?: DeviceProfile(deviceKey = key, name = active.device.name),
        viewModel = profilesViewModel,
        header = active.device.name,
        showDismissActions = false,
        collapsibleAdvanced = true,
    )
}

/**
 * The front tab: what is connected, how it sounds, and the settings that belong
 * to the *device* rather than to the app.
 *
 * App-level preferences (theme, backup, first-run setup) deliberately live in
 * the Settings tab instead — this screen answers "what is my headphone doing
 * right now", and mixing app housekeeping into that answer is what made the old
 * dashboard a dumping ground.
 */
@Composable
fun BluetoothScreen(
    onWatchLive: () -> Unit = {},
    onOpenDeviceProfiles: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Bluetooth", style = MaterialTheme.typography.displayMedium)

        BluetoothCodecSection(onWatchLive = onWatchLive)

        ConnectedDeviceSettings(onOpenDeviceProfiles = onOpenDeviceProfiles)

        // Header, sentence and button all said "other device profiles" three
        // times over. The header carries the name, its question mark carries
        // the sentence, and the button says where it goes.
        Panel {
                ExplainedHeader(
                    "Other devices",
                    "Every headphone this app has seen, with the profile applied when " +
                        "it connects.",
                )
                GoldOutlinedButton(onClick = onOpenDeviceProfiles) { Text("Open device profiles") }
        }
    }
}
