# DankYeeter's Bluetooth Dashboard

An Android app for Bluetooth audio nerds: a per-ear system-wide equalizer, an
audiometry-inspired hearing test that drives it, and a Bluetooth codec/link
dashboard. Kotlin, Jetpack Compose, NDK/Oboe, Shizuku.

> **Disclaimer:** audiometry-inspired consumer calibration without clinical
> validity — not a substitute for professional hearing diagnostics.
> The measured values are meaningful only relative to each other, on the
> headphone they were measured with, in the listening mode they were measured
> in. If you have any concern about your hearing, see an audiologist.

## Status

Stage A (foundation) — project scaffold, module structure, EQ engine, Shizuku
onboarding, persistence. The hearing-test protocol and the compensation math
are being implemented on top of the interfaces in `:core-hearing`.

## Principles

- **No network.** The app does not declare `INTERNET`. No analytics, no crash
  reporting, no cloud sync. Everything stays on the device.
- **No Play Store.** Distribution is GitHub only.
- **Never touch other apps' settings.** The Focal & Naim / Noble FoKus apps are
  left alone; the README asks you to set their EQ to Flat instead.
- **Honest about limits.** Where a capability is not reachable without root or
  vendor protocols, the UI says so rather than faking it.

## Modules

| Module | Contents |
| --- | --- |
| `:app` | Compose UI shell, navigation, EQ screen, Shizuku onboarding |
| `:core-audio` | `DynamicsProcessing` EQ wrapper (Kotlin) + Oboe/NDK tone generator (C++) |
| `:core-hearing` | Audiogram/compensation data models and interfaces |
| `:core-system` | Shizuku integration, EQ attachment strategies, DataStore, boot receiver |

## How the EQ actually works

Other apps' audio never passes through this app's process, so a custom DSP
cannot equalize Tidal. The system EQ is therefore Android's
`DynamicsProcessing` audio effect (API 28+), configured as **10 pre-EQ bands
per channel** — which is what makes per-ear compensation possible — plus its
built-in limiter, attached inside the system mixer.

Two attachment strategies, behind one interface:

1. **Global (Shizuku).** Attach to audio session 0, the output mix. This is the
   only path that reaches every app. Requires the elevated identity Shizuku
   provides after a one-time ADB wireless-debugging pairing.
2. **Session mode (fallback, no privileges).** Attach to sessions that apps
   announce via `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`. Works only for
   players that send this broadcast — Tidal does not do so reliably.

Oboe/NDK is used where low-level control is genuinely required: sample-accurate
sine generation with strict left/right isolation and digital dB stepping for the
hearing test.

### Known limitation: reboots

Shizuku does not survive a reboot on an unrooted device, because Android turns
wireless debugging off at boot. The boot receiver restores the saved EQ where it
can and otherwise posts an "EQ inactive — restart Shizuku" notification instead
of pretending everything is fine.

## Setup on the device

1. Install Shizuku from its GitHub releases page.
2. Enable Settings → System → Developer options → Wireless debugging.
3. Pair Shizuku with the pairing code, then start the service.
4. Authorize this app when it asks.
5. Optional, over ADB from a computer:
   `adb shell pm grant dev.dankyeeter.btdashboard android.permission.WRITE_SECURE_SETTINGS`
6. Set your headphone app's own EQ to Flat.

## Building

Standard Android Studio project. `./gradlew assembleDebug` with an Android SDK,
NDK and CMake 3.22+ installed. Oboe is pulled in as a Gradle prefab dependency.

## License

Not yet decided; all rights reserved for now.
