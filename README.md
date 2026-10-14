# DankYeeter's Bluetooth Dashboard

An equalizer for Bluetooth headphones on Android that shapes the sound to your
personal hearing profile — taken from a self-test on your own headphones or
from an audiogram you type in — plus the Bluetooth link readouts that audio
nerds actually want and no consumer app shows.

Two halves, one app:

- **Hearing-profile-driven system EQ.** A guided per-ear threshold test
  produces an audiogram; the audiogram produces a sound-adjustment curve; the
  curve is applied to *all* audio leaving the phone, not just this app's.
- **Bluetooth link-quality monitor.** Codec, negotiated bitrate, RSSI,
  retransmissions and takeover events over time, so a dropout you heard can be
  matched against what the link was doing at that second.

Kotlin · Jetpack Compose · Material 3 · Oboe/NDK · own privileged helper (no
Shizuku, no root). No cloud, no analytics — see [Privacy](#privacy) for what
the one network-related permission is actually for.

> ### Disclaimer
>
> The purpose of this app is **sound adjusted to a personal hearing profile**.
> It is **audiometry-inspired consumer calibration without clinical
> validity**. It is not a hearing test in the medical sense, not a hearing aid,
> not a diagnostic tool, and not a medical device; it determines nothing about
> your health and treats nothing. Its numbers are meaningful only relative to
> each other, on the headphone they were measured with, in the listening mode
> they were measured in — an uncalibrated transducer in an uncontrolled room
> cannot produce absolute hearing thresholds. If you have any concern about
> your hearing, see an audiologist.

---

## Features

**Hearing test**

- Modified Hughson-Westlake protocol (5-up/10-down, threshold at ≥2/3 hits),
  250–8000 Hz, per ear.
- Sample-accurate tone generation with strict left/right isolation; all level
  steps are digital, never via system volume.
- Ambient-noise pre-check through the microphone before a run — a warning, not
  a blocker.
- Media volume is locked for the duration of a run; external volume changes
  invalidate it instead of silently corrupting the result.
- Fit check before each run (mandatory for IEMs), comparing a low-frequency
  probe against your stored baseline.
- Multi-run workflow: run the test several times, overlay the audiograms, use
  the per-frequency median, delete and retake outliers.

**Equalizer**

- 10 bands **per channel** via Android's `DynamicsProcessing` effect — a curve
  per ear, not a stereo compromise.
- NAL-R-derived partial sound adjustment with an intensity slider
  (Mimi-style), so the curve can be dialled back to taste.
- Automatic negative pre-gain for headroom, plus an optional peak limiter.
- A/B toggle between flat and adjusted for validation listening.
- Named profiles, and bands outside the tested range are labelled as
  extrapolated rather than presented as measurements.
- Foreign-EQ detection: warns when another equalizer (Wavelet, an OEM app) is
  stacking with ours, with the offending app named.

**Bluetooth**

- Codec dashboard: negotiated codec, bitrate, sample rate, defensively read.
- Link-quality monitor with a Canvas timeline; adaptive sampling that idles to
  nothing when nothing is playing, plus a "watch live" deep-capture mode.
- Active-device takeover log — which device grabbed the audio stream and when.
- AirPods BLE beacon decoding: per-bud and case battery, in-ear wear state,
  model identification (which selects the matching calibration preset).
- Per-device profiles: when a known headphone connects, its sound profile,
  media volume and absolute-volume preference are applied
  automatically. A profile stores a hashed key of the device address, not the
  address itself (the 24-hour link history does store addresses, see
  [Privacy](#privacy)). Every
  setting the app changes this way can be put back with one "Back to
  before" tap; if the record of what changed cannot be read, a "Forget this
  list" option gives up on restoring it rather than staying stuck.
- Absolute-volume toggle, when `WRITE_SECURE_SETTINGS` has been granted.
- Guided A/B comparison of two link conditions (e.g. two LDAC quality
  levels), with the same measurement window and duration on both sides.
  Keeps running if the screen turns off, through a notification with its own
  Stop button.
- "Measures" panel: for each of the six evidence-backed tuning measures
  (moving away from other 2.4 GHz Wi-Fi, removing a competing Bluetooth
  device, …), the app reads the phone's current state and reports it in the
  same wording the comparison uses — never as a pass/fail score — with a
  jump-to-settings button where Android has a page for it (Wi-Fi or
  Bluetooth settings).

**Everything else**

- Local JSON export/import of hearing runs and profiles, for moving to another
  phone.
- Calibration presets for Focal Bathys, Noble FoKus Prestige Encore, Sennheiser
  Momentum 4 and the AirPods IEM lineup, each recording its data source and
  measurement rig — presets are shape corrections, never absolute levels.
- First-run setup wizard that checks every requirement live and lets you skip
  any of them.
- Light, Dark and "Edgy" (true black + gold) themes.

## Screenshots

_To be added._

<!--
Planned: dashboard, EQ per-ear view, audiogram chart, monitor timeline,
setup wizard. Store them in docs/screenshots/ and link them here.
-->

## Installation

**Where to get it.** A release on Google Play is planned (European Union and
European Economic Area only, one-time purchase in the store). It is **not
published yet**. The only file on the [Releases](../../releases) page is
`btdashboard-v0.2.0.apk` (18 August 2026); it is an older version that still
depends on Shizuku and has none of the helper described below, so it does not
match this README. Until a current build is published, the way to get a
matching app is to build it yourself (see [Building](#building)).

With a current build in hand:

1. Open the APK on the phone and allow installation from this source when
   Android asks.
2. Launch the app. The **setup wizard** starts automatically and walks you
   through four steps: Bluetooth permission, microphone (for the ambient
   check), notifications, and the app's own helper (see
   [App helper](#app-helper) below). Every step re-checks itself live. Only
   the microphone step can be skipped — Bluetooth and notifications cannot,
   because the app cannot work without the first and cannot even finish this
   wizard without the second.
3. Set the EQ inside your headphone's own app (Focal & Naim, Noble FoKus, …) to
   **Flat**. That EQ runs in the headphone's DSP *after* Bluetooth
   transmission, so it stacks with ours instead of replacing it. This app never
   touches another app's stored settings.

Minimum Android 12 (API 31).

You can re-open the wizard any time from Settings → **Setup** panel
("Review setup" / "Finish setup"). The separate **System access** panel next
to it reopens the helper screen described below without going through the
whole wizard.

## App helper

Android does not let an ordinary app equalize other apps' audio or read the
Bluetooth stack's negotiated codec. Both need shell-level access (the same
level `adb shell` has, not root), and this app ships its own helper for that
instead of using a separate tool such as Shizuku — that used to be documented
here and was removed.

The helper is started from **the phone itself**, through Android's own
wireless-debugging pairing, at the wizard's last step ("App helper") or later
from Settings → **System access**. _The steps below are transcribed
from the app's own source and its on-screen text, not executed on a device in
this session — mark them ungeprüft if you hit a difference:_

1. Tap **Activate**. The first time, it asks you to turn on developer
   options and wireless debugging:
   1. Settings → About phone → tap **Build number** seven times.
   2. Settings → System → Developer options → **Wireless debugging: on**.
   3. Open **"Pair device with pairing code"** and leave that dialog open.
   4. Type the six digits shown there into the app.
2. The app pairs with the phone's own debugging service and starts the
   helper. You know it worked when the screen shows **"Helper running."** and
   the wizard step's status changes to **"Done"**.

Before the first connection the app shows a dialog about the connection to the
phone's own debugging service and asks you to agree.

Pairing is needed once per phone. **The helper stops when the phone
restarts.** If the equalizer is switched on, the app then starts it again on
its own, without a code, because the pairing is remembered: it switches
wireless debugging on, connects to it on the phone, starts the helper and
switches wireless debugging off again. This needs a Wi-Fi connection and the
`WRITE_SECURE_SETTINGS` permission (see below). _Read from the source
(`BootReceiver`, `HelperAutoStart`), not run on a device in this session._ If
it does not work — for example without Wi-Fi — the app posts a notification,
and one tap on "Activate" (notification or System access screen) starts the
helper, again without a code.

### What the helper does

The complete, exact list is in [`docs/release/HELPER_DISCLOSURE.md`](docs/release/HELPER_DISCLOSURE.md);
in short:

- It is a small program inside the app's APK that runs with the rights of
  `adb shell` (not root). It downloads no code and runs only the commands and
  operations below.
- It **reads**: four commands — `dumpsys bluetooth_manager`,
  `dumpsys media.audio_flinger`, `dumpsys audio`, `ps -A -o PID,NAME` — plus a
  headphone's current codec and HD-audio state.
- It **changes**: the Bluetooth codec of one headphone, HD audio on or off for
  one headphone, a Bluetooth restart, granting `WRITE_SECURE_SETTINGS` to the
  app, and stopping itself.
- The app **writes six Android settings** (with the previous value recorded
  first, so "Back to before" can restore it): `bluetooth_avrcp_version`,
  `bluetooth_map_version`, `bluetooth_pbap_client_version`,
  `bluetooth_show_devices_without_names`, `bluetooth_disable_absolute_volume`
  and `adb_wifi_enabled` (wireless debugging itself).
- **Taking it back:** remove the paired device under Settings → Developer
  options → Wireless debugging → Paired devices. A running helper ends with the
  next restart. See [Known issue](#known-issue-after-uninstalling) for
  uninstalling.

**Fallback, only if pairing will not work:** the System access screen also
shows a ready-to-run command, with a copy button, that does the same thing
from a computer with `adb` installed:

```
adb shell '<command with a one-time token, shown in the app>'
```

**Without a running helper the app still works**, with less: the EQ attaches
only to players that broadcast `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`
(Tidal does not do that reliably), the codec reads as unknown, and the
foreign-EQ check reports "cannot check" instead of an all-clear. The
System access screen names exactly which of these are affected, rather than
hiding the difference. _Not verified against a device in this session; based
on the setup-wizard and system-access source._

### `WRITE_SECURE_SETTINGS`

Needed to write the six Android settings listed above: the Bluetooth
developer options and the absolute-volume toggle, and switching wireless
debugging on and off for the automatic restart of the helper after a reboot.
The helper grants it automatically every time it connects, and — unlike the
helper — the grant survives reboots; it ends when you uninstall the app. If
the helper cannot start on your phone, you can grant it once from a computer
instead:

```
adb shell pm grant dev.dankyeeter.btdashboard android.permission.WRITE_SECURE_SETTINGS
```

The System access screen shows the same command with a copy button.

## Privacy

- **The app does declare `INTERNET`, but only to reach itself.** Starting the
  helper without a computer (see [App helper](#app-helper)) needs a socket,
  and Android gates socket creation — loopback included — behind this
  permission. The connection this app opens always goes to `127.0.0.1`, never
  to an address announced over the network, so nothing can leave the device
  through it even if something else on the network claims to be an adb
  daemon. There is no HTTP client, no analytics, no crash reporter, no cloud
  sync and no update check anywhere in this project — that claim now rests on
  the code doing none of those things, rather than on the permission being
  structurally absent.
- **`ACCESS_NETWORK_STATE`** is declared for one thing: reading which Wi-Fi
  frequency band (2.4 or 5 GHz) the phone is currently on, for the "Measures"
  panel and the guided comparison. The app reads the band and nothing else —
  no SSID, no BSSID, no location; Android blanks those fields for an app
  without location access, and this app requests none.
- A link-quality-loss entry in the monitor's local log records the tuning
  measures' state (Wi-Fi band, a second connected Bluetooth device, …) at the
  moment the loss episode was first read, each marked "read" or "could not be
  read: reason". No entry claims a cause.
- Hearing data, profiles and link history stay in app-private storage. Export
  is manual, through the system file picker, to a location you choose.
- **Two different rules for Bluetooth addresses.** Device profiles store a
  hashed key of the address, not the address. The link history (Room tables
  `monitor_events`, `link_samples`, `encoder_starvation_events`) stores the
  real address and name of each device (`device_address`, `device_name`); it
  is purged of entries older than 24 hours each time the monitor starts. An
  earlier version of this README said addresses are never persisted raw; that
  was wrong for the history.
- The microphone is used only for the ambient-noise check, and audio is never
  written to storage.
- **Android backup is on** (`allowBackup`). The files in the app's `datastore/`
  folder — hearing data, equalizer settings, device profiles, setup state,
  appearance — can be copied by Android's backup to your Google account and to
  a new phone during device transfer. The history database, the pairing key
  and the record of previous Android settings are not copied.
- The full privacy policy text ships with the app as
  `app/src/main/assets/privacy.txt`. Its pages in the app (Settings) are built
  in the Play-release work (T-074, H-3) and are **not in the current build**.

### Known issue after uninstalling

Files the helper writes to `/data/local/tmp` (a log and temporary result
files) can hold device names and Bluetooth addresses and are readable by other
apps (SR-001, SR-009). Uninstalling the app removes neither these files nor a
running helper (SR-016). Restart the phone to stop the helper; delete the files
with `adb`. This is open and blocks the Play submission (`docs/state.md`,
T-006).

## Architecture

Five Gradle modules, wired by hand — the object graph is small enough that a DI
framework would cost more than it saves.

| Module | Contents |
| --- | --- |
| `:app` | Compose UI: dashboard, EQ, hearing test, monitor, setup wizard, device profiles; Tidal notification listener; backup import/export |
| `:core-audio` | `DynamicsProcessing` EQ wrapper and the Oboe/NDK tone generator (C++) |
| `:core-hearing` | Hughson-Westlake protocol, audiogram model, NAL-R sound-adjustment math, calibration presets, profile storage |
| `:core-system` | Privileged-helper client, EQ attachment strategies, per-device profiles, absolute volume, setup state, DataStore, boot receiver |
| `:core-monitor` | Codec status, link-quality sampling, BQR/dumpsys sources, foreign-EQ detection, Room history |

### How the EQ actually works

Other apps' audio never passes through this app's process, so no amount of
custom DSP could equalize Tidal. The system EQ is therefore Android's
`DynamicsProcessing` audio effect (API 28+), configured as 10 pre-EQ bands per
channel plus its built-in limiter, attached inside the system mixer. Two
strategies sit behind one interface:

1. **Global (app helper).** Attach to session 0, the output mix — the only
   path that reaches every app.
2. **Session mode (no privileges).** Attach to sessions apps announce by
   broadcast. Works only for well-behaved players.

Oboe/NDK is kept where low-level control is genuinely required: sample-accurate
sine generation with strict channel isolation for the hearing test.

### How the link monitor gets its data

A source hierarchy, degrading transparently and always showing which level is
live:

1. `BluetoothQualityReport` (Android 13+, needs `BLUETOOTH_PRIVILEGED` via the
   app helper's shell identity) — packet loss, retransmissions, RSSI, glitch
   reports. Whether the shell user may register these callbacks has to be
   verified per Android version.
2. The public codec-status API plus system broadcasts.
3. `dumpsys bluetooth_manager` parsing, last resort, with per-version golden
   samples in the tests because its format shifts between builds.

## Building

Standard Android Studio / Gradle project. Requires JDK 17+, an Android SDK with
API 35, and NDK + CMake 3.22+ for the tone generator. Oboe arrives as a Gradle
prefab dependency.

```
./gradlew test           # unit tests + Robolectric screen smoke tests
./gradlew assembleDebug
./gradlew assembleRelease
```

Unit tests run on the debug variant only; the Compose test rule needs the host
activity from `ui-test-manifest`, which must not be merged into release.

## Non-goals

No cloud. No medical claims: the app adjusts sound to a personal hearing
profile and does not diagnose or treat anything. No touching the settings of
the Focal & Naim or Noble FoKus apps — the documented rule is "set their EQ to
Flat" instead. Pushing a flat curve into a headphone's onboard DSP would need
vendor BLE reverse engineering and is explicitly out of scope for now.

## License

[MIT](LICENSE) © DankYeeter
