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

Two things about `bt_manager_pixel11_ldac_txqueue.txt` are load-bearing and were
kept deliberately rather than tidied away:

- `mCodecSpecific1:0` on a real LDAC link. That is what an untouched phone
  looks like — nobody has pinned an LDAC quality in Developer options — and it
  is the reason the app cannot show a live LDAC bitrate.
- `codecConfigOffloading` listing only SBC, AAC and Opus. LDAC's absence from
  that list is what makes the `A2DP State:` tx-queue counters in the same file
  real numbers rather than a frozen leftover.

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
