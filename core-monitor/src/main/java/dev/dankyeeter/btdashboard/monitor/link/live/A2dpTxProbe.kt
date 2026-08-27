package dev.dankyeeter.btdashboard.monitor.link.live

import dev.dankyeeter.btdashboard.monitor.shell.ShellRunner
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * One pass of the tx-queue probe: the counters, and why they are missing.
 *
 * Deliberately not a [LinkLiveSnapshot]. This channel reads one dump instead of
 * three, so it genuinely knows less, and a type that could be mistaken for the
 * full reading would invite exactly the mistake of asking it about app
 * underruns — which it cannot see. See [A2dpTxProbe] for the trade.
 */
data class TxProbeReading(
    val timestampMs: Long,
    /** MEASURED: `btif_a2dp_source`'s counters, or null when they do not apply. */
    val stats: A2dpTxStats? = null,
    val observability: LinkObservability = LinkObservability.UNKNOWN,
    /** Why [stats] is null. Shown, never swallowed. */
    val unavailable: String? = null,
)

/**
 * The change between two probe passes — the only part that says anything about
 * *now*, since every counter in the dump is cumulative.
 *
 * DERIVED throughout, and [delta] is null on the first pass of a run and after
 * any counter reset, which is the honest answer rather than a zero.
 */
data class TxProbeSample(
    val timestampMs: Long,
    val delta: A2dpTxDelta? = null,
    val observability: LinkObservability = LinkObservability.UNKNOWN,
    val unavailable: String? = null,
)

/**
 * A deliberately narrow reader of `btif_a2dp_source`'s tx counters, for the
 * close-up graph that samples twice a second.
 *
 * ## Why this exists next to [LiveLinkSource] rather than inside it
 *
 * [LiveLinkSource] reads three dumps per pass — `bluetooth_manager`,
 * `media.audio_flinger` and `audio` — measured at 233 ms, 155 ms and 162 ms on
 * the Pixel 11 Pro this was built against. That is about **550 ms of work per
 * pass**, so a two-per-second close-up is not merely expensive through it: at
 * 500 ms spacing the loop would never finish a pass before the next one was
 * due, and the graph's x-axis would be a fiction.
 *
 * This probe runs **only `bluetooth_manager`**: one exec, 233 ms measured, so a
 * 500 ms cadence spends a little under half the interval reading and the sample
 * spacing survives. That is still a heavy duty cycle, which is why the UI runs
 * this channel only while the close-up is switched on and the panel is in
 * front of the user — never as a background poller.
 *
 * ## What it gives up, and where that is said
 *
 * The two dumps it skips are the ones that carry the *input* side: per-app
 * underruns and the mixer thread's own underruns. So the loss this channel can
 * see is the Bluetooth stack's alone — dropped packets, stack dropouts and
 * encoder underflows. The panel's 60-second graph rides the full
 * [LiveLinkSource] pass and does see all three, and the close-up says so in its
 * own explainer rather than letting a quiet graph read as "no loss".
 *
 * ## Offloaded codecs
 *
 * The counters belong to the host encoder. When the controller encodes,
 * `btif_a2dp_source` is bypassed and its counters sit wherever the last
 * host-encoded session left them — a frozen, perfectly healthy-looking link.
 * [TxProbeReading.observability] carries that, and the probe reports no stats at
 * all rather than a flat line somebody would read as silence.
 */
