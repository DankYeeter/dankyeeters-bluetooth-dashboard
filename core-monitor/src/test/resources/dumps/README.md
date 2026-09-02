# Golden sample dumps

## Verbatim captures (trust these)

Trimmed but **not rewritten** — every line below is exactly as the device
printed it, with sections that carry nothing for the parser removed and MACs
redacted to the repo's `xx:xx:xx:xx:ab:cd` form.

| file | device / build | captured |
|------|----------------|----------|
| `bt_manager_pixel11_ldac_txqueue.txt` | Pixel 11 Pro (grizzly), Android 17 | 2026-08-26, minutes after an LDAC 96 kHz/32 bit session with a Noble FoKus Prestige Encore ended |
| `audio_flinger_pixel11_threads.txt` | Pixel 11 Pro (grizzly), Android 17 | 2026-08-26, all output threads in standby |
| `audio_players_tidal.txt` | copy of `:core-system`'s `dumpsys_audio_players.txt` | captured with Tidal playing and Spotify paused |
| `bt_manager_pixel11_ldac_990_loss.txt` | Pixel 11 Pro (grizzly), Android 17 | 2026-09-02, T-022. LDAC Playback Quality pinned to 990 kbps (`Priority: 1000000`, `LDAC quality mode: HIGH`), Noble FoKus Prestige Encore, TIDAL playing. Taken ~4:08 into the pinned session: `Counts (flushed/dropped/dropouts)` = 0/1851/74, `Counts (underflow)` = 623 — same 623 as the read-back ~4 min earlier, so dropouts rose while underflow stood still (the AK-T009-24 case). Rates (dropped ≈290/min, dropouts ≈12/min over the middle interval) match the T-008 range. **No `LDAC adaptive bit rate` lines** — see caveat below. |
| `bt_manager_pixel11_ldac_paused_990.txt` | Pixel 11 Pro (grizzly), Android 17 | 2026-09-02, T-022. Same pinned 990 session, ~75 s after `mIsPlaying` went to `false` (media paused via `KEYCODE_MEDIA_PAUSE`, A2DP connection held). `LDAC quality mode: HIGH`, transmission bitrate still `990`, still no ABR lines — the stack keeps reporting the last active codec config while paused, it does not blank `quality mode` or zero the rate. Answers QA-007 for this one condition: paused-but-connected does not reproduce the empty-`LdacStackState` case. |
| `bt_manager_pixel11_ldac_abr_rung1_660.txt` | Pixel 11 Pro (grizzly), Android 17 | Cut from the T-011 (`docs/perf/T-011-messung.md`) raw series, sample 1 of 1795, 2026-09-02 08:14 device time. **Reduced capture**: only the `A2DP State:` and `A2DP LDAC State:` blocks, matching T-011's own reduced-write methodology — not a full `dumpsys bluetooth_manager` output. ABR index 1 ↔ 660 kbps, `dropped`/`dropouts` = 0 (this is a rest-state sample, not the loss case). |
| `bt_manager_pixel11_ldac_abr_rung3_492.txt` | Pixel 11 Pro (grizzly), Android 17 | Cut from the same T-011 series, sample 236 of 1795. Same reduced two-block capture. ABR index 3 ↔ 492 kbps, `dropped`/`dropouts` = 0. Confirms the non-monotonic index↔bitrate ladder (660↔1, 492↔3, 396↔4) against real device data, not just the measurement doc. |
| `bt_manager_pixel11_ldac_pinned_660.txt` | Pixel 11 Pro (grizzly), Android 17 | 2026-09-02, T-027 Phase-0 gate, 17:06 device time. LDAC Playback Quality **fixed** to "Balanced Audio And Connection Quality" via Developer options (`Priority: 1000000`, `mCodecSpecific1: 1001`, not shown in this reduced two-block capture — see `docs/perf/T-027-messung.md` §1.2 for the full-dump excerpt). `LDAC quality mode: MID`, 660 kbps, **no `LDAC adaptive bit rate` lines** — the gate's own evidence that these lines are absent whenever the mode is a fixed tier, not just at 990/HIGH. `dropped`/`dropouts` = 0/0 (rest state, not the loss case). |
| `bt_manager_pixel11_ldac_pinned_330.txt` | Pixel 11 Pro (grizzly), Android 17 | 2026-09-02, T-027 Phase-0 gate, 17:13 device time, ~7 min after the 660 capture above, same session, LDAC Playback Quality switched to "Optimised for Connection Quality" (`mCodecSpecific1: 1002`, `Priority` still `1000000` — unchanged, see T-027's gate finding). `LDAC quality mode: LOW`, 330 kbps, **no `LDAC adaptive bit rate` lines**. `dropped`/`dropouts` = 0/0. |
| `bt_manager_pixel11_ldac_best_effort_990_freshly_selected.txt` | Pixel 11 Pro (grizzly), Android 17 | 2026-09-02, T-027 Phase-0 gate, 17:13–17:14 device time, same session, LDAC Playback Quality switched to "Best Effort (Adaptive Bit Rate)" (`mCodecSpecific1: 1003`). Captured seconds after the switch — the regulator has already jumped straight to 990 kbps (matches the T-011 finding "ABR tries 990 on its own"). `LDAC quality mode: ABR`, **ABR lines present** (`index: 0`, `adjustments: 2`) — contrast this file directly against the two pinned ones above for the presence/absence pattern. Not a steady-state ABR sample (see the `abr_rung*` files above for those); this one documents the transition instant. |
| `bt_manager_pixel11_ldac_pinned_660_24ghz_induced_loss.txt` | Pixel 11 Pro (grizzly), Android 17 | 2026-09-02, T-027 Phase 4 (2,4-GHz-Kalibrierlauf, `docs/perf/T-027-messung-24ghz.md`), 19:28:23 device time. LDAC Playback Quality **fixed** at 660 kbps (`LDAC quality mode: MID`, **no `LDAC adaptive bit rate` lines** — same fixed-tier signature as the two pinned files above), device associated to the same AP on **2,4 GHz** (`Frequency: 2462MHz`), captured immediately after a 240 s cell with 1 parallel `dd\|nc` push stream against a host TCP sink on the same LAN. **First verbatim capture with `Counts (flushed/dropped/dropouts)` genuinely non-zero from an externally-induced loss episode at a non-990 fixed tier** — `1 / 807 / 35`, `Last update time ago in ms (flushed/dropped): 0 / 0` (the drop had just happened when this dump was taken). Within this one cell alone `dropped` rose by 161 and `dropouts` by 7 against the immediately preceding control read (240 s, no stimulus: `dropped`/`dropouts` Δ 24/1) — see the measurement doc for the full delta table. Distinct from `bt_manager_pixel11_ldac_990_loss.txt`: that fixture is spontaneous overload at 990/HIGH with no external stimulus; this one is externally induced by measured real WLAN load while pinned at 660/MID, the condition this project's R-007 mechanism actually targets. Closes the third fixture gap noted in `docs/state.md` ("kein aufgenommener Dump aus dem Verlustfall" outside the 990 arm). |

Two things about `bt_manager_pixel11_ldac_txqueue.txt` are load-bearing and were
kept deliberately rather than tidied away:

- `mCodecSpecific1:0` on a real LDAC link. That is what an untouched phone
  looks like — nobody has pinned an LDAC quality in Developer options — and it
  is the reason the app cannot show a live LDAC bitrate.
- `codecConfigOffloading` listing only SBC, AAC and Opus. LDAC's absence from
  that list is what makes the `A2DP State:` tx-queue counters in the same file
  real numbers rather than a frozen leftover.

### Caveat on `bt_manager_pixel11_ldac_990_loss.txt` and `..._paused_990.txt`

T-022 was asked to find a single dump with **both** `dropped`/`dropouts` > 0
**and** the full `A2DP LDAC State:` block including the two `LDAC adaptive
bit rate` lines. Read-back on the real device (three dumps over ~4 min,
990 kbps pinned) shows this is not just missing so far — it appears
structurally unavailable: the two ABR lines only print while `LDAC quality
mode` is `ABR`, and the loss case only reproduces while pinned to `HIGH`.
No sample across three device reads in the pinned session carried the ABR
lines; no sample across all 1795 T-011 samples (`LDAC quality mode: ABR`
throughout) carried `dropped`/`dropouts` > 0. Reported to the director as a
requirement conflict, not resolved here — no line was added to either file
to force the two facts into one dump.

## Reconstructions (older, shape-only)

The remaining files are **shape-faithful reconstructions** of `dumpsys bluetooth_manager`
and `dumpsys media.audio_flinger` output, not verbatim captures — they were
written from the AOSP dump code paths so the parsers can be pinned down before a
device is available. Line wording, indentation, key spellings, redacted MACs
(`xx:xx:xx:xx:ab:cd`) and the `codecConfig:{...}` layout follow the real format.

When a real capture is taken on the Pixel 8 Pro and the Pixel 11 Pro, drop it in
here **verbatim** under a version-tagged name (`bt_manager_android17_pixel11.txt`)
and add a golden test for it. That is the whole point of the golden-sample setup:
the dump format is version-fragile, so every version we care about gets its own
pinned sample. Existing samples are never edited to make a parser pass.
