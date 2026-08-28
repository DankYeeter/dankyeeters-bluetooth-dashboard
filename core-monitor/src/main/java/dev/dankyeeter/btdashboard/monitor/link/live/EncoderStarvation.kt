package dev.dankyeeter.btdashboard.monitor.link.live

import dev.dankyeeter.btdashboard.monitor.effects.AudioFlingerEffectParser
import kotlin.math.roundToInt

/**
 * How many effect instances one audio session was carrying at capture time.
 *
 * The count, not the identity, is the fact that matters: the failure this whole
 * file exists for is *accumulation* — the same effect attached to the same
 * session more than once — and a list of names cannot show that while a count
 * can.
 */
data class SessionEffectCount(val sessionId: Int, val effectCount: Int)

/**
 * What the audio processing graph looked like at one instant.
 *
 * Everything here is MEASURED: it is `dumpsys media.audio_flinger`'s own effect
 * section and `dumpsys audio`'s own player list, parsed and counted. Nothing is
 * inferred, and an unreadable section produces [note] rather than a zero — a
 * zero here would read as "no effects were attached", which is the opposite of
 * "the dump could not be parsed".
 */
data class EffectChainForensics(
    /** MEASURED: total effect instances across every chain in the dump. */
    val effectInstances: Int,
    /** MEASURED: how many audio sessions carried at least one effect. */
    val sessionsWithEffects: Int,
    /** MEASURED: the per-session breakdown, heaviest session first. */
    val effectsPerSession: List<SessionEffectCount>,
    /** MEASURED, verbatim: the distinct effect names seen, in order of first appearance. */
    val effectNames: List<String>,
    /** MEASURED: session ids of everything `state:started` in the player list. */
    val playbackSessionIds: List<Int>,
    /** Why a section is missing or empty. Shown, never swallowed. */
    val note: String? = null,
) {
    /** True when neither dump yielded anything countable. */
    val isEmpty: Boolean
        get() = effectInstances == 0 && playbackSessionIds.isEmpty()

    companion object {
        /** The honest answer when there was no dump to read at all. */
        fun unavailable(reason: String) = EffectChainForensics(
            effectInstances = 0,
            sessionsWithEffects = 0,
            effectsPerSession = emptyList(),
            effectNames = emptyList(),
            playbackSessionIds = emptyList(),
            note = reason,
        )
    }
}

/**
 * One tripped forensic capture: the encoder was starving, and this is what the
 * processing graph looked like while it was.
 *
 * @property underflowsPerSecond DERIVED from `btif_a2dp_source`'s underflow
 *   counter across [windowMs]. See [A2dpTxDelta.underflowsPerSecond].
 * @property sustainedPasses how many consecutive polls were over the threshold
 *   when the capture fired. Carried because "one bad window" and "six seconds
 *   of continuous starvation" are different findings.
 */
data class EncoderStarvationReport(
    val timestampMs: Long,
    val deviceAddress: String?,
    val deviceName: String?,
    val underflowsPerSecond: Double,
    val windowMs: Long,
    val sustainedPasses: Int,
    val forensics: EffectChainForensics,
) {

    /**
     * The one line the events list shows, finished and ready to render.
     *
     * Written to say what was counted and nothing more. It deliberately does
     * **not** say that the effects caused the starvation: the two were observed
     * at the same moment, which is a correlation and is exactly as much as this
     * capture can support. Naming the counts lets whoever reads it later decide.
     */
    val detail: String
        get() = buildString {
            append("Encoder starving — ")
            append(underflowsPerSecond.roundToInt())
            append(" encoder underflows/s over ")
            append(sustainedPasses)
            append(" consecutive polls; ")
            if (forensics.effectInstances > 0) {
                append(forensics.effectInstances)
                append(" effect instance")
                if (forensics.effectInstances != 1) append("s")
                append(" on ")
                append(forensics.sessionsWithEffects)
                append(" session")
                if (forensics.sessionsWithEffects != 1) append("s")
                append(" at the time")
                val playing = forensics.playbackSessionIds.size
                if (playing > 0) {
                    append(", ")
                    append(playing)
                    append(" session")
                    if (playing != 1) append("s")
                    append(" playing")
                }
            } else {
                append("the effect chains could not be counted")
                forensics.note?.let { append(" (").append(it).append(")") }
            }
        }
}

/**
 * Turns the parsed dumps of one poll into an [EffectChainForensics].
 *
 * Pure text in, counts out — so the whole capture is testable against real
 * device dumps without a phone, which is the only way this can be trusted to
 * still work the next time it fires.
 */
object EncoderStarvationForensics {

    /**
     * @param flingerDump the `dumpsys media.audio_flinger` text of *this* poll.
     * @param audioDump the `dumpsys audio` text of *this* poll.
     *
     * Both are the dumps [LiveLinkSource] already read for the reading itself.
     * Re-running either through the helper would cost another exec **and** would
     * describe a moment slightly after the one being diagnosed; a capture that
     * arrives half a second late can miss exactly the transient it is for.
     */
    fun capture(flingerDump: String, audioDump: String): EffectChainForensics {
        if (flingerDump.isBlank() && audioDump.isBlank()) {
            return EffectChainForensics.unavailable("no dump was available for this poll")
        }
        val snapshot = AudioFlingerEffectParser.parse(flingerDump)
        val perSession = snapshot.chains
            .map { SessionEffectCount(it.sessionId, it.effects.size) }
            .sortedWith(
                compareByDescending<SessionEffectCount> { it.effectCount }.thenBy { it.sessionId },
            )
        val playing = PlayingStreamParser.playingStreams(audioDump)
            .mapNotNull { stream -> stream.sessionId?.takeIf { it > 0 } }
            .distinct()
        return EffectChainForensics(
            effectInstances = perSession.sumOf { it.effectCount },
            sessionsWithEffects = perSession.count { it.effectCount > 0 },
            effectsPerSession = perSession,
            effectNames = snapshot.chains
                .flatMap { chain -> chain.effects.map { it.name } }
                .filter { it.isNotBlank() }
                .distinct(),
            playbackSessionIds = playing,
            note = snapshot.warnings.firstOrNull(),
        )
    }
}

