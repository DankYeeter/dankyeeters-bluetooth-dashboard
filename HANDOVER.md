# Handover — 2026-08-19

State of the project at the end of the session, and the things that are not
obvious from the code or the git log.

## Where it stands

`./gradlew test` → **299 tests, 0 failures** across all modules.
`./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**, verified from a *fresh
clone* of `HEAD`, not just from the working tree.

Milestones 1 and 2 are built. The APK has still never been installed on a
device — by choice, to get as much done as possible before touching hardware.

## What landed last

**The "which apps could have an EQ" check** (`core-monitor/.../effects/EqCandidates.kt`,
`EqCandidateScanner`, surfaced in the dashboard's foreign-EQ area).

Four tiers, and the distinction between them is the whole point — they are
different *kinds* of evidence, not degrees of one:

| Tier | Signal | What it proves |
|---|---|---|
| `DECLARED_PANEL` | intent filter for `DISPLAY_AUDIO_EFFECT_CONTROL_PANEL` | Not a guess: the app is telling Android it owns an equaliser UI. |
| `VENDOR_COMPANION` | curated package list | Only that the app is installed. Its EQ runs in the headphone's DSP, invisible to any Android-side scan. |
| `AUDIO_EFFECT_PERMISSION` | requests `MODIFY_AUDIO_SETTINGS` | Necessary, nowhere near sufficient. Behind "show more". |
| playing now | `getActivePlaybackConfigurations()` | Context only — it never adds an app, it re-sorts. |

**Deliberately not detected:** apps that filter audio in their own code. That is
exactly the Focal case, and no scan can see it. The UI says so in as many words:
*"This is a hint, not a verdict."* Do not let a future change quietly turn this
into a claim.

**Battery** was a hard requirement and shaped the design: the package pass runs
only when the section becomes visible or the user taps refresh — never on a
timer, never on `refresh()`. One iteration over the package list, `loadLabel`
only for survivors of the filter. Cached in memory for the process lifetime,
invalidated by `PACKAGE_ADDED/REMOVED/REPLACED` with the receiver registered
lazily alongside the scanner. Playback state is event-driven via
`registerAudioPlaybackCallback`, unregistered on `ON_STOP`.

The scan is **instrumented rather than measured** — nobody has run it on a
phone. `EqCandidateScan` carries `scannedPackages` and `durationMs`, and the UI
prints "N installed apps checked in M ms". Expected 150–400 ms on a normally
loaded device; if the real number is much worse, move the `ON_START` trigger
behind the existing button.

## Gotchas worth knowing

- **`getClientUid()` on `AudioPlaybackConfiguration` is a system API.** It is
  read reflectively. If that ever stops working the overlay must say "cannot
  tell which app is playing" — never "nothing is playing".
- **A previous commit did not compile on its own.** Several workers had left
  output uncommitted, and the EQ commit referenced files that were still
  untracked. `c303404` landed all of it. If you run parallel workers again,
  check `git status` before trusting that `HEAD` builds.
- **Toolchain:** Temurin 21, SDK at `Desktop\ClaudeCode\android-sdk`. A Windows
  issue corrupts files extracted into `AppData\Local` outside `Temp` — keep
  Gradle distributions out of there.
- **The emulator does not work on this machine** (no hypervisor; WHPX or AEHD
  needs admin). Everything is verified by unit tests and Robolectric only.

## Open, in rough priority order

1. **Install the APK on the Pixel.** Everything below needs a device.
2. On-device unknowns already listed in `PLAN.md`: global `DynamicsProcessing`
   attach via Shizuku, BQR reachability under shell identity on Android 17,
   real `dumpsys` goldens, codec cycling.
3. Measure the EQ-candidate scan for real and decide whether it stays on
   `ON_START`.
4. No INTERNET permission exists anywhere and none may be added.

## Related

`PLAN.md` is the roadmap, `COMPENSATION.md` is the spec for the hearing-loss
maths (NAL-R with zero-loss normalisation). The sibling project
`../platinum-souls` is unrelated but shares the machine and the workflow.
