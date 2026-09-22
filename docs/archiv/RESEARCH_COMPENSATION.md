# Research: Hearing Compensation for Music Timbre Restoration

Written by Fable after web research, 2026-08-20. Companion to COMPENSATION.md
(which stays authoritative for the implemented v1 pipeline). Scope: Daniel's
stated goal — *"die Klangfarbe der Musik wiederherstellen zu dem was ich hören
sollte wenn ich noch den vollen Umfang hätte … einen Adjusted Reference. Für
mich."* — i.e. **restore perceived tonal balance of music toward what an
unimpaired ear would perceive**, explicitly not speech intelligibility.

House rule applies throughout: nothing below claims more than its source
supports, and where a number could not be verified it says "cannot check"
instead of a plausible guess.

---

## 1. Recommendations, in priority order

**R1 — Make the slider honest before making it different.** Keep `G = s · IG`
unchanged, but change what the UI *says*: NAL-R at 100 % already prescribes only
≈ 0.46 dB per dB of loss on average (the "half-gain" empirically preferred by
real listeners), so the current default `s = 0.6` delivers ≈ 28 % of the
audiometric loss, and 100 % on the slider means ≈ 46 %, not full restoration.
Show the effective dB at the most-boosted band next to the slider and label the
scale "of prescription", never "of your loss" — this single wording change
removes the app's largest honesty gap at zero implementation risk.

**R2 — Default the Adjusted Reference profile to the 20-band layout.** In the
10-band octave layout the measured 3000 Hz and 6000 Hz thresholds contribute
*exactly nothing* to the output: the octave band centres coincide with the
other six test frequencies, the interpolant passes through knots, and 3k/6k are
not in the PTA either (provable from `NalRCompensationCalculator.mapToBands` +
`NalR.PTA_FREQUENCIES_HZ`). 3–6 kHz is precisely where noise-induced notches
live. The 20-band layout (centres 3200/6400 Hz) captures them; this is a
config-default change, not new math.

**R3 — Offer a second curve shape ("Music – flat restoration") behind the same
slider, decided by the existing matched-loudness A/B.** NAL-R's C(f) table is a
speech-spectrum weighting (−17 dB at 250 Hz, −2 dB at 6 kHz): it deliberately
under-compensates the low end and shaves the extreme highs because that
maximises *speech* loudness-equalization, not fidelity. Music-specific evidence
(CAM2 preferred over NAL-NL2 for music quality; hearing-aid users preferring
≈ +10 dB more low-frequency gain for music than speech prescriptions; survey
complaints about "lack of bass") all points the same way: for music, restore
each band closer to uniformly, without the speech weighting. Concretely: add an
alternative prescription `IG_music(f) = 0.5 · H_T(f)` (pure per-band half-gain,
no PTA term, no C(f)), same slider, same caps — and let the A/B toggle decide,
because **no published prescription is validated for music timbre restoration**
and pretending otherwise would violate the house rules. Effort: small; the risk
is nil because everything downstream (caps, smoothing, headroom) is shared.

**R4 — v2 remains WDRC, and it is nearer than COMPENSATION.md assumed.** The
static-EQ compromise is real: Mimi's own whitepaper argues a fixed gain cannot
compensate a level-dependent loss, and Mimi's engine is the published, open
BioAid architecture (Jürgens et al. 2016; Clark et al. 2017; source on GitHub
since 2012). Android's `DynamicsProcessing` — already the app's EQ backend —
ships a per-channel multi-band compressor stage the app currently leaves
unused, and minSdk 31 > API 28 so every supported device has it. Slow
compression (≈ 50 ms attack / 3000 ms release) is the evidence-backed safe
default for music. This is a real project (fitting CRs per band, verification),
not an afternoon.

**R5 — Slider mechanics: keep dB-linear, add coarse detents, keep the A/B.**
Just-noticeable differences for frequency-band gain changes are ≈ 3 dB
(octave-band) to 4–7 dB (single band); on a curve whose largest band gain is
≤ 12 dB, slider moves smaller than ≈ 25 % of full scale are inaudible at the
loudest band. A continuous slider is fine for feel, but the UI should offer
detents (e.g. 0/25/50/75/100 %) and always pair adjustment with the
matched-loudness A/B — that is also exactly the granularity Apple ships
(Slight/Moderate/Strong ≈ 8 dB apart plus a fine slider spanning ~12 dB).

**R6 — Add a dead-region guard as a *warning*, not a feature.** From an
audiogram alone the app can only flag *probability*: at any frequency with
(device-corrected) threshold > 70 dB HL, published prevalence data says the
odds of a cochlear dead region at that frequency are ≥ 59 %, and amplifying
well into a dead region degrades rather than restores. The TEN test that
actually diagnoses this needs calibrated hardware the app does not have — so
the honest behaviour is: cap that band's gain at the value of its lower
neighbour, show "cannot check for a dead region; compensation limited here",
and never claim restored timbre in that band. With the existing PTA-60 referral
notice and the 12 dB cap this will rarely trigger for Daniel, but the rule
should exist.

**R7 — Safety posture is already sound; document why.** Because pre-gain is
−max(G), the compensated path can never exceed the flat path's peak output at
the same volume position — compensation only *rebalances*, it does not add
level (the limiter stays as a second line). The remaining exposure risk is the
user raising the volume knob afterwards; the honest mitigation is the WHO
safe-listening budget in the README (80 dB(A) ≈ 40 h/week, −3 dB per doubling),
not a different cap.

---

## 2. Q1 — Which prescription rule fits "restore the timbre of music"?