/**
 * Fires once when the Bluetooth encoder has been starving for long enough that
 * it cannot be a hiccup.
 *
 * ## Why this exists
 *
 * On 2026-08-28 this app's owner measured `btif_a2dp_source` counting roughly
 * **49 encoder underflows per second, continuously**, on an LDAC 96 kHz/32 bit
 * link, with audible stutter every half second and ABR collapsed to 396 kbps.
 * Switching the system EQ off stopped it instantly (+0 underflows in 30 s), and
 * switching it back on — a *fresh* attach of the same processing chain, same
 * music, same session — was also clean. So a long-lived attached chain starves
 * the encoder and a fresh one does not, and the toggle that proved it also
 * destroyed the state that caused it. The cause is therefore **unproven**.
 *
 * That is what this class is for. It cannot fix anything and does not try. It
 * makes sure that the *next* occurrence records, at the moment it happens, the
 * one measurement that separates the leading hypothesis from the others: how
 * many effect instances were attached, and to how many sessions. If a chain has
 * accumulated duplicates, this prints it; if it has exactly one instance, this
 * rules accumulation out and the search moves on.
 *
 * ## The three gates, and why each one is there
 *
 * 1. [tripRatePerSecond] — a *rate*, not a total. The stack's underflow counter
 *    is cumulative since the Bluetooth stack started, so any absolute number is
 *    meaningless; the incident's figure was ~49/s against a healthy link's 0.
 * 2. [sustainedPasses] — consecutive polls, because a single bad window is what
 *    a track change, a seek or a Wi-Fi scan produces. This is looking for a
 *    condition that persists.
 * 3. [cooldownMs] — a capture is a parse of two large dumps and a database
 *    write. During the incident the condition held for *minutes*; without this
 *    gate that would be one capture every two seconds for the whole episode,
 *    which would bury the timeline it is supposed to inform.
 *
 * ## What resets it
 *
 * A poll with no measurable delta — the first poll of a session, or a window in
 * which a counter went backwards because the stack restarted — resets the run
 * rather than extending it. Same rule as everywhere else in this module: a
 * window that cannot be measured is not a window in which nothing happened, and
 * it must not be counted as evidence in either direction.
 */
class EncoderStarvationTripwire(
    private val tripRatePerSecond: Double = TRIP_RATE_PER_SECOND,
    private val sustainedPasses: Int = SUSTAINED_PASSES,
    private val cooldownMs: Long = CAPTURE_COOLDOWN_MS,
) {

    private var consecutive = 0
    private var lastCaptureMs: Long? = null

    /** How many consecutive over-threshold polls have been seen. Diagnostic. */
    val consecutiveOverThreshold: Int get() = consecutive

    /**
     * Feeds one poll in.
     *
     * @return the number of consecutive over-threshold polls when a forensic
     *   capture is due, or null when it is not. Null is by far the common
     *   answer: a healthy link never reaches the first gate.
     */
    fun onPass(timestampMs: Long, delta: A2dpTxDelta?): Int? {
        val rate = delta?.underflowsPerSecond
        if (rate == null || rate <= tripRatePerSecond) {
            consecutive = 0
            return null
        }
        consecutive++
        if (consecutive < sustainedPasses) return null

        // Sustained, but possibly still inside the cooldown of the previous
        // capture. The run is deliberately *not* reset here: the condition is
        // still true, and resetting would make the next capture wait for three
        // fresh polls after every cooldown expiry rather than firing on it.
        val last = lastCaptureMs
        if (last != null && timestampMs - last < cooldownMs) return null
        lastCaptureMs = timestampMs
        return consecutive
    }

    /**
     * Forgets the current run and the cooldown.
     *
     * For a caller that knows the link it was watching is gone — a disconnect, a
     * codec renegotiation — where carrying a half-finished run across into a
     * different link would attribute one link's polls to another.
     */
    fun reset() {
        consecutive = 0
        lastCaptureMs = null
    }

    companion object {
        /**
         * Underflows per second above which the encoder is considered starving.
         *
         * Ten per second is one every 100 ms — far above the isolated underflow
         * a track change or a seek produces, and far below the ~49/s the
         * incident measured. Chosen to sit in the empty space between the two
         * rather than close to either, because the only two rates ever observed
         * on this device are "essentially zero" and "about fifty".
         */
        const val TRIP_RATE_PER_SECOND = 10.0

        /**
         * Consecutive polls the rate must hold before anything is captured.
         *
         * Three, at the live view's 2 s poll interval, is about six seconds of
         * continuous starvation. Long enough that no single stall or scan can
         * reach it, short enough that the capture still describes the episode
         * rather than its aftermath.
         */
        const val SUSTAINED_PASSES = 3

        /**
         * Minimum gap between two captures.
         *
         * The incident held for minutes. One capture per episode is the useful
         * number; ten minutes means a genuinely separate later episode still
         * gets its own record while a single long one does not write ninety.
         */
        const val CAPTURE_COOLDOWN_MS = 10 * 60 * 1_000L
    }
}
