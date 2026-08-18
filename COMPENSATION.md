# Compensation Curve Specification (authoritative)

Written by Fable after literature research (2026-08-18). Worker C implements
strictly against this spec. Goal: **reference-faithful** playback — the EQ
restores what Daniel's ears attenuate, and changes nothing else.

## 1. Background: why not "boost by the full threshold shift"

Sensorineural hearing loss raises thresholds but usually **not** the
uncomfortable-loudness ceiling. Loudness growth between the two steepens
(**loudness recruitment**, caused by loss of the basilar membrane's
compressive nonlinearity). Consequences:

- A person may need +30 dB to *detect* a tone but perceives an 80 dB SPL
  passage almost normally loud.
- Full-threshold compensation therefore over-amplifies everything above
  threshold: shrill, fatiguing, distorted. This is why every serious system
  (hearing aids, Mimi, Audiodo) applies **partial, sub-threshold gain**.
- The theoretically correct fix is level-dependent gain (WDRC — more gain
  for quiet passages, less for loud ones). A static EQ is a compromise
  tuned for typical music listening levels (~65–80 dB SPL). We accept this
  for v1 and document it; optional level-dependent gain is a v2 candidate.

## 2. Prescription formulas considered

| Formula | Rule | Verdict for us |
|---|---|---|
| Lybarger half-gain (1944) | gain = 0.5 × H_T (POGO: −10 dB @250, −5 dB @500) | Too blunt; no slope handling |
| **NAL-R (Byrne & Dillon 1986)** | see below — "half gain + third slope" | **Chosen baseline**: best-validated linear rule for mild/moderate loss, explicitly loudness-equalizing across bands |
| NAL-RP, POGO II | severe/profound-loss variants | Not applicable (severe loss is out of scope; app shows a "see a professional" notice above ~60 dB HL) |
| NAL-NL1/NL2, DSL i/o, FIG6 | nonlinear (level-dependent) | Correct long-term; requires WDRC → v2 |

### NAL-R (exact, from Rajkumar et al. 2013, UJBE 1(2):32-41)

```
PTA   = (H500 + H1000 + H2000) / 3          (per ear, dB HL)
X     = 0.15 × PTA
IG(f) = X + 0.31 × H_T(f) + C(f)
```

C(f) correction table (dB):

| Hz | 250 | 500 | 1000 | 2000 | 3000 | 4000 | 6000 |
|----|-----|-----|------|------|------|------|------|
| C  | −17 | −8  | +1   | −1   | −2   | −2   | −2   |

Interpolate C linearly on log-frequency for our 8 test frequencies
(250…8000 Hz; use C(8000) = C(6000) = −2). Clamp IG(f) ≥ 0.

Note: NAL-R equalizes *speech-band* loudness. For music we keep its shape
logic (it is exactly the "loudness equalization" both Mimi and Audiodo
pursue) but expose overall strength via the intensity slider instead of
prescribing 100 %.

## 3. Our pipeline (per ear)

1. **Thresholds:** median of ≥3 Hughson-Westlake runs per frequency
   (per-frequency median, outlier runs deletable).
2. **Device correction:** subtract the headphone's frequency-response
   deviation from flat (Bathys/Encore presets from public measurement data;
   generic fallback = no correction) → approximated H_T(f) in dB HL.
   This is consumer calibration, not clinical dB HL — README disclaimer.
3. **Target gain:** NAL-R IG(f) as above.
4. **Intensity slider** s ∈ [0, 1], default **0.6**, UI labeled 0–100 %:
   `G(f) = s × IG(f)`. (Mimi-style partial compensation; the old
   "30–50 % of threshold shift" heuristic corresponds to s ≈ 0.5–0.8 of
   NAL-R for typical mild slopes.)
5. **Safety clamps:** per-band cap **+12 dB**; inter-band slope cap
   6 dB/octave (smooth with a 3-point moving average on the band gains) —
   prevents narrow resonant peaks recruitment would punish.
6. **Band mapping:** evaluate G(f) at the 10 EQ band centers
   (31.5, 63, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz) by log-frequency
   interpolation; below 250 Hz and above 8 kHz hold the edge value scaled
   by 0.5 (no audiometric data there — never extrapolate full gain).
7. **Headroom:** pre-gain = −max(G) across both ears (avoids digital
   clipping); optional peak limiter after the EQ stays independent.
8. **Ears are fully independent** (Audiodo-style asymmetry handling);
   optionally display left/right difference.

## 4. Validation protocol
- A/B toggle flat ↔ compensated at matched loudness (pre-gain applied in
  both states so louder ≠ "better").
- Re-test after ~4 weeks; median updates the curve.
- Start listening at s ≤ 0.5 and increase over 1–2 weeks (acclimatization).

## 5. Explicit non-claims
Not a medical device; thresholds are device-approximated, not clinical
dB HL; static gain is a compromise vs. true recruitment compensation
(documented in README; WDRC is the v2 path).