### What each rule optimises

| Rule | Optimisation target | Level-dependent? | Inputs | Music validation |
|---|---|---|---|---|
| Lybarger half-gain / POGO | Preferred overall gain (empirical) | No | Thresholds | None |
| **NAL-R** (Byrne & Dillon 1986) | Equal loudness per band **for speech**, comfortable overall | No | Thresholds (250–6000 Hz) | None (speech-derived) |
| Cambridge linear formula (Moore & Glasberg 1998) | Flat specific-loudness of **65 dB speech** via loudness model | No | Thresholds | None directly |
| NAL-NL1/NL2 | Maximise **speech intelligibility** s.t. loudness ≤ normal | Yes (WDRC) | Thresholds + age/gender/experience (NL2) | Loses preference tests vs CAM2 for music |
| **CAM2 / CAMEQ2-HF** (Moore, Glasberg & Stone 2010) | Audibility + loudness model, targets to **10 kHz** | Yes (gains @65 dB speech + per-channel CRs) | **Audiogram alone**, ideally incl. 8–10 kHz | Preferred over NAL-NL2 for music quality (two trials) |
| CAMREST (Moore 2000) | **Loudness restoration** (normal specific-loudness patterns) | Yes | Thresholds | None; needed more post-fit correction than CAMEQ |
| DSL v5 / m[i/o] | Audibility; map normal dynamic range into residual range (paediatric origin) | Yes | Thresholds | For adults, required the largest gain *reductions* of all in trials; music listeners deviate from it by ≈ +10 dB LF |

Key philosophical split, stated by NAL themselves: NAL rules do loudness
**equalization** (make speech bands equally loud — deliberately reshapes the
spectrum for intelligibility), whereas DSL/CAMREST-style **normalization**
tries to restore the loudness pattern a normal ear would have. Daniel's goal is
by definition *normalization at his listening level*, not equalization. But the
trials are sobering for full normalization: in Moore's three-way fitting trials
(CAMEQ vs CAMREST vs DSL i/o), the equalization method needed the *smallest*
post-fitting corrections, restoration slightly more, and DSL — the fullest
audibility mapping — the largest, mostly *negative* at high frequencies. Full
restoration as-prescribed is reliably "too much" for real preferences.

### The CAM2 question

Verified findings:

- CAM2 prescribes slightly more mid-frequency gain and **markedly more gain
  above 4 kHz** than NAL-NL2, with recommended gains up to 10 kHz (its
  predecessor CAMEQ stopped at 6 kHz).
- Moore & Sęk 2013 (15 adults, mild sloping losses, music incl. classical,
  jazz, singing, percussion): **9/15 preferred CAM2 overall, 0 preferred
  NAL-NL2**, rest no clear preference; the preference held across stimuli,
  levels and compression speeds. A 2016 follow-up over a wide range of losses
  again found a slight preference for CAM2A at 65 and 80 dB inputs. A separate
  initial-fit comparison (Johnson 2013) found individual differences with no
  universal winner — the extra HF gain is exactly the contested part.
- Moore 2012 (review of music-bandwidth experiments, hearing-impaired listeners
  fitted with CAM2): preferences for a 5 kHz vs 7.5/10 kHz upper cutoff
  **varied by individual** — extended HF audibility is a benefit for some and
  a nuisance for others.

What can honestly be borrowed for a linear, threshold-only, N-band EQ:

1. **Borrowable: do not shave the measured top octave.** NAL-R's C(f) = −2 dB at
   6–8 kHz plus the 0.15·PTA cross-term systematically under-serves an
   HF-sloping loss relative to CAM2's philosophy. The half-gain-per-band curve
   of R3 achieves this without inventing coefficients.
2. **Borrowable: individual A/B for HF strength**, because the evidence says
   extended-HF benefit is individual (this is what `partialFactor` or a
   per-region trim could express later).
3. **Not borrowable: the actual CAM2 targets.** CAM2 is distributed as licensed
   software via Cambridge Enterprise; its target tables/coefficients are not
   published as an open formula. The linear Cambridge formula's exact
   per-frequency coefficients are in Moore & Glasberg (1998), which is
   paywalled — cannot check; do not implement a guessed version. (Its
   *qualitative* delta vs NAL-R is published: slightly more gain above 2 kHz,
   slightly less between 500 Hz and 2 kHz.)
4. **Not borrowable statically: the compression ratios.** CAM2's CRs are the
   level-dependent half of the method; a static EQ can only represent its
   65-dB-input slice. That slice is a legitimate approximation — for one
   listening level.

### The honest core answer

There is **no published prescription whose optimisation target is "restore
music timbre for this ear"**. Every open rule is speech-derived; the one
family with demonstrated music-quality advantages (CAM2) is closed-source and
built for WDRC. What the evidence *does* support for a static EQ:

- Near-half-gain overall strength (that is what preferred-gain studies keep
  converging on; NAL-NL2's own empirical revision subtracted a further ~3 dB
  from theory because 45 % of 189 users wanted less).
- No speech-spectrum weighting when the programme material is music
  (Vaisberg 2021: for music, listeners moved ≈ +10 dB LF and ≈ −4 dB HF away
  from a speech prescription; Madsen & Moore's 523-user survey: "lack of bass"
  and shrillness are the recurring complaints).
- But also: no *added* bass beyond restoration — Moore et al. 2016 found +10 dB
  LF boosts rated "boomy" for music. Restoring measured loss ≠ adding a smile
  curve. The app's rule of never boosting below the measured range
  (EDGE_BAND_FACTOR = 0.5 hold) is the right instinct; R3's half-gain curve
  only lifts the low bands if the *measured* 250 Hz threshold is actually
  elevated.

