# Helper disclosure — sentences for `HelperDisclosure` (AD-051)

Source of truth for the text the app shows before it connects to the phone's
debugging service, and before the PC command is shown (D3). `HelperDisclosure`
takes these sentences over verbatim; language English, like the app. Layout
(title, buttons, order on screen) belongs to `UI_SPEC.md` (T-074b), not to this
file.

Checked against the code on 29.09.2026 (commit `2397416`): the four commands
in `PrivilegedProtocol.ALLOWED`, the five operations with `mutates = true` in
`PrivilegedProtocol.PrivilegedOperation`, the six `Settings.Global` keys
written by `BluetoothDeveloperOptions.all`, `AbsoluteVolumeGate` and
`WirelessDebuggingSwitch`. Not run on a device.

## Counts that `HelperDisclosureTest` must use as literals

| Member | Count | Source in code |
|---|---|---|
| `readCommands` | **4** | `PrivilegedProtocol.ALLOWED` |
| `helperActions` | **5** | `PrivilegedOperation` with `mutates = true` |
| `writtenSettings` | **6** | 4 in `BluetoothDeveloperOptions.all`, `bluetooth_disable_absolute_volume`, `adb_wifi_enabled` |

**Deviation from AD-051:** AD-051 counts 4 mutating operations. The enum has
five: `SET_CODEC_PREFERENCE`, `SET_OPTIONAL_CODECS`, `RESTART_BLUETOOTH`,
`GRANT_SECURE_SETTINGS` and `SHUTDOWN`. A test written against
`entries.filter { it.mutates }` fails with four sentences. The fifth sentence
is below.

## 1. Introduction (`intro`)

> To work fully, the app starts a helper. The helper is a small program that is
> part of this app. It runs on your phone with the same rights as Android's
> debugging shell ("adb shell"). It is not root, and it does not download any
> code: it is inside the app you installed.

> The app starts the helper without a computer, through Android's Wireless
> debugging. Setup turns on Developer options and Wireless debugging, and you
> pair the app once with the code that Android shows.

> The app connects to the debugging service on this same phone (127.0.0.1).
> Nothing is sent to the internet. Android requires the internet permission
> even for this connection.

> The helper only does what is listed here. It cannot run any other command.

## 2. What the helper reads (`readCommands`, key = the command, 4 entries)

| Key (`List<String>`) | Sentence |
|---|---|
| `dumpsys bluetooth_manager` | Reads the Bluetooth service's status report (dumpsys bluetooth_manager): connected devices, codec and link state. |
| `dumpsys media.audio_flinger` | Reads the audio mixer's status report (dumpsys media.audio_flinger): which audio effects and sessions are active, including other apps' equalizers. |
| `dumpsys audio` | Reads the audio service's status report (dumpsys audio): which audio sessions are playing music right now. Only session numbers are kept. |
| `ps -A -o PID,NAME` | Reads the list of running processes (ps -A): to find other equalizer processes. |

Additional read-only sentence, not covered by the test (these are separate
operations, not entries of `ALLOWED`):

> The helper also reads a headphone's current Bluetooth codec and its HD-audio
> state through Android's Bluetooth interface. This changes nothing.

## 3. What the helper changes (`helperActions`, key = `PrivilegedOperation`, 5 entries)

| Key | Sentence |
|---|---|
| `SET_CODEC_PREFERENCE` | Asks Android's Bluetooth service to renegotiate the audio codec with a headphone (codec, sample rate, bit depth, quality level). The sound can drop out for a moment. |
| `SET_OPTIONAL_CODECS` | Turns HD audio (optional codecs) on or off for one headphone, or hands the decision back to Android. The Bluetooth link reconnects, which you can hear. |
| `RESTART_BLUETOOTH` | Turns Bluetooth off and on again. Connected headphones disconnect and reconnect. |
| `GRANT_SECURE_SETTINGS` | Gives this app the Android permission WRITE_SECURE_SETTINGS (the command is: pm grant dev.dankyeeter.btdashboard android.permission.WRITE_SECURE_SETTINGS). This happens automatically every time the helper connects to the app. The permission stays until you uninstall the app. |
| `SHUTDOWN` | Stops the helper when the app asks it to, for example when a newer helper takes over. |

