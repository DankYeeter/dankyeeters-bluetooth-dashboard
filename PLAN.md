# DankYeeter's Bluetooth Dashboard — Implementation Plan

Android app (Kotlin + Jetpack Compose, Oboe/NDK, Shizuku). English-only code,
UI, and docs. Local git only for now — public GitHub push happens later after
Daniel reviews. No `INTERNET` permission, no analytics, fully self-contained.

## Decisions (confirmed 2026-08-18)

- Test device: Google Pixel (recent Android). Code defensively anyway for
  OEM quirks in `BluetoothA2dp.getCodecStatus()`.
- Device support: **generic** for any BT audio device, with bundled
  calibration presets for Focal Bathys and Noble FoKus Prestige Encore
  (frequency-response data from public measurement databases).
- First milestone: **vertical slice** — hearing test → compensation curve →
  system-wide EQ. Dashboard/profiles/Tidal come second.
- Shizuku: not preinstalled; app must detect its absence and walk the user
  through install + ADB pairing (also `WRITE_SECURE_SETTINGS` guidance).

## Reference approach (what we copy)

- **Mimi (Focal Bathys):** pure tones against masking noise, per-ear
  threshold profile, *partial* compensation with a user-facing intensity
  slider. We copy: intensity slider, partial compensation, per-ear curves.
- **Audiodo (Noble Encore):** ~3-minute per-ear threshold sweep, hearing
  profile → per-ear compensation EQ that preserves the device's sound
  signature. We copy: short guided test flow, per-ear asymmetry handling.
- Our protocol: **Modified Hughson-Westlake** (5-up/10-down, threshold =
  ≥2/3 hits) at 250–8000 Hz per ear, ambient-noise mic check first.
  Honest framing: "audiometry-inspired consumer calibration, not clinical."

## Milestone 1 — vertical slice (current)

### Stage A: Foundation (Worker A, first)
- Gradle/AGP scaffold: Kotlin, Compose, NDK/CMake, Oboe dependency; minSdk 31,
  target latest. Package `dev.dankyeeter.btdashboard`.
- Module layout: `app` (UI), `core-audio` (NDK biquad EQ + tone generator),
  `core-hearing` (test protocol + compensation math), `core-system`
  (Shizuku, settings persistence, BT plumbing).
- Shizuku integration skeleton: detect installed/paired/authorized states,
  onboarding screens that guide install + ADB pairing; detect
  `WRITE_SECURE_SETTINGS`.
