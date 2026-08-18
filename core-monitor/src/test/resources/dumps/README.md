# Golden sample dumps

These files are **shape-faithful reconstructions** of `dumpsys bluetooth_manager`
and `dumpsys media.audio_flinger` output, not verbatim captures — they were
written from the AOSP dump code paths so the parsers can be pinned down before a
device is available. Line wording, indentation, key spellings, redacted MACs
(`xx:xx:xx:xx:ab:cd`) and the `codecConfig:{...}` layout follow the real format.

When a real capture is taken on the Pixel 8 Pro and the Pixel 11 Pro, drop it in
here **verbatim** under a version-tagged name (`bt_manager_android17_pixel11.txt`)
and add a golden test for it. That is the whole point of the golden-sample setup:
the dump format is version-fragile, so every version we care about gets its own
pinned sample. Existing samples are never edited to make a parser pass.