So: keep NAL-R as the validated baseline, add the flat-restoration shape as an
explicitly experimental alternative, and let the matched-loudness A/B — the one
instrument that is actually calibrated to Daniel's ears — pick per profile.

---

## 3. Q2 — What consumer sound-personalisation products actually do

Verified concrete detail; gaps are marked. "Blend vs scale": for a static gain
curve the two are mathematically identical (`s·IG` *is* the blend between flat
and full correction); the distinction only becomes real when the strength
control also changes compression, as in Mimi and Apple.

| Product | Measures | Processing / prescription | Strength control | Notes |
|---|---|---|---|---|
| **Mimi** (Focal Bathys, beyerdynamic, Skullcandy, Nothing, Teufel, Loewe/Philips TVs …) | Pure-tone thresholds (PTT test) or masked thresholds (MT), per ear | Loudness-model fitting ("Loudness Loss Fitting Module") driving **multi-band dynamic feedback compression + linear gain** — the published BioAid architecture (band-pass → broken-stick compression → delayed feedback attenuation → per-channel gain); explicitly *not* a static EQ, whose inadequacy their whitepapers argue | Intensity slider plus "Recommended / Richer / Softer" presets; the whitepaper's fitting figure shows literal "full compensation" vs "partial compensation" arrows — the slider is a partial-compensation fraction | Default slider position is not publicly documented (cannot check); Daniel observed 50/100 in the Focal & Naim app; a TechHive reviewer settled "at roughly 30 %"; Naim app-store reviews report a bug where intensity resets to 100 |
| **Apple Headphone Accommodations** (AirPods/Beats) | Optional audiogram from Health, or in-app Custom Audio Setup A/B; otherwise presets | Level-dependent gain, measured CR ≈ 1.5:1 at 4 kHz; audiogram fit approximates NAL targets best at 65 dB input, over-amplifies loud / under-amplifies soft inputs vs NAL-NL2 | Two-axis: **Tune** (Balanced Tone / Vocal Range / Brightness — measured peaks ≈ 12–15 dB at 2–5 kHz for 65 dB input) × **Strength** (Slight/Moderate/Strong, ≈ +8 dB per step) + fine amplification slider 0–100 % spanning ≈ 12 dB + tone slider (≈ +8 dB > 1.5 kHz to −4 dB < 1.5 kHz) | The clearest published consumer control anatomy, thanks to independent KEMAR measurements |
| **Apple Hearing Aid feature** (AirPods Pro 2, FDA OTC) | In-device pure-tone hearing test | Real WDRC; independent real-ear studies: matches NAL-NL2 REIG within tolerance at 1–4 kHz for 65 dB speech; under-amplifies soft, over-amplifies loud relative to NL2; up to 14 dB below a professionally fitted aid at high frequencies; LF over-amplified relative to targets | Volume/balance/tone adjustments post-fit | Independent bench data: NAL (Australia) and a 2026 electroacoustic comparison |
| **Samsung Adapt Sound** | Brief per-ear yes/no beep test at "different frequencies" (internals undisclosed — cannot check exact frequencies/levels) | Frequency-dependent compensation curve; also offers age-band presets (<30, 30–60, >60) | None beyond on/off + retest | Phone-side (works with any headphones) |
| **Audiodo Personal Sound** (Skullcandy, CMF by Nothing, PSB, Astell&Kern) | ~3-minute per-ear threshold test | Claims psychoacoustics-based, **volume-dependent** (level-dependent) compensation, per-ear asymmetry handling; formula undisclosed | Varies by OEM app; details undisclosed | Marketing stresses "not a preset EQ curve"; nothing verifiable beyond that |
| **Nura → Denon PerL** (Masimo AAT; Nura acquired by Denon/Masimo 2023 — not Sonova) | **Objective otoacoustic emissions**: tones played in-ear, cochlear response measured by in-ear mic; no button pressing | Profile built by "AI" from OAE strength per frequency; applied as boost/attenuation per band; exact mapping undisclosed | "Immersion" (bass) slider; personalization on/off A/B | Only consumer system measuring cochlear function rather than thresholds; measures outer-hair-cell response, which does not capture the full audiogram |
| **Bose** | CustomTune: chirp measured by in-ear mics → **ear-canal acoustics** (canal length, eardrum reflectivity, seal) | Corrects playback/ANC for the *ear's acoustics*, explicitly **not** hearing-loss compensation | n/a | Bose's hearing products (SoundControl hearing aids) were a separate, since-abandoned line — status details not re-verified here (cannot check) |
| **Sony** | Consumer: photo of ear for spatial personalisation; no audiogram feature in Sound Connect. OTC hearing aids (CRE-C10/E10/C20): in-app self-fit hearing check | OTC aids: self-fitting WDRC (co-developed with WS Audiology); consumer headphones: manual EQ only | OTC: volume + tone balance | Sony keeps hearing compensation strictly in the regulated product line |
| **Sennheiser / Sonova** (All-Day Clear OTC, Conversation Clear Plus) | Preference-based self-fit: choose between processed real-world conversation samples (volume/clarity/balance), not a tone audiogram | WDRC hearing-aid DSP (Sonova platform); music program included | Volume/clarity personalisation | Sonova cites evidence the self-fit matches professional fitting; underlying targets undisclosed |
| **Even (EarPrint)** | 8 frequencies per ear, 125 Hz–14 kHz, button-press threshold-style test built into headphone | "Patented compensation algorithm" adjusting frequency response; internals undisclosed | Personalisation on/off | Company activity today unclear (cannot check) |
| **Devialet Gemini II** | — | 6-band manual EQ + presets only; **no hearing personalisation found**, and Devialet does not appear on Mimi's partner list | — | Earlier assumption of a Mimi integration: not confirmable (cannot check) |