class A2dpTxProbe(
    private val shell: ShellRunner,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    val isAvailable: Boolean get() = shell.isAvailable

    /** One pass. Pure input → output apart from the shell call itself. */
    suspend fun readOnce(): TxProbeReading {
        val now = clock()
        if (!shell.isAvailable) {
            return TxProbeReading(
                timestampMs = now,
                unavailable = "no shell identity — the helper is not running",
            )
        }

        val result = shell.run(COMMAND)
        // A non-zero exit with partial output still parses: dumpsys routinely
        // times out on one section while the rest is intact.
        if (result.stdout.isBlank()) {
            return TxProbeReading(
                timestampMs = now,
                unavailable = "dumpsys bluetooth_manager returned nothing: " +
                    result.stderr.ifBlank { "exit ${result.exitCode}" },
            )
        }

        val dump = A2dpLinkDumpParser.parse(result.stdout)
        val observability = when {
            dump.codec == null -> LinkObservability.UNKNOWN
            dump.codec.isOffloaded -> LinkObservability.OFFLOADED
            else -> LinkObservability.HOST_ENCODED
        }
        return TxProbeReading(
            timestampMs = now,
            // Only host-encoded links own these counters; see the class KDoc.
            stats = dump.tx?.takeIf { observability == LinkObservability.HOST_ENCODED },
            observability = observability,
            unavailable = when {
                observability == LinkObservability.OFFLOADED ->
                    "${dump.codec?.family?.displayName} is encoded by the controller, so the " +
                        "stack's tx counters do not describe this link"

                observability == LinkObservability.UNKNOWN ->
                    "no negotiated codec in the dump"

                dump.tx == null -> "no 'A2DP State:' section in the dump"
                else -> null
            },
        )
    }

    /**
     * The difference between two passes.
     *
     * Public and pure so the arithmetic that the graph is drawn from can be
     * tested without a phone — the part that is easy to get quietly wrong is a
     * counter that went backwards, not the shell call.
     */
    fun sampleBetween(previous: TxProbeReading?, current: TxProbeReading): TxProbeSample {
        val before = previous?.stats
        val now = current.stats
        val windowMs = current.timestampMs - (previous?.timestampMs ?: current.timestampMs)
        val enqueued = if (before == null || now == null || windowMs <= 0) {
            null
        } else {
            increase(before.enqueueCount, now.enqueueCount)
        }
        return TxProbeSample(
            timestampMs = current.timestampMs,
            delta = enqueued?.let {
                A2dpTxDelta(
                    windowMs = windowMs,
                    enqueued = it,
                    dropped = increase(before!!.droppedCount, now!!.droppedCount) ?: 0,
                    dropouts = increase(before.dropoutCount, now.dropoutCount) ?: 0,
                    flushed = increase(before.flushedCount, now.flushedCount) ?: 0,
                    underflows = increase(before.underflowCount, now.underflowCount) ?: 0,
                    underflowBytes = increase(before.underflowBytes, now.underflowBytes) ?: 0,
                    framesEncoded = increase(
                        before.framesPerPacketTotal,
                        now.framesPerPacketTotal,
                    ) ?: 0,
                )
            },
            observability = current.observability,
            unavailable = current.unavailable,
        )
    }

    /**
     * A cold flow of differences. Polling starts on collection and stops with it.
     *
     * The interval is measured from the *start* of each pass, so a slow dump
     * eats into the wait rather than adding to it: every value on the graph is
     * divided by the spacing, so uneven spacing would bend the line rather than
     * shorten it.
     */
    fun samples(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<TxProbeSample> = flow {
        var previous: TxProbeReading? = null
        while (currentCoroutineContext().isActive) {
            val startedAt = clock()
            val reading = readOnce()
            emit(sampleBetween(previous, reading))
            // A pass that could not read the counters is not a baseline for the
            // next difference: the one after it would otherwise be measured
            // across two intervals and drawn as if it were one.
            previous = reading.takeIf { it.stats != null }
            val spent = clock() - startedAt
            delay((intervalMs - spent).coerceAtLeast(MIN_INTERVAL_MS))
        }
    }

    /**
     * The rise in a cumulative counter, or null when it cannot be compared.
     *
     * A *fall* is null rather than zero, for the reason [LiveLinkSource] gives
     * for its own copy of this rule: counters only go down when the thing
     * counting them restarted, and the honest answer there is "this window
     * cannot be measured", not "nothing happened in it". Written out again here
     * rather than shared, because the two callers must be free to disagree
     * about what they read — this one deliberately reads less.
     */
    private fun increase(before: Long?, now: Long?): Long? {
        if (before == null || now == null) return null
        return (now - before).takeIf { it >= 0 }
    }

    companion object {
        private val COMMAND = listOf("dumpsys", "bluetooth_manager")

        /**
         * Two passes a second: fast enough that a dropout lands in a bar the
         * user can still connect to what they heard, and the fastest this can
         * be run without the 233 ms pass eating the interval it is measured in.
         */
        const val DEFAULT_INTERVAL_MS = 500L

        /** A floor, so a slow device cannot turn the loop into a busy spin. */
        const val MIN_INTERVAL_MS = 200L

        /** Measured cost of the one dump this probe runs, on the Pixel 11 Pro. */
        const val MEASURED_PASS_MS = 233L
    }
}
