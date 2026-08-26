package dev.dankyeeter.btdashboard.ui.screens.devices

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.monitor.codec.ChannelMode
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.codec.CodecStatus
import dev.dankyeeter.btdashboard.system.devices.AbsoluteVolumeStatus
import dev.dankyeeter.btdashboard.system.devices.ApplyResult
import dev.dankyeeter.btdashboard.system.devices.BluetoothCodecOptions
import dev.dankyeeter.btdashboard.system.devices.BluetoothDeveloperOptions
import dev.dankyeeter.btdashboard.system.devices.BluetoothReadOnlySettings
import dev.dankyeeter.btdashboard.system.devices.BluetoothSystemControls
import dev.dankyeeter.btdashboard.system.devices.CodecPreference
import dev.dankyeeter.btdashboard.system.devices.DeviceProfile
import dev.dankyeeter.btdashboard.system.devices.HdAudioPreference
import dev.dankyeeter.btdashboard.system.devices.HdAudioState
import dev.dankyeeter.btdashboard.ui.icons.DeviceIcons
import kotlin.math.roundToInt
import dev.dankyeeter.btdashboard.ui.theme.ExplainedHeader
import dev.dankyeeter.btdashboard.ui.theme.ExplainedRow
import dev.dankyeeter.btdashboard.ui.theme.GoldCard
import dev.dankyeeter.btdashboard.ui.theme.GoldTitle
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton
import dev.dankyeeter.btdashboard.ui.theme.Panel
import dev.dankyeeter.btdashboard.ui.theme.PanelHeader

/**
 * Per-device profiles (spec core function 2).
 *
 * A profile is a set of *intentions*, not a snapshot: every field can be left
 * at "leave alone", and the editor says so explicitly rather than defaulting
 * to whatever the phone happens to be doing right now.
 */