Pattern worth internalising: **every system that takes hearing loss seriously
(Mimi, Audiodo, Apple, all OTC aids) is level-dependent**, and every strength
control that is documented ends up as a *fraction of a prescribed correction*
with a mid-scale default and users settling below it.

---

## 4. Q3 — What should the slider mean?

**The options collapse.** For a static EQ, "fraction of prescribed insertion
gain" and "blend between flat and full correction" are the same operation
(`G = s·IG` is the linear interpolation in dB). A compression-ratio-like
control requires WDRC (v2). So the real v1 questions are the mapping, the
default, and the granularity.

**Mapping: keep linear-in-dB.** dB is already the approximately
perceptually-uniform scale for level; warping the slider (e.g. sone-based)
would make equal thumb movements produce wildly unequal dB steps. No consumer
product researched does this; Apple's fine slider spans its 12 dB range
linearly.

**Granularity: quantize to audibility.** Measured JNDs for gain increments:
≈ 3 dB for octave-band-width changes, 4/4/7 dB for single low/mid/high bands,
≈ 1.5 dB broadband; the authors recommend 3 dB fine-tuning steps and 5 dB
self-fitting steps. With max(G) capped at 12 dB, a slider step of x % changes
the loudest band by 0.12·x dB — steps under ~25 % are sub-JND at the loudest
band and pure placebo below that. Detents at 0/25/50/75/100 % (or a stepped
+/− control in ~2–3 dB effective increments) is the honest resolution;
a continuous slider may remain for feel, but the A/B toggle is what actually
verifies a change did something.

**Default: 0.5–0.6 is defensible; 0.6 stays.** Converging evidence:
NAL-R's 100 % is already the empirically preferred near-half gain; NAL-NL2's
empirical revision took a further ~3 dB off its theory because 45 % of users
wanted less; new users prefer ~2 dB less again (and adapt upward over months —
Daniel rejected a time ramp, so the default should simply sit where
acclimatised listeners land, with the slider doing the rest); the one measured
Mimi data point has a reviewer at ~30 % and Daniel's own app defaulting
mid-scale. `s = 0.6` × 0.46 ≈ 28 % of audiometric loss is squarely in the
region these systems converge on. No change recommended — only honest
labelling (R1), e.g.: "60 % of the NAL-R prescription ≈ counteracts about
28 % of your measured loss at typical listening levels; 100 % ≈ 46 %.
Full-threshold boost is never applied because loudness recruitment makes it
over-loud (see COMPENSATION.md §1)."

**Two-axis control (optional, later):** Apple and Mimi both split "how much"
from "which flavour" (Strength × Tune; Intensity × Richer/Softer). If R3 ships
a second curve shape, the profile picker *is* that second axis — do not add a
separate tone slider to the measurement-derived profile (Compensation.kt's
"measurement result, no manual controls" rule is correct and should survive
this research).

---

## 5. Q4 — The honest limits

**Dead regions.** A cochlear dead region (no functioning inner hair
cells/neurons) means a tone at that frequency is heard — if at all — via
off-place listening at neighbouring frequencies; boosting that band adds level
and distortion, not timbre. Facts with sources:

- Diagnosis requires the TEN(HL) test (masked vs absolute thresholds under a
  calibrated threshold-equalizing noise; ≥ 10 dB elevation flags a dead
  region) or psychophysical tuning curves. Both need calibrated presentation
  levels the app cannot produce on consumer hardware → the app's answer must
  be "cannot check", never "no dead regions".
- Audiogram-only heuristics that are published: at any frequency with
  threshold > 70 dB HL, ≥ 59 % of tested ears had a dead region there
  (Vinay & Moore 2007); clinical reviews put "probable" at
  ≥ 75–80 dB HL (low frequencies) and ≥ 90 dB HL (high frequencies). A steep
  slope is suggestive but has no validated threshold-only cutoff — treat slope
  as a hint, not a detector.
