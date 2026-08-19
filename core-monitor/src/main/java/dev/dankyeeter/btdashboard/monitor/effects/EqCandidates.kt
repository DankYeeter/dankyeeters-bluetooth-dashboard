package dev.dankyeeter.btdashboard.monitor.effects

/**
 * Models for the "which installed apps might carry their own equaliser" check.
 *
 * This is deliberately a *different question* from the one [ForeignEqScanner]
 * answers. That scanner reads `dumpsys media.audio_flinger` and reports effect
 * chains that are genuinely attached to the audio pipeline right now — a fact.
 * This file answers "what should the user go and look at", which is a hint and
 * is worded as one everywhere it surfaces.
 *
 * What we deliberately do **not** do, and why:
 *
 *  - **No APK/dex scanning for `android.media.audiofx` references.** It is slow
 *    (megabytes per app), it is defeated by any obfuscator, and it says nothing
 *    about native DSP compiled into a `.so`. A detector that is wrong in both
 *    directions is worse than an honest "check this yourself".
 *  - **No claim about apps filtering in their own code.** An app can run a
 *    biquad cascade on its own PCM buffer, or ship the EQ inside the headphone
 *    (the Focal case), and leave *zero* trace on Android. Absence of evidence
 *    here is not evidence of absence, and the UI says exactly that.
 */

/**
 * Why an app is on the list. Each constant is a different *kind* of evidence,
 * not a different amount of the same evidence, so each carries its own wording.
 *
 * [order] is also the display order: strongest kind of evidence first.
 */
enum class EqEvidence(val order: Int, val reason: String) {

    /**
     * The app registers an activity for
     * `android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL`.
     *
     * Not a guess. That intent filter *is* the app telling Android "I own an
     * equaliser UI, send users here" — it is how Wavelet, Poweramp-style
     * players and most vendor ROM equalisers advertise themselves. An app that
     * declares it and has no EQ would be a broken app, not a false positive.
     */
    DECLARED_PANEL(0, "provides a system equalizer panel"),

    /**
     * On the curated companion list ([VendorEqApps]). The equaliser lives in
     * the headphone's own DSP, so no audio-side scan can ever see it; the app
     * being installed is the entire available signal.
     */
    VENDOR_COMPANION(1, "headphone companion app"),

    /**
     * Requests `MODIFY_AUDIO_SETTINGS`, which is what attaching an
     * `android.media.audiofx.Equalizer` to a session requires.
     *
     * The weakest tier by a wide margin: countless apps hold this permission
     * for volume, routing or focus reasons and never touch an effect. It is
     * a *necessary* condition, not a sufficient one, and the UI keeps it
     * collapsed behind "show more" for exactly that reason.
     */
    AUDIO_EFFECT_PERMISSION(2, "can attach audio effects"),
}

/** One app the user might want to check, with the evidence behind it. */
data class EqCandidate(
    val packageName: String,
    val appLabel: String,
    /** Strongest evidence first, never empty. Build via [of] to keep it sorted. */
    val evidence: List<EqEvidence>,
    /** Producing audio at the moment of the scan. Context, never evidence. */
    val playingNow: Boolean = false,
    /** Set when the package is on the curated companion list. */
    val vendor: String? = null,
) {
    val primaryEvidence: EqEvidence get() = evidence.first()

    /** Human-readable "why is this listed", e.g. "provides a system equalizer panel". */
    val reason: String get() = evidence.joinToString(" · ") { it.reason }

    /** The weak tier is everything whose *only* signal is the permission. */
    val isWeak: Boolean get() = evidence.singleOrNull() == EqEvidence.AUDIO_EFFECT_PERMISSION

    companion object {
        fun of(
            packageName: String,
            appLabel: String,
            evidence: Collection<EqEvidence>,
            playingNow: Boolean = false,
            vendor: String? = null,
        ): EqCandidate? {
            val sorted = evidence.distinct().sortedBy { it.order }
            if (sorted.isEmpty()) return null
            return EqCandidate(
                packageName = packageName,
                appLabel = appLabel.ifBlank { packageName },
                evidence = sorted,
                playingNow = playingNow,
                vendor = vendor,
            )
        }
    }
}

/**
 * Display order: playing now first, then declared, vendor, capable.
 *
 * "Playing now" jumps the queue because an app that is *both* capable and
 * currently producing audio is the one that can be affecting what the user is
 * hearing this second — which is the whole point of the check. Ties break on
 * label then package so the list does not reshuffle between scans.
 */
object EqCandidateRanking {

    val comparator: Comparator<EqCandidate> = compareBy(
        { if (it.playingNow) 0 else 1 },
        { it.primaryEvidence.order },
        { it.appLabel.lowercase() },
        { it.packageName },
    )

    fun sort(candidates: List<EqCandidate>): List<EqCandidate> = candidates.sortedWith(comparator)
}

/**
 * Result of one candidate scan.
 *
 * [available] `false` means "cannot check" — never a clean bill of health.
 * The same rule the audio_flinger scanner already follows.
 */
data class EqCandidateScan(
    val candidates: List<EqCandidate> = emptyList(),
    val available: Boolean = true,
    val unavailableReason: String? = null,
    /** How many installed packages the last full pass looked at. */
    val scannedPackages: Int = 0,
    /** Wall-clock cost of the last *full* pass, so the UI can show the price. */
    val durationMs: Long = 0,
    /** True when this scan reused the cached package pass. */
    val fromCache: Boolean = false,
    /** False when the playing-now overlay could not be read; see [playbackNote]. */
    val playbackKnown: Boolean = true,
    val playbackNote: String? = null,
) {
    /** Declared + vendor: shown by default. */
    val strong: List<EqCandidate> = candidates.filterNot { it.isWeak }

    /** Permission-only: collapsed behind "show more". */
    val weak: List<EqCandidate> = candidates.filter { it.isWeak }

    val isEmpty: Boolean get() = candidates.isEmpty()
}