## 4. What the app writes to Android settings (`writtenSettings`, key = setting name, 6 entries)

| Key | Sentence |
|---|---|
| `bluetooth_avrcp_version` | Bluetooth remote-control version (AVRCP). Only when you choose a version in a device profile. Takes effect after Bluetooth restarts. |
| `bluetooth_map_version` | Bluetooth message-access version (MAP). Only when you choose a version in a device profile. Takes effect after Bluetooth restarts. |
| `bluetooth_pbap_client_version` | Bluetooth phone-book-access version (PBAP client). Only when you choose a version in a device profile. Takes effect after Bluetooth restarts. |
| `bluetooth_show_devices_without_names` | Whether Bluetooth lists nearby devices that send no name. Only when you choose it in a device profile. |
| `bluetooth_disable_absolute_volume` | Switches Bluetooth absolute volume on or off for the whole phone. Only when you choose it, in the app or in a device profile. |
| `adb_wifi_enabled` | Turns Wireless debugging on, to start the helper, and off again once the helper has connected. This also switches it off if you had switched it on yourself. |

Closing sentence for this block:

> Before the app changes one of these settings for the first time, it records
> the previous value. "Back to before" in the app puts it back.

## 5. How long the helper runs, and how it comes back (`lifecycle`)

> The helper runs until the phone restarts. After a restart, if the equalizer
> is switched on, the app starts the helper again on its own: it turns Wireless
> debugging on, connects with the pairing it stored, starts the helper, and
> turns Wireless debugging off again. This needs a Wi-Fi connection.

## 6. How to take it back (`revoke`)

> To stop the app from starting the helper again: open Android's Settings,
> Developer options, Wireless debugging, and remove the paired device you added
> for this app under "Paired devices". Menu names differ between phone makers.

> A running helper stops with the next restart of the phone. The permission
> WRITE_SECURE_SETTINGS stays until you uninstall the app.

> If you uninstall the app, restart the phone afterwards. An uninstall alone
> does not stop a running helper, and files the helper wrote to
> /data/local/tmp stay behind. This is a known issue.

**Remove the last paragraph once SR-001, SR-009 and SR-016 are closed** and
the same statement in `privacy.txt` section 7 and in `STORE_TEXT.md`. Text and
behavior must change together (T-006 is the prerequisite of the submission).

## 7. PC route (D3): what is shown before the command

The PC route uses the same disclosure dialog as the phone route (Director,
29.09.2026; `UI_SPEC.md`, section "AD-051", 3b): one component, one text, one
flag. Confirming in the dialog sets the flag and only then shows the command
and the token. One additional sentence above the command, shown once the flag
is set:

> This command starts the same helper from a computer. It does exactly what
> the list you agreed to says, and nothing else.

## 8. Notes for H-3 and the wording test

- **Wordlist patterns (Director, 29.09.2026).** The entries `cure` and
  `diagnos` of C-002 Befund 8 are matched as `\bcure\b` and
  `diagnos(e|es|ed|ing|is)\b`. Reason, counted 29.09.2026 in `app/src/main`
  (single-line literals, case-insensitive): as plain substrings `cure` matches
  12 lines (`"secure settings: …"` log lines; `WRITE_SECURE_SETTINGS` in
  sections 3 and 6 also contains it) and `diagnos` matches `EventLog.kt:90`
  and `:103` ("Diagnostics", a Monitor filter unrelated to hearing). With the
  patterns above, none of the sentences in this file matches a wordlist entry.
- Sentence 4 in section 1 ("The helper only does what is listed here") is
  only true while `ALLOWED` and `PrivilegedOperation` match the list. That is
  what the three count literals are for.