- For extensive high-frequency dead regions with edge frequency fe,
  amplification is beneficial only up to ≈ 1.7 × fe; above that it gives no
  benefit and for some listeners *reduces* performance (Baer, Moore & Kluk
  2002; replicated guidance across Moore's clinical reviews). The evidence
  base is speech, not music — for music, quality degradation from amplifying
  into a dead region is the expectation but is thinly documented (cannot
  check a music-specific study).

**Recruitment and reduced selectivity.** Even outside dead regions, outer
hair cell loss broadens auditory filters and steepens loudness growth; an EQ
restores neither frequency selectivity nor the compressive input-output
function. Restoring the *average spectral balance* at one listening level is
achievable; restoring *timbre in full* (spectral detail, masking patterns,
loudness relations across the dynamic range) is not possible in principle with
any static filter — and only partially possible with WDRC. This is the
scientific basis for the app's existing "Adjusted Reference" framing and must
stay in the UI copy.

**Above 8 kHz.** Extended-high-frequency audiometry needs specially calibrated
transducers (ISO 389-5; e.g. HDA200) because ear-canal standing waves make
per-individual level errors large; inter-subject variability is far higher
than below 8 kHz. On a consumer Bluetooth headphone a 10–14 kHz threshold
would be noise presented as data. Do not add EHF test frequencies; keep the
half-gain edge-hold above 8 kHz and keep marking those bands as extrapolated
(`EqBandLayout.extrapolatedIndices` already does).

**Practical rule for the app (R6):** flag any (device-corrected) threshold
> 70 dB HL; in flagged bands do not raise gain above the nearest unflagged
band's gain; show "possible dead region — cannot check on this hardware;
compensation limited here". Below that threshold, no dead-region logic — the
false-positive cost of guessing exceeds the benefit for a mild/moderate loss.

---

## 6. Q5 — Practical safety

**The pre-gain design is the strongest safety property; state it.** Because
pre-gain = −max(G) across ears, enabling compensation cannot raise peak output
at a fixed volume position; the curve only redistributes. The limiter guards
the residual (inter-band summation on broadband content). Both should stay.

**Exposure numbers that matter (for README/UI copy):**

- WHO safe-listening allowance: 80 dB(A) for ≈ 40 h/week, 3 dB exchange rate
  (83 dB(A) → 20 h, 86 dB(A) → 10 h). NIOSH occupational REL: 85 dB(A), 8 h/day,
  same exchange rate.
- EU device rules (EN 50332 family): warn at 85 dB(A), hard ceiling 100 dB(A)
  with repeated re-warning — the phone's media-volume layer already implements
  this envelope below the app (whether the Bathys adds its own limiting:
  cannot check).
- No published study defines a "maximum safe *boost*" for music listening —
  cannot check, because the literature frames risk as output level × time,
  not EQ gain. The honest safety statement is therefore about *listening
  level after the user re-raises volume*, not about the 12 dB cap.
- One counter-pressure worth citing: Mimi's (self-published) listening study
  claims personalisation lets users *lower* their chosen volume. Plausible,
  vendor-sourced, unreplicated — cite only as "vendor claim".

**Is 12 dB per band sensible?** Yes, and conservative. Full NAL-R for a flat
40 dB loss prescribes ≈ 17–18 dB mid-band; the cap therefore binds from
moderate losses upward at s = 1 (and from ≈ 45 dB loss at s = 0.6), which is
consistent with the app's "see a professional above PTA 60" posture — the app
deliberately stops competing with hearing aids where hearing aids are the
right tool. Apple's consumer feature spans a comparable ~12 dB fine range with
up to ~2 × 8 dB more via Strength presets; OTC-aid rules regulate *output*
(SPL ceilings), not per-band gain, so there is no external number to import —
the cap is a house choice and 12 dB is a reasonable one. Keep.

**Is 6 dB/octave slope-limiting sensible?** No literature validates a specific
inter-band gain-slope ceiling — cannot check; it is a house heuristic. Two
facts bracket it: NAL-R itself only ever prescribes ≈ 0.31 × audiogram slope,
so a 20 dB/oct ski-slope audiogram wants ≈ 6 dB/oct of gain slope — the cap
sits exactly at the edge of what the prescription can legitimately ask for,
and it will shave truly steep mild losses slightly. And smoothness has quality
value: for normal-hearing listeners, response ripples beyond ± 5 dB measurably
degrade rated quality. Verdict: keep 6 dB/oct for the octave layout, but note
in code that on finer layouts the per-pair scaling (already implemented) is
what keeps the rule meaningful; if the A/B ever shows the cap flattening a
real notch correction, loosening to ~8 dB/oct would still be inside NAL-R's
own slope logic for a 25 dB/oct audiogram.

---

## 7. Q6 — Does 10-band octave resolution suffice?

**No — for this specific pipeline it is provably lossy at the frequencies that
matter most, and the fix already exists in the codebase.**

1. **The 3k/6k blindness (decisive).** The octave centres 250…8000 Hz coincide
   with six of the eight test frequencies. `mapToBands` evaluates the gain
   curve *at the band centres*; a monotone interpolant returns knot values at
   knots, so the measured 3000 Hz and 6000 Hz thresholds influence no band —
   they are also absent from the PTA (500/1k/2k). A 3 or 6 kHz notch — the
   classic noise-damage signature, and the steepest part of a typical
   age-related slope — produces zero compensation in the 10-band layout no
   matter how large it is. The 20-band layout (3200/6400 Hz centres) and the
   31-band layout (3150/6300 Hz) capture both.
2. **Slope representation.** One-octave spacing plus the 3-point moving average
   low-passes the curve; the code's own measurement (NalR.kt doc comment)
   quantifies the interpolation side of this: reconstructing a 4 kHz dip is
   off by up to 3.6 dB piecewise-linear vs 1.25 dB monotone-cubic from the
   same points — resolution and interpolation both matter more than adding
   test tones.
3. **External reference points.** Prescriptions publish targets at audiometric
   (≈ third-octave) spacing; research fittings use many more channels than 10
   (Vaisberg's simulator: 21 bands; CAM2 prescribes per-channel parameters up
   to 10 kHz); consumer measurement data (the calibration presets' source) is
   published third-octave. Ten octave bands is a *display* convention, not a
   fitting resolution.
4. **Counterweight.** More bands only help the *static* curve land where it
   was measured; they do not add information beyond the 8 test frequencies.
   Between 20 and 31 bands the audiogram is the bottleneck, not the EQ — on
   the 20-band grid every band gain is interpolated from the full 8-point
   curve, and 3 kHz / 6 kHz get centres within 7 % (3200 / 6400 Hz); the other
   test frequencies sit at most ~0.18 octave from a centre, well inside the
   moving-average window. Recommendation: default the Adjusted Reference to
   20 bands (R2);
   31 bands is fine but buys accuracy only below 250 Hz and above 8 kHz, where
   the app deliberately extrapolates anyway.

`DynamicsProcessing` sets per-band cutoffs at construction (already handled by
`writeBand`), so this is purely a default change plus the existing rebuild
path.

---

## 8. What we must not claim

- **Not** "restores your hearing" / "you hear what normal-hearing people
  hear". Achievable: approximate restoration of *average tonal balance at
  your typical listening level*. Reduced frequency selectivity, recruitment,
  and any dead regions are not correctable by any EQ.
- **Not** "compensates X % of your hearing loss" on the slider. The slider
  scales a *prescription* that is itself ≈ 46 % of the loss by design;
  label percentages as "of prescription", optionally with the derived
  "≈ N dB at 4 kHz" read-out.
- **Not** dB HL, audiogram, diagnosis. Already policed by Audiogram.kt's
  honesty rule; extends to the dead-region guard: the app can only say
  "cannot check for dead regions", never "none present".
- **Not** "clinically validated prescription for music". NAL-R is validated
  for speech-band loudness equalization; the flat-restoration curve (R3) is
  explicitly experimental; CAM2's music advantage is real but CAM2 itself is
  not what the app implements.
- **Not** "safe for your hearing" as a blanket claim. Claimable: "at the same
  volume position, enabling compensation does not increase peak output"
  (pre-gain design) — paired with the WHO 80 dB(A)/40 h budget note, because
  the user can and will raise the volume.
- **Not** any behaviour above 8 kHz or below 250 Hz as measured. Those bands
  are extrapolated at half weight and must remain marked as such.
- **Not** the Mimi/Focal default of 50 as a documented industry standard —
  it is observed in one app version, undocumented publicly (their fitting
  model, by contrast, *is* documented: partial compensation of a
  loudness-restoration target).

---

## 9. Sources

### Prescriptions and their validation

- [Byrne & Dillon 1986 — The NAL new procedure (NAL-R), ResearchGate](https://www.researchgate.net/publication/19417854_The_National_Acoustic_Laboratories_NAL_New_Procedure_for_Selecting_the_Gain_and_Frequency_Response_of_a_Hearing_Aid)
- [The Research of Denis Byrne at NAL (0.46 average-gain / 0.31 slope derivation) — AudiologyOnline](https://www.audiologyonline.com/articles/research-denis-byrne-at-nal-1200)
- [The NAL-NL1 Fitting Method (loudness equalization vs normalization philosophy) — AudiologyOnline](https://www.audiologyonline.com/articles/the-nal-nl1-fitting-method-1260)
- [Keidser, Dillon, Carter, O'Brien 2012 — NAL-NL2 Empirical Adjustments, Trends in Amplification](https://journals.sagepub.com/doi/full/10.1177/1084713812468511)
- [Rajkumar et al. 2013 — NAL-R formula as implemented (UJBE 1(2):32-41)](https://www.hrpub.org/download/20131107/UJBE2-10601674.pdf)
- [Moore & Glasberg 1998 — Use of a loudness model for hearing-aid fitting I: linear hearing aids (Cambridge formula; paywalled — coefficients not verified)](https://www.tandfonline.com/doi/abs/10.3109/03005364000000083)
- [Moore et al. — Comparison of the NAL(R) and Cambridge formulae (PubMed 10759075)](https://pubmed.ncbi.nlm.nih.gov/10759075/)
- [Moore, Glasberg & Stone 2010 — CAMEQ2-HF development, Int J Audiology](https://www.tandfonline.com/doi/abs/10.3109/14992020903296746)
- [Evaluation of CAMEQ2-HF with multichannel compression (PubMed 20526199)](https://pubmed.ncbi.nlm.nih.gov/20526199/)
- [CAM2 (CAMEQ2-HF) fitting software — Cambridge Dept. of Psychology ("audiogram alone", 10 kHz targets)](https://www.psychol.cam.ac.uk/hearing/cam2-cameq2-hf-hearing-aid-fitting-software)
- [CAM2 software licensing — Cambridge Enterprise](https://www.enterprise.cam.ac.uk/reagents/cam2-software-for-hearing-aid-fitting/)
- [Moore & Sęk 2013 — Comparison of CAM2 and NAL-NL2 (music preference trial), Ear & Hearing](https://journals.lww.com/ear-hearing/Abstract/2013/01000/Comparison_of_the_CAM2_and_NAL_NL2_Hearing_Aid.9.aspx)
- [Moore & Sęk 2016 — CAM2A vs NAL-NL2 across a wide range of losses (PubMed 26470732)](https://pubmed.ncbi.nlm.nih.gov/26470732/)
- [Johnson 2013 — Initial-fit comparison NAL-NL2 vs CAM2 (PubMed 23357807)](https://pubmed.ncbi.nlm.nih.gov/23357807/)
- [Moore et al. — Comparison of three procedures (CAMEQ / CAMREST / DSL i/o) I, Br J Audiol](https://www.tandfonline.com/doi/abs/10.1080/00305364.2001.11745252)
- [Comparison of three procedures III — inexperienced vs experienced users (PubMed 15250124)](https://pubmed.ncbi.nlm.nih.gov/15250124/)
- [Moore 2014 — Development and current status of the "Cambridge" loudness models, Trends in Hearing](https://journals.sagepub.com/doi/10.1177/2331216514550620)
- [Evolving the philosophy: from the NAL rule to NAL-NL3 (2026, paywalled — not verified in detail)](https://www.tandfonline.com/doi/full/10.1080/14992027.2026.2690236)

### Music-specific evidence

- [Madsen & Moore 2014 — Music and Hearing Aids (523-user survey), Trends in Hearing](https://journals.sagepub.com/doi/10.1177/2331216514558271)
- [Moore (feature summary of the above) — Canadian Audiologist](https://canadianaudiologist.ca/moore-feature-10/)
- [Vaisberg, Beaulac, Glista, Macpherson & Scollie 2021 — Preferred frequency-gain shaping for speech and music (open PDF)](https://uwo.scholaris.ca/server/api/core/bitstreams/eb0bf5ec-7642-4afa-9cfd-b6d6e02eef51/content)
- [Moore & Sęk 2016 — Preferred compression speed for speech and music (slow ≈ safe default), PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC5017572/)
- [Moore 2012 — Effects of bandwidth, compression speed and HF gain on preferences for amplified music (PubMed 23172008)](https://pubmed.ncbi.nlm.nih.gov/23172008/)
- [Greasley et al. 2020 — Music listening and hearing aids: perspectives from audiologists and patients (paywalled)](https://www.tandfonline.com/doi/full/10.1080/14992027.2020.1762126)
- [Greasley et al. 2026 — Using hearing aids for music: UK survey (Sage, fetch blocked — headline only)](https://doi.org/10.1177/23312165251396517)
- [Hake et al. 2025 — Compression differentially affects musical scene analysis and sound quality, Trends in Hearing](https://journals.sagepub.com/doi/10.1177/23312165251368669)
- [Chasin-school music-program practice (input headroom, linear/slow WDRC) — AudiologyOnline 20Q](https://www.audiologyonline.com/articles/20q-optimizing-hearing-aid-processing-28684) and [Hearing Review Back-to-Basics](https://hearingreview.com/practice-building/practice-management/continuing-education/back-basics-music-listening-hearing-aids-approaches-stop-listen)

### Dead regions and limits of amplification

- [Vinay & Moore 2007 — Prevalence of dead regions (≥ 59 % of ears at thresholds > 70 dB HL), Ear & Hearing](https://www.ovid.com/jnls/ear-hearing/abstract/10.1097/aud.0b013e31803126e2~prevalence-of-dead-regions-in-subjects-with-sensorineural)
- [Diagnosing cochlear dead regions and rehabilitation implications — PMC review](https://pmc.ncbi.nlm.nih.gov/articles/PMC9443717/)
- [Baer, Moore & Kluk 2002 — Low-pass filtering / dead regions (basis of the 1.7·fe rule), ResearchGate](https://www.researchgate.net/publication/11148212_Effects_of_low_pass_filtering_on_the_intelligibility_of_speech_in_noise_for_people_with_and_without_dead_regions_at_high_frequencies)
- [20Q: Frequency lowering (states the 1.7·fe guidance) — AudiologyOnline](https://www.audiologyonline.com/articles/20q-highs-and-lows-frequency-11772)
- [TEN(HL) test procedure — Interacoustics Academy](https://www.interacoustics.com/academy/audiometry-training/advanced-tests/threshold-equalizing-noise-ten-test)
- [Cochlear dead regions in typical hearing-aid candidates — PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC3085835/)

### Extended high frequencies

- [Extended high-frequency audiometry in research and clinical practice — JASA 2022](https://pubs.aip.org/asa/jasa/article/151/3/1944/2838347/Extended-high-frequency-audiometry-in-research-and)
- [Understanding EHF hearing (standing waves, calibration) — ICA 2019 (PDF)](https://pub.dega-akustik.de/ICA2019/data/articles/000295.pdf)
- [High-frequency audiometry hardware requirements — Interacoustics](https://www.interacoustics.com/academy/audiometry-training/pure-tone-audiometry/high-frequency-audiometry)

### Consumer products

- [Mimi — Technology and patents (Loudness Loss Fitting Module, dynamic multiband compression)](https://mimi.io/technology)
- [Mimi — Hearing Loss and Compensation whitepaper, March 2023 (loudness-loss model, BioAid-style circuit, full vs partial compensation) (PDF)](https://info.mimi.io/hubfs/Mimi.io%20-%20White%20Papers/Mimi%20Hearing%20Technologies_Loudness%20Loss%20and%20Compensation_March%202023.pdf)
- [Mimi — Beyond EQ: why personalization requires non-linear processing](https://mimi.io/blog/beyond-eq-why-hearing-aware-sound-personalization-requires-non-linear-processing)
- [Mimi — White papers and studies index (incl. listening-level vendor study)](https://mimi.io/hearing-science/white-papers-and-studies)
- [Mimi — PTT hearing-test explanation](https://mimi.io/blog/how-to-interpret-your-hearing-test-results-ptt-test-for-sound-personalization)
- [Mimi partner list](https://mimi.io/partners) · [Mimi × Focal](https://mimi.io/partners/focal)
- [Focal Bathys + Mimi — Sound & Vision](https://www.soundandvision.com/content/focal-brings-personalized-sound-bathys-headphones) · [TechHive review (reviewer at ~30 % intensity)](https://www.techhive.com/article/2069707/focal-bathys-users-can-customize-their-headphone-experience.html) · [Focal & Naim app reviews (intensity-reset bug)](https://naim.appstor.io/app-reviews)
- [Jürgens, Clark, Lecluyse & Meddis 2016 — Physiologically-inspired hearing-aid algorithm (BioAid), Int J Audiology](https://doi.org/10.3109/14992027.2015.1135352)
- [Clark, Lecluyse & Jürgens 2017 — Compressive properties of BioAid, Int J Audiology](https://www.tandfonline.com/doi/full/10.1080/14992027.2017.1378931)
- [BioAid source code — GitHub (audioplastic/BioAid)](https://github.com/audioplastic/BioAid)
- [Apple — Headphone Accommodations support document](https://support.apple.com/guide/airpods/set-headphone-accommodations-devcd05671ab/web)
- [Evaluating AirPods Pro with Headphone Accommodations (KEMAR measurements: preset shapes, 12 dB slider span, 8 dB strength steps, CR ≈ 1.5:1) — Hearing Review](https://hearingreview.com/inside-hearing/research/evaluating-apple-airpods-pro-with-headphone-accommodations-as-hearing-devices)
- [Evaluating AirPods Pro 2 Hearing Aid software — Hearing Review](https://hearingreview.com/hearing-products/hearing-aids/otc/evaluating-apple-airpods-pro-2-hearing-aid-software-acoustic-measurements-and-insights)
- [NAL — AirPods Pro 2 hearing-aid feature testing](https://www.nal.gov.au/projects/airpods-pro-2-hearing-aid-feature-testing/)
- [Electroacoustic verification of AirPods Pro 2/3 vs fitted hearing aids (up to 14 dB HF shortfall) — MDPI Audiology Research](https://www.mdpi.com/2039-4349/16/2/55)
- [Samsung Adapt Sound guide — MakeUseOf](https://www.makeuseof.com/samsung-galaxy-adapt-sound-change-how-headphones-sound/) · [Samsung support](https://www.samsung.com/latin_en/support/mobile-devices/how-to-customize-the-adapt-sound/)
- [Audiodo Personal Sound](https://www.audiodo.com/solutions/personal-sound/) · [Forbes on Audiodo in CMF Buds 2 Plus (volume-dependent claim)](https://www.forbes.com/sites/marksparrow/2025/04/28/audiodo-brings-personal-sound-to-new-buds-2-plus-earbuds-from-cmf-by-nothing/) · [Nothing support — What is Personal Sound](https://support.nothing.tech/hc/en-us/articles/34147095344529-What-is-Personal-Sound)
- [Nura's OAE-based profiling — Hearing Health Matters](https://hearinghealthmatters.org/hearing-technologies/2017/otoacoustic-emissions-oaes-personalized-listening-nura-headphones/) · [Masimo/Denon PerL with AAT (press release)](https://www.masimo.com/media/masimo-expands-into-the-personalized-hearables-market-with-denon-perl-true-wireless-earbuds-featuring-masimo-adaptive-acoustic-technology-aat) · [Audioholics on the Nura→Denon acquisition](https://www.audioholics.com/headphone-reviews/denon-perl-pro-earbud)
- [Bose CustomTune (ear-canal acoustics, not hearing loss) — Bose](https://www.bose.com/stories/sound-shaped-to-you-bose-customtune-technology) · [Fast Company on CustomTune](https://www.fastcompany.com/90782756/how-boses-new-earbuds-customize-sound-to-your-ear-shape)
- [Sony Sound Connect (ear-shape spatial personalisation, manual EQ)](https://apps.apple.com/us/app/sony-sound-connect/id1168502924) · [Sony CRE-C10 self-fitting OTC](https://audiologyisland.com/shop/otc-hearing-aids/sony-self-fitting/sony-crec10-otc-hearing-aids/)
- [Sennheiser All-Day Clear (Sonova, self-fit)](https://global.sennheiser-hearing.com/products/sennheiser-all-day-clear) · [Sonova press release](https://www.sonova.com/en/sonova-further-extends-offering-sennheiser-branded-hearing-solutions-all-day-clear)
- [Even EarPrint (8 frequencies, 125 Hz–14 kHz) — Audioholics](https://www.audioholics.com/editorials/even-earprint-headphones-customize-sound) · [Forbes](https://www.forbes.com/sites/marksparrow/2017/07/03/these-headphones-have-a-built-in-listening-test-and-theyre-like-glasses-for-your-ears/)
- [Devialet Gemini app (6-band EQ, no personalisation)](https://apps.apple.com/us/app/devialet-gemini/id1498489424)

### Slider granularity / self-adjustment

- [Caswell-Midwinter & Whitmer 2019 — Discrimination of gain increments in speech (JNDs 4/4/7 dB per band), PMC](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC6862772/)
- [Caswell-Midwinter & Whitmer — Gain increments in speech-shaped noises (≈3 dB octave-band, 1.5 dB broadband; 3 dB fine-tuning / 5 dB self-fit recommendation), PMC](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC6351966/)

### Safety and exposure

- [WHO — Safe listening Q&A (80 dB(A) / 40 h weekly allowance)](https://www.who.int/news-room/questions-and-answers/item/deafness-and-hearing-loss-safe-listening)
- [WHO-ITU safe listening devices standard](https://www.who.int/publications-detail/safe-listening-devices-and-systems-a-who-itu-standard)
- [Hearing Health Foundation — decibel/time table (NIOSH 85 dB(A)/8 h, 3 dB exchange)](https://hearinghealthfoundation.org/keeplistening/decibels)
- [EN 50332 overview (85 dB warning / 100 dB ceiling; part 3 dose management)](https://www.spilma.com/en/guides/acoustic-safety-en-50332)
- [ITU situation analysis of safe-listening standards (PDF)](https://www.itu.int/en/ITU-T/Workshops-and-Seminars/safelistening/Documents/Standards_for_safe_listening_devices_situation_analysis_report.pdf)

### Platform

- [Android `DynamicsProcessing` (pre-EQ / MBC / post-EQ / limiter stages; API 28+)](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing)
