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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.dankyeeter.btdashboard.system.devices.CodecPreference
import dev.dankyeeter.btdashboard.system.devices.DeviceProfile
import dev.dankyeeter.btdashboard.system.devices.ProfileAction
import dev.dankyeeter.btdashboard.ui.icons.DeviceIcons
import kotlin.math.roundToInt
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
        Text(
            "When one of these headphones connects, the app applies its profile: " +
                "compensation curve, media volume, absolute-volume preference, " +
                "Bluetooth codec. Addresses are stored hashed — the raw MAC is " +
                "never written down.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        lastAutoApply?.let { AutoApplyCard(it) }

        if (!viewModel.hasBluetoothPermission()) {
            Panel {
                    PanelHeader("Bluetooth access missing")
                    Text(
                        "Without BLUETOOTH_CONNECT the paired-device list stays empty. " +
                            "Profiles for devices already seen still work.",
                        style = MaterialTheme.typography.bodySmall,
                    )
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
            Panel {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") }
            }
        }

        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun AutoApplyCard(result: ApplyResult) {
    val text = when (result) {
        // "Applied (n actions)" counted the unconfirmed ones too. Observed on a
        // Focal Bathys: the profile asked for aptX HD, the headphone does not
        // offer it in that negotiation, the stack ignored the request — and the
        // line still read "applied (2 actions)". Requesting is not applying,
        // and this screen is the only place that ever says what happened.
        is ApplyResult.Applied -> {
            val unconfirmed = result.actions.count { it is ProfileAction.CodecNotObserved }
            val confirmed = result.actions.size - unconfirmed
            buildString {
                append("Last connect: applied \"${result.profile.name}\" ")
                append("($confirmed action(s)")
                if (unconfirmed > 0) append(", $unconfirmed requested but not confirmed")
                append(").")
            }
        }
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
                GoldButton(onClick = onEdit) { Text(if (profile == null) "Create profile" else "Edit") }
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
) {
    val compensationProfiles by viewModel.compensationProfiles.collectAsStateWithLifecycle()
    val absoluteStatus by viewModel.absoluteVolumeStatus.collectAsStateWithLifecycle()
    val helperConnected by viewModel.helperConnected.collectAsStateWithLifecycle()
    val offeredCodecs by viewModel.offeredCodecs.collectAsStateWithLifecycle()
    val negotiatedCodec by viewModel.negotiatedCodec.collectAsStateWithLifecycle()
    val negotiatedStatus by viewModel.negotiatedStatus.collectAsStateWithLifecycle()
    val deviceConnected by viewModel.deviceConnected.collectAsStateWithLifecycle()
    val liveDevOptions by viewModel.liveDevOptions.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Asked again every time this card appears for a device, and never cached:
    // which codecs are selectable is a property of the current negotiation, not
    // of the headphone.
    LaunchedEffect(initial.deviceKey) { viewModel.loadOfferedCodecs(initial.deviceKey) }

    var name by remember(initial.deviceKey) { mutableStateOf(initial.name) }
    var presetId by remember(initial.deviceKey) { mutableStateOf(initial.calibrationPresetId) }
    var compensationId by remember(initial.deviceKey) { mutableStateOf(initial.compensationProfileId) }
    var volumeEnabled by remember(initial.deviceKey) { mutableStateOf(initial.mediaVolumePercent != null) }
    var volume by remember(initial.deviceKey) { mutableStateOf((initial.mediaVolumePercent ?: 60).toFloat()) }
    var absoluteEnabled by remember(initial.deviceKey) { mutableStateOf(initial.absoluteVolumeEnabled) }
    var absoluteSystemDefault by remember(initial.deviceKey) { mutableStateOf(initial.absoluteVolumeSystemDefault) }
    var autoApply by remember(initial.deviceKey) { mutableStateOf(initial.autoApply) }
    var devOptions by remember(initial.deviceKey) { mutableStateOf(initial.developerOptions) }
    var codec by remember(initial.deviceKey) { mutableStateOf(initial.codecPreference) }

    Panel {
            PanelHeader(header)

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
                    label = "Device type / icon",
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
                onWriteNow = viewModel::setAbsoluteVolumeNow,
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
 */
@Composable
private fun DeveloperOptionsEditor(
    selected: Map<String, String>,
    live: Map<String, String?>,
    permissionMissing: Boolean,
    onChange: (String, String) -> Unit,
    enabled: Boolean = true,
) {
    Text("Bluetooth developer options", style = MaterialTheme.typography.titleSmall)
    Text(
        "Android keeps one value for each of these, not one per headphone \u2014 so they " +
            "are re-applied whenever this device connects. If two headphones want " +
            "different values, the last one to connect wins.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )

    if (permissionMissing) {
        Text(
            "WRITE_SECURE_SETTINGS is not granted, so none of these can be written. " +
                "Grant it in the setup wizard first.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
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
            enabled = enabled,
        )
        Text(
            option.explanation,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
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
 * Three things are said outright rather than implied:
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
    Text("Bluetooth codec", style = MaterialTheme.typography.titleSmall)
    Text(
        "Asks the Bluetooth stack to renegotiate this device onto a chosen codec " +
            "whenever it connects. The app then reads the codec back and reports what " +
            "it actually found — a request is not a result.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )

    if (!helperConnected) {
        Text(
            "The privileged helper is not running, so the codec can be neither set nor " +
                "checked. Start it from the setup screen; anything stored here is applied " +
                "the next time this device connects with the helper running.",
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
        selectedLabel = shownCodec?.let(::codecDisplayName)
            ?: if (origin == CodecOrigin.UNREADABLE) "Cannot be read" else "Not connected",
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

    Text(
        "These are the codecs the app can ask for, not the ones this headphone " +
            "advertises. aptX Adaptive is not among them: its codec id is a vendor value " +
            "that has moved between Android versions, so it can be read and named but not " +
            "requested without guessing.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )

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
        )
        Text(
            "LDAC trades bitrate against link stability. The higher rates drop out " +
                "sooner at distance; adaptive lets the stack choose.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }

    Text(
        "Changing the codec briefly interrupts playback while the link renegotiates.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    systemDefault: Boolean,
    onWishChange: (Boolean?) -> Unit,
    onSystemDefault: () -> Unit,
    onWriteNow: (Boolean) -> Unit,
    context: Context,
    enabled: Boolean = true,
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
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.outline,
        )
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
            text = { Text("Setting not available for this device") },
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
