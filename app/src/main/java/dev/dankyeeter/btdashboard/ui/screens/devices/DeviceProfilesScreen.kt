package dev.dankyeeter.btdashboard.ui.screens.devices

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dankyeeter.btdashboard.system.devices.AbsoluteVolumeStatus
import dev.dankyeeter.btdashboard.system.devices.ApplyResult
import dev.dankyeeter.btdashboard.system.devices.DeviceProfile
import dev.dankyeeter.btdashboard.ui.icons.DeviceIcons
import kotlin.math.roundToInt
import dev.dankyeeter.btdashboard.ui.theme.GoldCard
import dev.dankyeeter.btdashboard.ui.theme.GoldTitle
import dev.dankyeeter.btdashboard.ui.theme.GoldButton
import dev.dankyeeter.btdashboard.ui.theme.GoldOutlinedButton

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
    val profiles by viewModel.profiles.collectAsState()
    val bonded by viewModel.bonded.collectAsState()
    val editing by viewModel.editing.collectAsState()
    val message by viewModel.message.collectAsState()
    val lastAutoApply by viewModel.lastAutoApply.collectAsState()

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
        GoldTitle("Device profiles", style = MaterialTheme.typography.headlineSmall)
        Text(
            "When one of these headphones connects, the app applies its profile: " +
                "compensation curve, media volume, absolute-volume preference. " +
                "Addresses are stored hashed — the raw MAC is never written down.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        lastAutoApply?.let { AutoApplyCard(it) }

        if (!viewModel.hasBluetoothPermission()) {
            GoldCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoldTitle("Bluetooth access missing")
                    Text(
                        "Without BLUETOOTH_CONNECT the paired-device list stays empty. " +
                            "Profiles for devices already seen still work.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
        }

        message?.let { text ->
            GoldCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") }
                }
            }
        }

        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun AutoApplyCard(result: ApplyResult) {
    val text = when (result) {
        is ApplyResult.Applied ->
            "Last connect: applied \"${result.profile.name}\" (${result.actions.size} action(s))."
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
    GoldCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                GoldButton(onClick = onEdit) { Text(if (profile == null) "Create profile" else "Edit") }
                if (profile != null) GoldOutlinedButton(onClick = onApply) { Text("Apply now") }
            }
        }
    }
}

private fun summarise(profile: DeviceProfile?, device: KnownDevice): String {
    if (profile == null) return if (device.bonded) "Paired · no profile yet" else "No profile yet"
    val parts = buildList {
        if (profile.compensationProfileId != null) add("compensation")
        profile.mediaVolumePercent?.let { add("volume $it %") }
        profile.absoluteVolumeEnabled?.let { add("absolute volume ${if (it) "on" else "off"}") }
        if (!profile.autoApply) add("auto-apply off")
    }
    return if (parts.isEmpty()) "Nothing set — connecting changes nothing" else parts.joinToString(" · ")
}

