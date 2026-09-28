# DankYeeter's Bluetooth Dashboard

A system-wide, per-ear equalizer for Android that is driven by a hearing test
you take on your own headphones — plus the Bluetooth link diagnostics that
audio nerds actually want and no consumer app shows.

Two halves, one app:

- **Hearing-test-driven system EQ.** A guided per-ear threshold test produces an
  audiogram; the audiogram produces a partial compensation curve; the curve is
  applied to *all* audio leaving the phone, not just this app's.
- **Bluetooth link-quality monitor.** Codec, negotiated bitrate, RSSI,
  retransmissions and takeover events over time, so a dropout you heard can be
  matched against what the link was doing at that second.

Kotlin · Jetpack Compose · Material 3 · Oboe/NDK · own privileged helper (no
Shizuku, no root). No cloud, no analytics — see [Privacy](#privacy) for what
the one network-related permission is actually for.

> ### Disclaimer
>
> This is **audiometry-inspired consumer calibration without clinical
> validity**. It is not a hearing test in the medical sense, not a diagnostic
> tool, and not a medical device. Its numbers are meaningful only relative to
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

- 10 bands **per channel** via Android's `DynamicsProcessing` effect — per-ear
  compensation, not a stereo compromise.
- NAL-R-derived partial compensation with an intensity slider (Mimi-style), so
  the correction can be dialled back to taste.
- Automatic negative pre-gain for headroom, plus an optional peak limiter.
- A/B toggle between flat and compensated for validation listening.
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
- Per-device profiles: when a known headphone connects, its compensation
  profile, media volume and absolute-volume preference are applied
  automatically. Addresses are stored hashed, never as raw MACs. Every
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

- Now-playing card reading Tidal's media notification (read-only; Tidal is
  never modified).
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

There is no Play Store release and there will not be one.

1. Download the latest `app-release.apk` from the
   [Releases](../../releases) page.
2. Open it on the phone and allow installation from this source when Android
   asks.
3. Launch the app. The **setup wizard** starts automatically and walks you
   through four steps: Bluetooth permission, microphone (for the ambient
   check), notifications, and the app's own helper (see
   [App helper](#app-helper) below). Every step re-checks itself live. Only
   the microphone step can be skipped — Bluetooth and notifications cannot,
   because the app cannot work without the first and cannot even finish this
   wizard without the second.
4. Set the EQ inside your headphone's own app (Focal & Naim, Noble FoKus, …) to
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

This is needed once per phone. Android turns wireless debugging off at every
reboot, so **the helper stops at every reboot**; after that, restarting it is
a single tap on "Activate" — from a notification the app posts after a
reboot, or from the System access screen — with no code to type again, because
the pairing itself is remembered.

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

### `WRITE_SECURE_SETTINGS` (optional)

Only the absolute-volume toggle needs it. The helper grants it automatically
the moment it connects, and — unlike the helper — the grant survives reboots.
If the helper cannot start on your phone, you can grant it once from a
computer instead:

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
- Bluetooth addresses are hashed before being persisted, so a permanent
  hardware identifier does not end up in a file you might share.
- The microphone is used only for the ambient-noise check, and audio is never
  written to storage.

## Architecture

Five Gradle modules, wired by hand — the object graph is small enough that a DI
framework would cost more than it saves.

| Module | Contents |
| --- | --- |
| `:app` | Compose UI: dashboard, EQ, hearing test, monitor, setup wizard, device profiles; Tidal notification listener; backup import/export |
| `:core-audio` | `DynamicsProcessing` EQ wrapper and the Oboe/NDK tone generator (C++) |
| `:core-hearing` | Hughson-Westlake protocol, audiogram model, NAL-R compensation math, calibration presets, profile storage |
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

No Play Store. No cloud. No medical claims. No touching the settings of the
Focal & Naim or Noble FoKus apps — the documented rule is "set their EQ to
Flat" instead. Pushing a flat curve into a headphone's onboard DSP would need
vendor BLE reverse engineering and is explicitly out of scope for now.

## License

[MIT](LICENSE) © DankYeeter