- **EQ architecture (corrected 2026-08-18):** other apps' audio can never
  flow through our process — a C++/Oboe DSP cannot equalize Tidal. The EQ
  is implemented via Android's **`DynamicsProcessing`** AudioEffect
  (API 28+): 10 pre-EQ bands **per channel** (per-ear!) + built-in limiter,
  attached in the system mixer. Shizuku grants the elevated attach
  (global/`USAGE_MEDIA`; Tidal doesn't reliably broadcast its session).
  Graceful degradation without Shizuku: session mode (apps that broadcast
  `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` only). Oboe/NDK stays but only
  where it's correct: sample-accurate sine generation for the hearing test.
  Foreign-EQ detection is promoted to v1 (two DynamicsProcessing instances
  on one session collide). Persistence via DataStore + boot receiver;
  Shizuku is a first-class part of the app from v1: the onboarding stepper
  includes its install + pairing as a core step (not an optional extra),
  and we enable/document Shizuku's start-on-boot (wireless debugging)
  option so it auto-runs where the OS allows; if auto-start fails after a
  reboot, a post-boot notification says "EQ inactive — restart Shizuku".
  Graceful degradation (session mode) always ships with an in-app
  disclaimer listing exactly which features are missing without Shizuku.
- Local git init + sensible .gitignore; README stub with honesty disclaimer.

### Stage B (parallel after A): Hearing test (Worker B)
- Precise per-channel sine generation (left/right isolation) in `core-audio`.
- Modified Hughson-Westlake state machine in `core-hearing` with unit tests.
- Ambient-noise pre-check via mic (warning, not blocker).
- Level control: lock media volume to a reference value for the whole run —
  volume key events are consumed while the test is foreground and a message
  ("volume is locked during the test") is shown; external changes (headphone
  buttons) are detected and invalidate the run. All 5-dB steps happen
  digitally in the tone generator, never via system volume.
- ANC mode: test simply runs in whatever mode the user normally listens in —
  no mode selection UI, just a one-line hint in the intro ("use your usual
  mode and keep it").
- Fit check is mandatory UX for **all IEMs** (Encore, AirPods, future
  models — general rule), optional for over-ears.
- Audiogram result model + Compose test UI + audiogram chart.
- Multi-run workflow: user performs 3+ test runs; overlay all audiograms in
  one chart, use per-frequency **median** as the active curve; individual
  outlier runs can be deleted and retaken.
- Plain-text intro screen before each test: quiet environment, check earbud
  fit, expected duration. No enforcement, just instructions.
- Quick fit check before each test: short 125–250 Hz probe compared against
  the user's stored low-frequency baseline; large deviation ⇒ warn about
  poor seal/placement before the run. (True fit tests use the earbud's
  internal feedback mic, which third-party apps cannot access — that would
  be BLE reverse engineering, Milestone 3.)
- Calibration presets: Bathys + Encore frequency-response offsets from
  public measurement data; "uncalibrated generic" fallback profile.
  Additional presets (added 2026-08-18): **AirPods IEM lineup** — AirPods
  Pro 3, Pro 2, AirPods 4, 4 ANC, AirPods 3, AirPods 2 (highest-volume TWS
  models; on Android they are plain AAC A2DP devices, no vendor features) —
  and **Sennheiser Momentum 4 Wireless** (a colleague of Daniel's owns
  them and can test directly). AirPods Max excluded. Each
  preset stores its data source, measurement rig, and target curve —
  over-ear rigs vs. IEM couplers are not comparable; presets are shape
  corrections, never absolute levels.
- EQ bands outside the tested 250–8000 Hz range (31.5, 63, 125 Hz; 16 kHz)
  are visually marked as "extrapolated" in the UI.

### Stage C (parallel after A): Compensation + EQ UI (Worker C)
- Compensation math is specified by Fable directly (extra-effort research —
  see COMPENSATION.md, must be reference-faithful); Worker C implements
  strictly against that spec. Baseline: (threshold − ISO 226 norm) ×
  partial factor, per ear; intensity slider like Mimi; mapped onto 10 bands.
- EQ screen: band sliders, per-ear view, profile save/load, A/B toggle
  (flat vs. compensated) for validation listening.
- Headroom management (negative pre-gain to avoid clipping).
- Optional peak limiter as the last stage of the EQ chain (tames loudly
  mastered tracks system-wide; Tidal's own loudness normalization remains
  the recommended first fix — we never touch Tidal itself).

### Stage D: Integration + review (Fable)
- Wire B+C outputs together, build, code review, fix, commit.

## Design (added 2026-08-18)
Material 3 / Material You base (Pixel-native feel), bottom navigation
(Dashboard · EQ · Hearing Test · Monitor), edge-to-edge, large collapsing
top bars, guided stepper flow for Shizuku onboarding, custom Compose-Canvas
charts (no third-party chart libs). Three themes: **Light** and **Dark**
(Material 3 with dynamic color) and **Edgy** — true black (#000, OLED) with
gold accents: gold outlines, slider thumbs, active states and chart lines,
subtle gold gradients and 1-dp hairline dividers, display-serif accents for
headings/numbers. Opulence through detail, never gold surfaces. Full spec
in DESIGN.md.

## Milestone 2 (later)
- BT codec dashboard (`getCodecStatus`, defensive), per-device profiles via
  `ACTION_ACL_CONNECTED` + MAC, absolute-volume toggle, Tidal now-playing via
  `NotificationListenerService`.
- **Link quality monitor (added 2026-08-18):** background logging of BT link
  health per device with history + charts, motivated by suspected bandwidth
  problems between Pixel 8 Pro and Noble FoKus Prestige Encore. Android has
  no public packet-loss counter. Source hierarchy (stable APIs first):
  1. **`BluetoothQualityReport` (Android 13+, BLUETOOTH_PRIVILEGED via
     Shizuku) — primary source**: structured controller events with packet
     loss, retransmissions, RSSI, audio-glitch ("choppy") reports. No
     consumer app uses this — this is the market gap.
  2. Codec-status API + system broadcasts (codec change, play state, ACL).
  3. `dumpsys bluetooth_manager` parsing only as last-resort fallback.
  Sampling strategy: **hybrid adaptive (Option C) + on-demand deep capture
  (Option D)** — events always on, slow polling (30–60 s) while A2DP plays,
  burst to 2–5 s on anomaly (bitrate drop/RSSI dip); full resolution only
  during "test device" runs **plus a "watch live" quick-action on the
  dashboard** that jumps straight into deep capture (10 s resolution) so
  acute problems can be inspected while they happen. If the shell identity
  turns out not to reach BQR on Android 17, a dedicated deep-dive into
  Android 17's Bluetooth stack is planned to develop an alternative. Primary target:
  Android 17 (Pixel 11 Pro), Pixel 8 Pro second.
  Rights reality check: BQR needs BLUETOOTH_PRIVILEGED; we call it under
  Shizuku's shell identity — whether shell may register BQR callbacks must
  be verified on-device per Android version. The source hierarchy exists
  exactly for this: monitor degrades transparently and shows which data
  source is currently active. Without root, shell identity is the ceiling.
  - codec + bitrate change history (aptX Adaptive downscaling events are the
    best real-world proxy for bandwidth trouble)
  Battery discipline: event-driven sampling only while A2DP is playing,
  adaptive intervals, stop on screen-off without playback. dumpsys output
  format is version-fragile (Pixel 8 Pro vs. Pixel 11 Pro!): defensive
  parsers with per-version golden-sample tests; degrade, never crash.
  Store locally (Room), render as timeline; annotate drops with timestamp +
  codec state so Daniel can correlate audible dropouts with data.
- **Active-device takeover log (added 2026-08-18):** event timeline of which
  BT device took A2DP focus and when (e.g. office SoundCore Motion Boom vs.
  Encore) — part of the link-quality monitor's event stream. Also log
  playback interruptions while still connected: track per-device A2DP
  playing-state broadcasts + active-device changes so the timeline can say
  "playback paused — device XY took the audio stream".
- **Foreign EQ detection (v1, promoted):** via Shizuku, parse
  `dumpsys media.audio_flinger` to list active Android audio effects per
  session and warn if another EQ (Wavelet, OEM) is stacking with ours —
  including **app attribution**: map the effect chain's client PID to the
  package/app name so the warning reads "Equalizer active from Wavelet"
  with a deep link to that app's info screen.
  The headphone's onboard DSP EQ is not readable (vendor BLE protocol —
  Milestone 3); v1 keeps the "set vendor app to Flat" documentation rule.
- **AirPods BLE beacon features (added 2026-08-18):** AirPods broadcast BLE
  proximity beacons readable without pairing or Apple hardware (format
  documented by open-source projects like CAPod/MaterialPods — prior art,
  reimplement cleanly, no GPL code copying): per-bud + case battery, in-ear
  wear status, model identification (drives automatic preset selection),
  lid events. Not possible: ANC switching, in-bud EQ, firmware features.
- **Device icons (decided 2026-08-18):** our own minimalist vector line
  illustrations per model only (copyright-safe, scales, gold-line-on-black
  fits the Edgy theme). No user-photo feature, no manufacturer press images
  in the repo — editorial-use licenses don't cover software redistribution.
- **Profile export/import (added 2026-08-18):** local JSON export of hearing
  test results + EQ/device profiles, importable on another phone (Daniel is
  getting a Pixel 11 Pro alongside the Pixel 8 Pro; no cloud allowed).
- **Automated device diagnostic ("test device"):** guided one-tap routine —
  connect check, codec negotiation result, forced codec cycling, N-minute
  link stability soak with the monitor recording, then a summary report
  (best stable codec/bitrate, drop count, RSSI range).

## Milestone 3 (stretch, explicitly not MVP)
- BLE reverse engineering to push a flat curve to Bathys/Encore DSP.

## Non-goals
No Play Store, no cloud, no medical diagnostic claims, never touch the
Focal & Naim / Noble FoKus apps' stored settings (v1 documents "set their
EQ to Flat" instead).