// ---- editor -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorCard(initial: DeviceProfile, viewModel: DeviceProfilesViewModel) {
    val compensationProfiles by viewModel.compensationProfiles.collectAsState()
    val absoluteStatus by viewModel.absoluteVolumeStatus.collectAsState()
    val context = LocalContext.current

    var name by remember(initial.deviceKey) { mutableStateOf(initial.name) }
    var presetId by remember(initial.deviceKey) { mutableStateOf(initial.calibrationPresetId) }
    var compensationId by remember(initial.deviceKey) { mutableStateOf(initial.compensationProfileId) }
    var volumeEnabled by remember(initial.deviceKey) { mutableStateOf(initial.mediaVolumePercent != null) }
    var volume by remember(initial.deviceKey) { mutableStateOf((initial.mediaVolumePercent ?: 60).toFloat()) }
    var absoluteEnabled by remember(initial.deviceKey) { mutableStateOf(initial.absoluteVolumeEnabled) }
    var autoApply by remember(initial.deviceKey) { mutableStateOf(initial.autoApply) }

    GoldCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GoldTitle("Edit profile")

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(DeviceProfile.MAX_NAME_LENGTH) },
                label = { Text("Name") },
                singleLine = true,
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
                    label = "Device type / icon",
                    selectedLabel = viewModel.calibrationPresets
                        .firstOrNull { it.id == presetId }?.displayName ?: "Generic",
                    options = listOf<Pair<String?, String>>(null to "Generic") +
                        viewModel.calibrationPresets.map { it.id as String? to it.displayName },
                    onSelect = { presetId = it },
                )
            }

            PickerMenu(
                label = "EQ preset",
                selectedLabel = compensationProfiles.firstOrNull { it.id == compensationId }?.name
                    ?: "Leave alone",
                options = listOf<Pair<String?, String>>(null to "Leave alone") +
                    compensationProfiles.map { it.id as String? to it.name },
                onSelect = { compensationId = it },
            )
            if (compensationProfiles.isEmpty()) {
                Text(
                    "No saved presets yet. Set a curve on the EQ screen — by hand or from " +
                        "a hearing test — save it under a name, then bind it here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            HorizontalDivider()

            SwitchRow(
                label = "Set media volume on connect",
                checked = volumeEnabled,
                onCheckedChange = { volumeEnabled = it },
            )
            if (volumeEnabled) {
                Text("${volume.roundToInt()} %", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    valueRange = 0f..100f,
                    steps = 19,
                )
            }

            HorizontalDivider()

            AbsoluteVolumeEditor(
                status = absoluteStatus,
                wish = absoluteEnabled,
                onWishChange = { absoluteEnabled = it },
                onWriteNow = viewModel::setAbsoluteVolumeNow,
                context = context,
            )

            HorizontalDivider()

            SwitchRow(
                label = "Apply automatically on connect",
                checked = autoApply,
                onCheckedChange = { autoApply = it },
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
                                autoApply = autoApply,
                            ),
                        )
                    },
                ) { Text("Save") }
                GoldOutlinedButton(onClick = viewModel::stopEditing) { Text("Cancel") }
                TextButton(onClick = { viewModel.delete(initial.deviceKey) }) { Text("Delete") }
            }
        }
    }
}

/**
 * Absolute volume is one *global* Android setting, and the UI says so. The
 * per-device field is a wish applied on connect; the "set it now" button
 * writes the live value, and both disappear behind an honest explanation when
 * WRITE_SECURE_SETTINGS is missing.
 */
@Composable
private fun AbsoluteVolumeEditor(
    status: AbsoluteVolumeStatus,
    wish: Boolean?,
    onWishChange: (Boolean?) -> Unit,
    onWriteNow: (Boolean) -> Unit,
    context: Context,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Absolute volume", style = MaterialTheme.typography.titleSmall)
        Text(
            "With absolute volume on, the phone's volume slider drives the headphone's " +
                "own volume. Turning it off gives the phone a separate, finer scale — " +
                "which helps on devices whose own steps are too coarse. This is a " +
                "single system-wide Android setting, so a profile can only ask for it " +
                "when that device connects.",
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
                    GoldOutlinedButton(onClick = { onWriteNow(!status.enabled) }) {
                        Text(if (status.enabled) "Turn off now" else "Turn on now")
                    }
                }
            }

            is AbsoluteVolumeStatus.PermissionMissing -> {
                Text(
                    buildString {
                        append("WRITE_SECURE_SETTINGS is not granted, so the app cannot change ")
                        append("this. ")
                        append(
                            when (status.enabled) {
                                true -> "It is currently on system-wide."
                                false -> "It is currently off system-wide."
                                null -> "The current value could not be read either."
                            },
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
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
            selectedLabel = when (wish) {
                null -> "Leave alone"
                true -> "Turn on"
                false -> "Turn off"
            },
            options = listOf<Pair<Boolean?, String>>(
                null to "Leave alone",
                true to "Turn on",
                false to "Turn off",
            ),
            onSelect = onWishChange,
        )
    }
}

// ---- small shared pieces ----------------------------------------------------

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Minimal dropdown; Material 3's ExposedDropdownMenu is overkill for these. */
@Composable
private fun <T> PickerMenu(
    label: String,
    selectedLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        GoldOutlinedButton(onClick = { expanded = true }) { Text(selectedLabel) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
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