@Composable
fun DeviceProfilesScreen(
    onBack: () -> Unit,
    viewModel: DeviceProfilesViewModel = viewModel(),
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val bonded by viewModel.bonded.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val messageDeviceKey by viewModel.messageDeviceKey.collectAsStateWithLifecycle()
    val lastAutoApply by viewModel.lastAutoApply.collectAsStateWithLifecycle()

    // Every device we could show, profile-backed ones first.
    val known = remember(profiles, bonded) {
        val byKey = linkedMapOf<String, KnownDevice>()
        profiles.forEach { byKey[it.deviceKey] = KnownDevice(it.deviceKey, it.name, bonded = false) }
        bonded.forEach { byKey[it.deviceKey] = it }
        byKey.values.toList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Device profiles", style = MaterialTheme.typography.displayMedium)

        // The way out sits at the top, where a reader looking for it first
        // looks. At the bottom it was behind every card on the screen: on a
        // phone with six paired headphones, leaving meant scrolling past all
        // of them.
        TextButton(onClick = onBack) { Text("Back to Bluetooth") }

        ExplainedHeader(
            "Device profiles",
            "A profile can set the compensation curve, media volume, " +
                "absolute-volume preference and Bluetooth codec. Addresses are " +
                "stored hashed — the raw MAC is never written down.",
        )
        Text(
            "When one of these headphones connects, the app applies its profile.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        lastAutoApply?.let { AutoApplyCard(it, viewModel) }

        if (!viewModel.hasBluetoothPermission()) {
            Panel {
                    ExplainedHeader(
                        "Bluetooth access missing",
                        "Profiles for devices already seen still work — the permission " +
                            "is only what lets Android name the devices this phone is " +
                            "paired with.",
                    )
                    Text(
                        "Without Bluetooth access the paired-device list stays empty.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Asking here rather than sending the reader to the setup
                    // wizard: the panel names a missing permission, and the
                    // system dialog that grants it is one call away. `refresh()`
                    // re-reads the bonded list, which is what the panel was
                    // complaining about, and drops the panel on success.
                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) { viewModel.refresh() }
                    GoldButton(
                        onClick = {
                            launcher.launch(
                                arrayOf(
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.BLUETOOTH_SCAN,
                                ),
                            )
                        },
                    ) { Text("Grant Bluetooth access") }
            }
        }

        if (known.isEmpty()) {
            Text(
                "No devices yet. Connect a headphone once and it appears here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        known.forEach { device ->
            val profile = profiles.firstOrNull { it.deviceKey == device.deviceKey }
            if (editing == device.deviceKey) {
                ProfileEditorCard(
                    initial = profile ?: DeviceProfile(device.deviceKey, device.name),
                    viewModel = viewModel,
                )
            } else {
                DeviceRowCard(
                    device = device,
                    profile = profile,
                    onEdit = { viewModel.startEditing(device.deviceKey) },
                    onApply = { profile?.let(viewModel::applyNow) },
                )
            }
            // The answer stands with the question. Collected at the foot of the
            // screen, "Codec still reads SBC" appeared below every other
            // headphone's card, and nothing on it said which card had produced
            // it — the reader had to remember what they had just tapped.
            if (messageDeviceKey == device.deviceKey) {
                message?.let { MessageNote(it, viewModel::dismissMessage) }
            }
        }

        // A result whose device has left the list — deleted, or never bonded —
        // still has to be shown somewhere, and the foot of the screen is the
        // only place left. The system panel's results land here too: they
        // belong to the phone, not to any card above.
        if (known.none { it.deviceKey == messageDeviceKey }) {
            message?.let { MessageNote(it, viewModel::dismissMessage) }
        }

        // After the devices, not before them. This screen is called "Device
        // profiles" and that is what someone opening it came for; the
        // phone-wide settings are the answer to a question they ask second.
        BluetoothSystemPanel(viewModel)
    }
}

/**
 * The Bluetooth settings that belong to the phone rather than to one headphone.
 *
 * ## Why this panel exists at all
 *
 * The same keys were already reachable, but only as a per-device wish inside
 * some headphone's profile — and that implied a per-device setting that does
 * not exist. Someone who simply wanted AVRCP set to 1.4 on this phone had to
 * pick a headphone to say it through, and could not see what the value was
 * without opening one. Both halves are true and they are different questions:
 * "set this when the Bathys connects" is upstairs, "this is what the phone is
 * on right now" is here.
 *
 * ## Why the unwritable ones are here too
 *
 * Because leaving them out is the dishonest option. Android's own Developer
 * Options offers A2DP hardware offload; this app cannot, and a reader has no
 * way to tell "we chose not to" from "we could not" from "we forgot" unless the
 * row says which. Each one names the mechanism, shows its live value, and
 * quotes the reason it is beyond reach.
 */
@Composable
private fun BluetoothSystemPanel(viewModel: DeviceProfilesViewModel) {
    val live by viewModel.liveDevOptions.collectAsStateWithLifecycle()
    val readOnly by viewModel.readOnlySettings.collectAsStateWithLifecycle()
    val canRestart by viewModel.canRestartBluetooth.collectAsStateWithLifecycle()
    val restarting by viewModel.restarting.collectAsStateWithLifecycle()
    val absoluteStatus by viewModel.absoluteVolumeStatus.collectAsStateWithLifecycle()
    val writable = absoluteStatus !is AbsoluteVolumeStatus.PermissionMissing

    Panel {
        ExplainedHeader(
            "Bluetooth system",
            "These belong to the phone, not to one headphone: Android keeps a single " +
                "value for each. Setting one here changes it now and for every device. " +
                "The profiles above can also ask for these — that is a different thing, " +
                "re-applied whenever a chosen headphone connects, and the last device to " +
                "connect wins.",
        )
        Text(
            "One value for the whole phone. Changed here, changed now.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        if (!writable) {
            Text(
                "These need system access, which is not granted yet. The helper grants it " +
                    "as soon as it is running.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        BluetoothSystemControls.writableGlobals.forEach { option ->
            val current = live[option.key]
            PickerMenu<String>(
                label = option.label,
                // Never a stored wish: this picker shows what the settings
                // database actually holds. "Use System Default" is what an unset
                // key reads as, which is the state a fresh phone is in.
                selectedLabel = current?.let(option::labelFor) ?: "Use System Default",
                options = listOf(
                    BluetoothDeveloperOptions.USE_SYSTEM_DEFAULT to "Use System Default",
                ) + option.values.map { it.raw to it.label },
                onSelect = { viewModel.setGlobalNow(option.key, it) },
                enabled = writable,
                explanation = option.explanation,
            )
            option.caution?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }

        HorizontalDivider()

        ExplainedRow(
            label = "Restart Bluetooth",
            explanation = "The Bluetooth stack reads the settings above once, when it " +
                "starts. Until it is restarted, a value you just changed is stored and " +
                "not yet in force — which is why this button is here rather than a " +
                "sentence telling you to go and do it in the quick-settings panel. " +
                "Everything reconnects on its own afterwards.",
            control = {},
        )
        GoldOutlinedButton(
            onClick = viewModel::restartBluetooth,
            enabled = canRestart && !restarting,
        ) {
            Text(
                when {
                    restarting -> "Restarting…"
                    canRestart -> "Turn Bluetooth off and on"
                    else -> "Needs the helper"
                },
            )
        }
        if (!canRestart) {
            Text(
                "The helper is not running, so the app cannot cycle the radio for you. " +
                    "Turning Bluetooth off and on by hand does exactly the same thing.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        HorizontalDivider()

        ExplainedHeader(
            "Shown but not changeable",
            "These affect Bluetooth audio and this app cannot write them, so they are " +
                "here read-only rather than left out. Each one is a system property " +
                "rather than a setting — a different mechanism that the phone's init " +
                "process guards, and it refuses the shell. The app's helper is the " +
                "shell, so being privileged does not help; only a rooted phone could " +
                "change these.",
        )
        BluetoothReadOnlySettings.all.forEach { setting ->
            ExplainedRow(
                label = setting.label,
                explanation = setting.explanation + "\n\n" + setting.whyReadOnly,
                control = {},
            )
            Text(
                readOnly[setting.liveValueKey]
                    // Unset is the normal state for all of these, and it is not
                    // the same as "off" — for hardware offload the two are
                    // opposites, since the stack's built-in default is on.
                    ?.let { "Currently: $it" }
                    ?: "Not set — the Bluetooth stack's own default applies.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** One applied-or-failed result, shown under the card that caused it. */
@Composable
private fun MessageNote(text: String, onDismiss: () -> Unit) {
    Panel {
            Text(text, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun AutoApplyCard(result: ApplyResult, viewModel: DeviceProfilesViewModel) {
    val text = when (result) {
        // Counting actions said the least of anything that could be said here.
        // Observed on a Focal Bathys: the profile asked for aptX HD, the
        // headphone does not offer it in that negotiation, the stack ignored
        // the request — and the line read "applied (2 actions)", which is both
        // a wrong count and no help. The per-action sentences the "Apply now"
        // path already writes name what actually happened, so this reuses them
        // rather than inventing a second, vaguer vocabulary for the same events.
        is ApplyResult.Applied ->
            "Last connect: applied \"${result.profile.name}\". " +
                viewModel.describe(result.actions)
        is ApplyResult.AutoApplyDisabled ->
            "Last connect: \"${result.profile.name}\" has auto-apply switched off."
        is ApplyResult.NoProfile -> "Last connect: an unknown device — no profile was applied."
        ApplyResult.UnknownAddress -> "Last connect: the device address was unreadable."
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun DeviceRowCard(
    device: KnownDevice,
    profile: DeviceProfile?,
    onEdit: () -> Unit,
    onApply: () -> Unit,
) {
    Panel {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(DeviceIcons.forPresetId(profile?.calibrationPresetId)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Column {
                    Text(profile?.name ?: device.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        summarise(profile, device),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoldButton(onClick = onEdit) {
                    Text(if (profile == null) "Create profile" else "Edit profile")
                }
                if (profile != null) GoldOutlinedButton(onClick = onApply) { Text("Apply now") }
            }
    }
}

private fun summarise(profile: DeviceProfile?, device: KnownDevice): String {
    if (profile == null) return if (device.bonded) "Paired · no profile yet" else "No profile yet"
    val parts = buildList {
        if (profile.compensationProfileId != null) add("compensation")
        profile.mediaVolumePercent?.let { add("volume $it %") }
        profile.absoluteVolumeEnabled?.let { add("absolute volume ${if (it) "on" else "off"}") }
        // Before the codec, because it can override it: "LDAC · HD audio off"
        // would read as a contradiction, and in that order it reads as the
        // explanation it is.
        profile.hdAudio?.let {
            add(
                when (it) {
                    HdAudioPreference.ENABLE -> "HD audio on"
                    HdAudioPreference.DISABLE -> "HD audio off"
                    HdAudioPreference.SYSTEM_DEFAULT -> "HD audio by system default"
                },
            )
        }
        profile.codecPreference?.let { add(codecDisplayName(it.codec)) }
        if (!profile.autoApply) add("auto-apply off")
    }
    return if (parts.isEmpty()) "Nothing set — connecting changes nothing" else parts.joinToString(" · ")
}

// ---- editor -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileEditorCard(
    initial: DeviceProfile,
    viewModel: DeviceProfilesViewModel,
    header: String = "Edit profile",
    /**
     * Cancel and Delete belong to the list screen, where the editor is one of
     * two states a row can be in. Inline on the Bluetooth tab the editor *is*
     * the screen — there is nothing to cancel back to, and deleting the
     * profile of the headphone you are currently wearing is not an action that
     * belongs next to its volume slider.
     */
    showDismissActions: Boolean = true,
    /**
     * Draw every control, but inert.
     *
     * The Bluetooth tab shows this card whether or not a headphone is
     * connected. What a device can be told to do is most of what that tab is
     * for, and hiding it until something connects left one sentence of text
     * where the settings should be. Disabled rather than absent, because
     * controls that only appear on connect read as a different screen instead
     * of the same one waking up — and because an empty tab gives no answer to
     * "what will happen when I put these on".
     */
    enabled: Boolean = true,
    /**
     * One line under the header saying why the card is in the state it is in.
     * A greyed-out control with no explanation is a bug report waiting to be
     * filed, so every caller that passes `enabled = false` also says why.
     */
    note: String? = null,
    /**
     * The long version of [note], behind the header's question mark.
     *
     * The single line above has to be readable at a glance; the reasoning
     * behind it — why an address cannot be matched, what still works anyway —
     * is worth having but not worth three sentences of always-on prose above
     * the controls.
     */
    headerExplanation: String? = null,
    /**
     * Fold absolute volume, developer options and codec behind one expander.
     *
     * The Bluetooth tab is the app's start screen, and this card is on it in
     * full: name, icon, EQ, volume, three sections of Bluetooth internals and
     * auto-apply, which is more scrolling than any first screen should ask for.
     * The everyday fields stay in the open, the ones you set once fold away.
     * The profiles screen, where you went *in order to* edit a profile, keeps
     * everything expanded.
     */
    collapsibleAdvanced: Boolean = false,
) {
    val compensationProfiles by viewModel.compensationProfiles.collectAsStateWithLifecycle()
    val absoluteStatus by viewModel.absoluteVolumeStatus.collectAsStateWithLifecycle()
    val helperConnected by viewModel.helperConnected.collectAsStateWithLifecycle()
    val offeredCodecs by viewModel.offeredCodecs.collectAsStateWithLifecycle()
    val negotiatedCodec by viewModel.negotiatedCodec.collectAsStateWithLifecycle()
    val negotiatedStatus by viewModel.negotiatedStatus.collectAsStateWithLifecycle()
    val deviceConnected by viewModel.deviceConnected.collectAsStateWithLifecycle()
    val liveDevOptions by viewModel.liveDevOptions.collectAsStateWithLifecycle()
    val liveHdAudio by viewModel.hdAudioState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Asked again every time this card appears for a device, and never cached:
    // which codecs are selectable is a property of the current negotiation, not
    // of the headphone.
    LaunchedEffect(initial.deviceKey) { viewModel.loadOfferedCodecs(initial.deviceKey) }

    // Seeded from the whole `initial`, not from its key.
    //
    // On the Bluetooth tab the stored profiles arrive from DataStore a frame or
    // more after this card first composes, so the first `initial` is the
    // `DeviceProfile(deviceKey, name)` fallback — all defaults. Keyed by
    // `deviceKey`, the key was already correct when the real stored profile
    // landed, the remembers never re-ran, and the defaults stayed latched: the
    // card showed the wrong settings for a saved device, and Save wrote those
    // defaults back over the real profile.
    //
    // Keying on `initial` itself makes the data class's equality the trigger, so
    // the late-arriving stored profile re-seeds the editor. The accepted
    // tradeoff: an external change to the profile (auto-apply writing it, say)
    // also re-seeds and discards in-progress unsaved edits. For a card whose
    // `initial` can genuinely change identity-without-key, showing the stored
    // truth wins over preserving a draft that was built on defaults.
    var name by remember(initial) { mutableStateOf(initial.name) }
    var presetId by remember(initial) { mutableStateOf(initial.calibrationPresetId) }
    var compensationId by remember(initial) { mutableStateOf(initial.compensationProfileId) }
    var volumeEnabled by remember(initial) { mutableStateOf(initial.mediaVolumePercent != null) }
    var volume by remember(initial) { mutableStateOf((initial.mediaVolumePercent ?: 60).toFloat()) }
    var absoluteEnabled by remember(initial) { mutableStateOf(initial.absoluteVolumeEnabled) }
    var absoluteSystemDefault by remember(initial) { mutableStateOf(initial.absoluteVolumeSystemDefault) }
    var autoApply by remember(initial) { mutableStateOf(initial.autoApply) }
    var devOptions by remember(initial) { mutableStateOf(initial.developerOptions) }
    var codec by remember(initial) { mutableStateOf(initial.codecPreference) }
    var hdAudio by remember(initial) { mutableStateOf(initial.hdAudio) }
    // Saveable, so a rotation does not fold the section the user just opened.
    // Still keyed by `deviceKey` alone, unlike the fields above: where the
    // expander stands is not profile data, so a profile edit must not fold it.
    var advancedOpen by rememberSaveable(initial.deviceKey) { mutableStateOf(false) }
    val showAdvanced = !collapsibleAdvanced || advancedOpen

    Panel {
            if (headerExplanation != null) {
                ExplainedHeader(header, headerExplanation)
            } else {
                PanelHeader(header)
            }

            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(DeviceProfile.MAX_NAME_LENGTH) },
                label = { Text("Name") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )

            // Icon + calibration preset are one choice: the icon is derived from
            // the preset, so there is no way to pick a Bathys icon for an
            // AirPods correction curve.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(DeviceIcons.forPresetId(presetId)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                PickerMenu(
                    label = "Device type",
                    selectedLabel = viewModel.calibrationPresets
                        .firstOrNull { it.id == presetId }?.displayName ?: "Generic",
                    options = listOf<Pair<String?, String>>(null to "Generic") +
                        viewModel.calibrationPresets.map { it.id as String? to it.displayName },
                    onSelect = { presetId = it },
                    enabled = enabled,
                )
            }

            PickerMenu(
                label = "EQ preset",
                selectedLabel = compensationProfiles.firstOrNull { it.id == compensationId }?.name
                    ?: "None",
                options = listOf<Pair<String?, String>>(null to "None") +
                    compensationProfiles.map { it.id as String? to it.name },
                onSelect = { compensationId = it },
                enabled = enabled,
                explanation = "A preset is a compensation curve saved on the EQ screen — " +
                    "set by hand or measured with the hearing test — and given a name. " +
                    "Bind one here and it is applied whenever this device connects.",
            )
            if (compensationProfiles.isEmpty()) {
                // Just the fact. The instructions for making one are behind the
                // question mark on the row above: an empty list is not the
                // moment to teach a workflow the reader may not want.
                Text(
                    "No saved presets yet.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            HorizontalDivider()

            SwitchRow(
                label = "Set media volume on connect",
                checked = volumeEnabled,
                onCheckedChange = { volumeEnabled = it },
                enabled = enabled,
            )
            if (volumeEnabled) {
                Text("${volume.roundToInt()} %", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    valueRange = 0f..100f,
                    steps = 19,
                    enabled = enabled,
                )
            }

            HorizontalDivider()

            if (collapsibleAdvanced) {
                TextButton(onClick = { advancedOpen = !advancedOpen }) {
                    Text(if (advancedOpen) "Hide advanced settings" else "Advanced device settings")
                }
            }

            if (showAdvanced) {
                AbsoluteVolumeEditor(
                    status = absoluteStatus,
                    wish = absoluteEnabled,
                    systemDefault = absoluteSystemDefault,
                    onWishChange = { chosen ->
                        // The two fields are one control: choosing a value clears
                        // the reset flag, choosing reset clears the value. Letting
                        // both stand would store a profile that argues with itself.
                        absoluteEnabled = chosen
                        absoluteSystemDefault = false
                    },
                    onSystemDefault = {
                        absoluteEnabled = null
                        absoluteSystemDefault = true
                    },
                    // The key travels with the write so the result lands under
                    // this card rather than at the foot of the list screen.
                    onWriteNow = { on -> viewModel.setAbsoluteVolumeNow(initial.deviceKey, on) },
                    context = context,
                    enabled = enabled,
                )

                HorizontalDivider()

                DeveloperOptionsEditor(
                    selected = devOptions,
                    live = liveDevOptions,
                    permissionMissing = absoluteStatus is AbsoluteVolumeStatus.PermissionMissing,
                    onChange = { key, value -> devOptions = devOptions + (key to value) },
                    enabled = enabled,
                )

                HorizontalDivider()

                // Above the codec section, mirroring the order the applier runs
                // them in — and for the same reason. HD audio is the gate in
                // front of codec negotiation: with it off, everything in the
                // section below is moot, and a reader who meets the codec picker
                // first has no way to know that.
                HdAudioEditor(
                    wish = hdAudio,
                    live = liveHdAudio,
                    helperConnected = helperConnected,
                    onChange = { hdAudio = it },
                    enabled = enabled,
                )

                HorizontalDivider()

                CodecEditor(
                    preference = codec,
                    helperConnected = helperConnected,
                    onChange = { codec = it },
                    enabled = enabled,
                    offeredCodecs = offeredCodecs,
                    negotiatedCodec = negotiatedCodec,
                    negotiated = negotiatedStatus,
                    deviceConnected = deviceConnected,
                )

                HorizontalDivider()
            }

            SwitchRow(
                label = "Apply automatically on connect",
                checked = autoApply,
                onCheckedChange = { autoApply = it },
                enabled = enabled,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.save(
                            initial.copy(
                                name = name,
                                calibrationPresetId = presetId,
                                compensationProfileId = compensationId,
                                mediaVolumePercent = if (volumeEnabled) volume.roundToInt() else null,
                                absoluteVolumeEnabled = absoluteEnabled,
                                absoluteVolumeSystemDefault = absoluteSystemDefault,
                                developerOptions = devOptions,
                                codecPreference = codec,
                                hdAudio = hdAudio,
                                autoApply = autoApply,
                            ),
                        )
                    },
                    enabled = enabled,
                ) { Text("Save") }
                if (showDismissActions) {
                    GoldOutlinedButton(onClick = viewModel::stopEditing) { Text("Cancel") }
                    TextButton(onClick = { viewModel.delete(initial.deviceKey) }) { Text("Delete") }
                }
            }
    }
}

/**
 * The Bluetooth developer options, chosen per device.
 *
 * Every option here is the same switch Android's own Developer Options screen
 * writes. Two things are stated rather than implied, because getting either
 * wrong makes the app look broken:
 *
 *  - these are **global** settings, so "per device" means re-applied on
 *    connect, and two headphones with different wishes do not coexist;
 *  - the Bluetooth stack reads them at startup, so a change is stored
 *    immediately and in force only after Bluetooth is cycled.
 *
 * With no wish stored, the field shows the key's **live** value — or "System
 * default" when it is unset, which is what a fresh phone reads. Storing
 * "Use System Default" is different from storing nothing: it actively clears
 * the key on every connect, undoing values any earlier writer left behind.
 *
 * Without the permission the pickers stay on screen, disabled. Returning early
 * removed the whole section instead, which answered a question nobody had
 * asked: the reader wanted to know what these settings *are* and whether their
 * headphone could have them, and got a sentence about a permission and an empty
 * space where the controls should be.
 */
@Composable
private fun DeveloperOptionsEditor(
    selected: Map<String, String>,
    live: Map<String, String?>,
    permissionMissing: Boolean,
    onChange: (String, String) -> Unit,
    enabled: Boolean = true,
) {
    ExplainedHeader(
        "Bluetooth developer options",
        "Android keeps one value for each of these, not one per headphone \u2014 so they " +
            "are re-applied whenever this device connects. If two headphones want " +
            "different values, the last one to connect wins. Writing them needs the " +
            "WRITE_SECURE_SETTINGS permission, which the app's helper grants itself " +
            "as soon as it is running.",
    )
    Text(
        "One value for the whole system, re-applied on connect.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )

    if (permissionMissing) {
        Text(
            "These need system access, which is not granted yet.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    BluetoothDeveloperOptions.all.forEach { option ->
        val current = selected[option.key]
        PickerMenu<String>(
            label = option.label,
            selectedLabel = when (current) {
                null -> systemDefaultLabel(live[option.key]?.let(option::labelFor))
                BluetoothDeveloperOptions.USE_SYSTEM_DEFAULT -> "Use System Default"
                else -> option.labelFor(current)
            },
            options = listOf(
                BluetoothDeveloperOptions.USE_SYSTEM_DEFAULT to "Use System Default",
            ) + option.values.map { it.raw to it.label },
            onSelect = { onChange(option.key, it) },
            enabled = enabled && !permissionMissing,
            explanation = option.explanation,
        )
        if (current != null && option.needsBluetoothRestart) {
            Text(
                "Stored. Turn Bluetooth off and on for it to take effect.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (current != null) {
            option.caution?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Label for a sub-setting standing on "Use System Default": names what the
 * stack currently resolved that default to, when the link is up to tell.
 */
private fun systemDefaultLabel(live: String?): String =
    if (live != null) "System default (now: $live)" else "Use System Default"

/** The wording for a stored codec name, e.g. `"APTX_HD"` becomes "aptX HD". */
private fun codecDisplayName(stored: String): String = when (stored) {
    BluetoothCodecOptions.SYSTEM_DEFAULT -> "System Default"
    else -> CodecFamily.entries.firstOrNull { it.name == stored }?.displayName ?: stored
}

/**
 * Which codec to ask for when this device connects.
 *
 * Unlike the developer options above, this one is genuinely per device — the
 * Android API takes a `BluetoothDevice`, so two headphones really can hold
 * different codecs at the same time. What it is *not* is permanent: the stack
 * renegotiates on every connect, which is why this is stored as a wish and
 * re-applied rather than set once and forgotten.
 *
 * Three things are said outright rather than implied — the first on screen, the
 * other two behind the section's question mark, because they are background
 * rather than news:
 *
 *  - it needs the privileged helper, and without one nothing here can be tried
 *    **or checked** — the section says so instead of offering a control that
 *    would quietly do nothing;
 *  - the codecs offered are the ones this app can *express*, not the ones this
 *    headphone supports. Whether a given headphone accepts one is only known
 *    after asking, and the app reports what it reads back;
 *  - aptX Adaptive is missing on purpose, and the reason is stated, because a
 *    silently absent codec looks like a bug to somebody whose headphones do
 *    support it.
 */
@Composable
private fun CodecEditor(
    preference: CodecPreference?,
    helperConnected: Boolean,
    onChange: (CodecPreference?) -> Unit,
    enabled: Boolean = true,
    /**
     * What the connected headphone offers right now, or null when that could
     * not be established. Null greys out nothing — see
     * [DeviceProfilesViewModel.offeredCodecs].
     */
    offeredCodecs: List<String>? = null,
    /** Whether the watched headphone is connected, regardless of codec readability. */
    deviceConnected: Boolean = false,
    /** What the link is running on right now; null when nothing is connected. */
    negotiatedCodec: String? = null,
    /** Full live status, so sub-settings can name what "default" resolves to. */
    negotiated: CodecStatus? = null,
) {
    ExplainedHeader(
        "Bluetooth codec",
        "Asks the Bluetooth stack to renegotiate this device onto a chosen codec " +
            "whenever it connects. The app then reads the codec back and reports what " +
            "it actually found — a request is not a result. These are the codecs the " +
            "app can ask for, not the ones this headphone advertises. aptX Adaptive is " +
            "not among them: its codec id is a vendor value that has moved between " +
            "Android versions, so it can be read and named but not requested without " +
            "guessing. Setting or reading a codec at all needs the app's helper; " +
            "anything stored here is applied the next time this device connects with " +
            "the helper running.",
    )
    Text(
        "Requested on every connect, then read back.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )

    if (!helperConnected) {
        Text(
            "The helper is not running, so the codec cannot be set or read.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    val unavailableCodecs = unavailableCodecs(offeredCodecs)

    // No "Leave alone" here, unlike every other picker in this editor. A
    // connected headphone always runs on some codec — an A2DP link without one
    // does not exist — so an entry claiming "nothing is set" would describe a
    // state the hardware cannot be in. What the field shows instead is the
    // truth: the stored wish if there is one, otherwise the codec the link is
    // actually running on.
    val shownCodec = codecToShow(preference?.codec, negotiatedCodec)
    val origin = codecOrigin(preference?.codec, negotiatedCodec, deviceConnected)

    PickerMenu<String>(
        label = "Codec on connect",
        // With nothing stored and nothing readable, the honest value of this
        // *field* is System Default — that is what will happen on connect.
        // "Cannot be read" and "Not connected" described the link instead of
        // the setting, which put a failure where a choice belongs; the status
        // line below already carries both of those states.
        selectedLabel = shownCodec?.let(::codecDisplayName) ?: "System Default",
        // System Default leads the list and never greys out: handing the
        // decision back is possible on every device, always.
        options = listOf(BluetoothCodecOptions.SYSTEM_DEFAULT to "System Default") +
            BluetoothCodecOptions.codecs.map { it to codecDisplayName(it) },
        unavailable = unavailableCodecs,
        onSelect = { chosen ->
            onChange(
                when (chosen) {
                    // No sub-settings survive: the whole point is to stop
                    // forcing anything, and isKnown() enforces exactly that.
                    BluetoothCodecOptions.SYSTEM_DEFAULT ->
                        CodecPreference(codec = chosen)
                    // Switching family drops the LDAC quality: it means nothing
                    // on anything else, and a leftover value would make the
                    // stored profile fail its own validity check.
                    else -> (preference ?: CodecPreference(codec = chosen))
                        .copy(codec = chosen)
                        .let { if (chosen == "LDAC") it else it.copy(ldacQuality = 0L) }
                },
            )
        },
        enabled = enabled,
        explanation = "Changing the codec briefly interrupts playback while the link " +
            "renegotiates. The stored choice is requested again on every connect, " +
            "because the stack negotiates afresh each time.",
    )

    // Showing the live codec in a field labelled "on connect" would otherwise
    // read as a stored setting. These two sentences are the difference between
    // "the app will do this" and "this is merely what is happening".
    Text(
        when {
            preference?.codec == BluetoothCodecOptions.SYSTEM_DEFAULT ->
                "Stored — on every connect the codec decision is handed back to Android, " +
                    "un-pinning anything this app set before."
            else -> when (origin) {
                CodecOrigin.STORED -> "Stored — requested every time this device connects."
                CodecOrigin.NEGOTIATED -> "Currently negotiated, not stored. Pick one to store it."
                CodecOrigin.UNREADABLE ->
                    "This headphone is connected, but Android does not tell an ordinary " +
                        "app which codec it negotiated. Start the helper to read it."
                CodecOrigin.NONE -> "No connected device to read a codec from."
            }
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )

    val current = preference ?: return
    // System Default has no sub-settings: forcing a sample rate while handing
    // the codec choice back would be a contradiction, and isKnown() rejects it.
    if (current.codec == BluetoothCodecOptions.SYSTEM_DEFAULT) return

    // Which codecs the list can and cannot contain, and why aptX Adaptive is
    // absent, now live in this section's header explanation. They answer a
    // question that arises once, when somebody hunts for a codec they know
    // their headphone has — not one worth a paragraph above every sub-setting
    // on every visit.

    // 0 means "Use System Default": the write sends the NONE mask, and the
    // stack picks — proven by the diagnostic, which cycles codecs with
    // rate=0/bits=0/channels=0. When the value is 0 and the link is up, the
    // label also names what the stack currently resolved that default to.
    PickerMenu<Int>(
        label = "Sample rate",
        selectedLabel = current.sampleRateHz.takeIf { it > 0 }?.let { "$it Hz" }
            ?: systemDefaultLabel(negotiated?.sampleRateHz?.let { "$it Hz" }),
        options = listOf(0 to "Use System Default") +
            BluetoothCodecOptions.sampleRatesHz.map { it to "$it Hz" },
        onSelect = { onChange(current.copy(sampleRateHz = it)) },
        enabled = enabled,
    )

    PickerMenu<Int>(
        label = "Bit depth",
        selectedLabel = current.bitsPerSample.takeIf { it > 0 }?.let { "$it bit" }
            ?: systemDefaultLabel(negotiated?.bitsPerSample?.let { "$it bit" }),
        options = listOf(0 to "Use System Default") +
            BluetoothCodecOptions.bitsPerSample.map { it to "$it bit" },
        onSelect = { onChange(current.copy(bitsPerSample = it)) },
        enabled = enabled,
    )

    PickerMenu<Int>(
        label = "Channel mode",
        selectedLabel = current.channelMode.takeIf { it > 0 }
            ?.let(BluetoothCodecOptions::channelModeLabel)
            ?: systemDefaultLabel(
                negotiated?.channelMode
                    ?.takeIf { it != ChannelMode.UNKNOWN }
                    ?.let { it.name.lowercase().replaceFirstChar(Char::uppercase) },
            ),
        options = listOf(0 to "Use System Default") +
            BluetoothCodecOptions.channelModes.map {
                it to BluetoothCodecOptions.channelModeLabel(it)
            },
        onSelect = { onChange(current.copy(channelMode = it)) },
        enabled = enabled,
    )

    if (current.codec == "LDAC") {
        PickerMenu<Long>(
            label = "LDAC playback quality",
            selectedLabel = current.ldacQuality.takeIf { it > 0L }
                ?.let(BluetoothCodecOptions::ldacQualityLabel)
                ?: "Use System Default",
            options = listOf(0L to "Use System Default") +
                BluetoothCodecOptions.ldacQualities.map {
                    it to BluetoothCodecOptions.ldacQualityLabel(it)
                },
            onSelect = { onChange(current.copy(ldacQuality = it)) },
            enabled = enabled,
            explanation = "LDAC trades bitrate against link stability. The higher rates " +
                "drop out sooner at distance; adaptive lets the stack choose.",
        )
        Text(
            "Higher LDAC rates drop out sooner at distance.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }

    Text(
        "Changing this briefly interrupts playback.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * HD audio — whether this headphone may use anything better than SBC.
 *
 * The one section in this editor that is about a setting Android really does
 * keep **per device**, and it says so out loud, because every neighbouring
 * section spends a paragraph explaining the opposite. Getting that backwards
 * would have people expecting their other headphones to change too.
 *
 * It sits above the codec picker on purpose: it is the gate in front of the
 * negotiation, not another knob inside it. With HD audio off, asking for LDAC
 * does not fail loudly — the link simply comes up as SBC, and the codec section
 * can only report that the read-back disagreed without being able to say why.
 * That was the one failure mode in the codec section nobody could explain.
 */
@Composable
private fun HdAudioEditor(
    wish: HdAudioPreference?,
    /** What the stack says right now, or null before anything has been read. */
    live: HdAudioState?,
    helperConnected: Boolean,
    onChange: (HdAudioPreference?) -> Unit,
    enabled: Boolean = true,
) {
    ExplainedHeader(
        "HD audio",
        "The switch behind Android's own \"HD audio\" row. Off, this headphone is " +
            "held to SBC no matter what the codec section below asks for — which is " +
            "why it sits above it. Unlike the other Bluetooth settings here, Android " +
            "stores this one per device, so changing it for these headphones leaves " +
            "your others alone. Reading or writing it needs the app's helper: the " +
            "underlying calls are system-only, and the helper runs with the shell's " +
            "privileges.",
    )

    val known = live as? HdAudioState.Known
    Text(
        when {
            !helperConnected ->
                "The helper is not running, so HD audio cannot be set or read."
            live == null -> "Not read yet — connect this device to see its current state."
            live is HdAudioState.Unreadable -> "Cannot be read: ${live.reason}"
            known?.supported == false ->
                "This headphone offers nothing beyond SBC, so the setting changes nothing here."
            known?.enabled == true -> "Currently on for this device."
            known?.enabled == false -> "Currently off — this device is held to SBC."
            // The stack's third state, named rather than rounded to "on". It is
            // what "Use System Default" produces, and it is undone differently.
            else -> "No preference stored — Android decides, which normally means on."
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (helperConnected) {
            MaterialTheme.colorScheme.outline
        } else {
            MaterialTheme.colorScheme.error
        },
    )

    PickerMenu<HdAudioPreference?>(
        label = "On connect",
        selectedLabel = when (wish) {
            null -> systemDefaultLabel(
                known?.let {
                    when (it.enabled) {
                        true -> "on"
                        false -> "off"
                        null -> null
                    }
                },
            )
            else -> HdAudioPreference.label(wish)
        },
        // "Leave alone" leads, because it is what a profile that has never been
        // touched means, and it is the only entry that writes nothing at all.
        options = listOf<Pair<HdAudioPreference?, String>>(
            null to "Leave alone",
            HdAudioPreference.ENABLE to HdAudioPreference.label(HdAudioPreference.ENABLE),
            HdAudioPreference.DISABLE to HdAudioPreference.label(HdAudioPreference.DISABLE),
            HdAudioPreference.SYSTEM_DEFAULT to
                HdAudioPreference.label(HdAudioPreference.SYSTEM_DEFAULT),
        ),
        onSelect = onChange,
        enabled = enabled && helperConnected,
        explanation = "\"Use System Default\" is not the same as \"On\": it clears the " +
            "stored preference so Android applies its own rule again, which is how a " +
            "choice made here is withdrawn rather than merely replaced.",
    )

    if (wish != null) {
        Text(
            "Changing this drops and re-negotiates the link, so playback stops for a moment.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Absolute volume is one *global* Android setting, and the UI says so. The
 * per-device field is a wish applied on connect; the "set it now" button
 * writes the live value.
 *
 * Without WRITE_SECURE_SETTINGS the section says what is true of this app
 * today: the helper grants that permission by itself, so the useful fact is
 * that the helper is not running — not a command to type on a computer. The
 * ADB line survives one layer down, for the case where the helper will not
 * start at all.
 */
@Composable
private fun AbsoluteVolumeEditor(
    status: AbsoluteVolumeStatus,
    wish: Boolean?,
    systemDefault: Boolean,
    onWishChange: (Boolean?) -> Unit,
    onSystemDefault: () -> Unit,
    onWriteNow: (Boolean) -> Unit,
    context: Context,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExplainedHeader(
            "Absolute volume",
            "With absolute volume on, the phone's slider drives the headphone's own " +
                "volume. Off, the phone keeps a separate and finer scale, which helps " +
                "on headphones whose own steps are coarse. Android keeps one value for " +
                "the whole system, so a profile can only ask for it when that device " +
                "connects.",
        )
        Text(
            "One system-wide switch, re-applied when this device connects.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        when (status) {
            is AbsoluteVolumeStatus.Available -> {
                Text(
                    "Currently ${if (status.enabled) "on" else "off"} system-wide.",
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoldOutlinedButton(
                        onClick = { onWriteNow(!status.enabled) },
                        enabled = enabled,
                    ) {
                        Text(if (status.enabled) "Turn off now" else "Turn on now")
                    }
                }
            }

            is AbsoluteVolumeStatus.PermissionMissing -> {
                Text(
                    "The helper grants this automatically. It is not running.",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    when (status.enabled) {
                        true -> "It is currently on system-wide."
                        false -> "It is currently off system-wide."
                        null -> "The current value cannot be read either."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                ExplainedRow(
                    label = "If the helper will not start",
                    explanation = "The same permission can be granted once from a " +
                        "computer over ADB. It is the fallback, not the route: the " +
                        "helper grants it on its own the moment it connects, and this " +
                        "line is only useful when it never does.",
                    // No control of its own: the question mark carries the whole
                    // row, and what it explains is the command printed below.
                    control = {},
                )
                Text(
                    "Fallback — run this once from a computer:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                CopyableCommand(context, status.adbCommand)
            }

            AbsoluteVolumeStatus.Unsupported -> Text(
                "This Android build does not expose the absolute-volume setting.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        PickerMenu(
            label = "On connect",
            selectedLabel = when {
                // An actual instruction: delete the key on every connect.
                systemDefault -> "Use System Default"
                wish == true -> "Turn on"
                wish == false -> "Turn off"
                // Nothing stored at all — name what is in force right now
                // rather than claiming a setting that was never made.
                else -> systemDefaultLabel(
                    (status as? AbsoluteVolumeStatus.Available)
                        ?.enabled?.let { if (it) "on" else "off" },
                )
            },
            options = listOf<Pair<Boolean?, String>>(
                null to "Use System Default",
                true to "Turn on",
                false to "Turn off",
            ),
            onSelect = { chosen -> if (chosen == null) onSystemDefault() else onWishChange(chosen) },
            enabled = enabled,
        )
        if (systemDefault) {
            Text(
                "On connect the app deletes this setting, so Android's own default " +
                    "applies again — including undoing a value something else left behind.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

// ---- small shared pieces ----------------------------------------------------

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Minimal dropdown; Material 3's ExposedDropdownMenu is overkill for these. */
@Composable
private fun <T> PickerMenu(
    label: String,
    selectedLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    /**
     * The long answer for this one control, behind a question mark.
     *
     * Given here rather than as a paragraph under the picker because that is
     * what turned this editor into a wall: every control carried its own
     * explanation, always open, and the controls themselves were what the
     * reader had come for.
     */
    explanation: String? = null,
    /**
     * Values this *device* cannot do — as opposed to values the app declines to
     * offer, which simply are not in [options] at all.
     *
     * They stay in the list on purpose. Dropping them would leave someone who
     * knows their headphone supports aptX HD hunting for a menu entry that
     * silently vanished; showing them as equals is what let a codec be chosen,
     * requested twice and ignored by the stack with nothing on screen to
     * explain it. So they sink to the bottom, grey out, and say why when
     * tapped.
     */
    unavailable: Set<T> = emptySet(),
) {
    var expanded by remember { mutableStateOf(false) }
    var showUnavailableNotice by remember { mutableStateOf(false) }

    val ordered = orderByAvailability(options, unavailable)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (explanation != null) {
            // The control slot stays empty on purpose: the thing being explained
            // is the button on the next line, and putting it inside the row
            // would lay a dropdown out as though it were a switch.
            ExplainedRow(label = label, explanation = explanation, control = {})
        } else {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.outline,
            )
        }
        GoldOutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text(selectedLabel) }
        // `expanded` cannot be set while disabled, but a menu left open when the
        // card switches to disabled would outlive the control that opened it.
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            ordered.forEach { (value, text) ->
                val blocked = value in unavailable
                DropdownMenuItem(
                    text = {
                        Text(
                            text,
                            color = if (blocked) {
                                MaterialTheme.colorScheme.outline
                            } else {
                                Color.Unspecified
                            },
                        )
                    },
                    // Deliberately still clickable. `enabled = false` would grey
                    // the row and swallow the tap, and a control that does
                    // nothing at all is exactly what leaves people guessing.
                    onClick = {
                        if (blocked) {
                            showUnavailableNotice = true
                        } else {
                            onSelect(value)
                            expanded = false
                        }
                    },
                )
            }
        }
    }

    if (showUnavailableNotice) {
        AlertDialog(
            onDismissRequest = { showUnavailableNotice = false },
            title = { Text("Not available on this headphone") },
            text = {
                Text(
                    "Your headphone did not offer this codec in the current " +
                        "negotiation. It stays listed so you can see it was considered.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showUnavailableNotice = false }) { Text("OK") }
            },
        )
    }
}

@Composable
internal fun CopyableCommand(context: Context, command: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(command, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ADB command", command))
            },
        ) { Text("Copy command") }
    }
}
