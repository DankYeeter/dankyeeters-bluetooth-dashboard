package dev.dankyeeter.btdashboard.audio.eq

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * The slice of `DynamicsProcessing` this equaliser actually uses, expressed in
 * plain values.
 *
 * WHY the indirection: `DynamicsProcessing` can only exist on a device — its
 * constructor is a binder call into audioserver, and in a JVM unit test even
 * its value objects (`EqBand`, `MbcBand`) are android.jar stubs whose setters
 * do nothing and whose getters return zero. Without a seam the *lifecycle* of
 * this class — how many native effects it creates, whether every one of them is
 * released, how many band writes one `apply` costs — could only be tested by
 * hand on hardware, which is exactly how an unreleased effect accumulated
 * unnoticed until the encoder starved (2026-08-28).
 *
 * The seam sits below the domain and above the framework: no compressor maths
 * lives here, and no framework type leaves [FrameworkDpEffect].
 */
internal interface DpEffect {
    /**
     * Reads and writes `AudioEffect.setEnabled`/`getEnabled`. Writing can be
     * silently refused — see [DynamicsProcessingEqualizer.setEnabledVerified].
     */
    var enabled: Boolean

    fun hasControl(): Boolean

    fun setPreEqBand(channelIndex: Int, bandIndex: Int, band: PreEqBandSpec)

    fun setMbcBand(channelIndex: Int, bandIndex: Int, band: MbcBandSpec)

    fun setInputGainAllChannelsTo(db: Float)

    fun setLimiterEnabled(channelIndex: Int, enabled: Boolean)

    /** Reads a pre-EQ gain back out of the framework. */
    fun preEqBandGain(channelIndex: Int, bandIndex: Int): Float

    /** Unregisters the native effect from AudioFlinger. May throw. */
    fun release()
}

/** One pre-EQ band, as this class writes it. */
internal data class PreEqBandSpec(
    val cutoffHz: Float,
    val gainDb: Float,
)

/** One multiband-compressor band, as this class writes it. */
internal data class MbcBandSpec(
    val enabled: Boolean,
    val cutoffHz: Float,
    val attackMs: Float,
    val releaseMs: Float,
    val ratio: Float,
    val thresholdDb: Float,
    val kneeWidthDb: Float,
    val noiseGateThresholdDb: Float,
    val expanderRatio: Float,
    val preGainDb: Float,
    val postGainDb: Float,
)

/** The only place a real `DynamicsProcessing` object is touched. */
private class FrameworkDpEffect(private val effect: DynamicsProcessing) : DpEffect {

    override var enabled: Boolean
        get() = effect.enabled
        set(value) {
            effect.enabled = value
        }

    override fun hasControl(): Boolean = effect.hasControl()

    override fun setPreEqBand(channelIndex: Int, bandIndex: Int, band: PreEqBandSpec) {
        val target = effect.getPreEqBandByChannelIndex(channelIndex, bandIndex)
        target.isEnabled = true
        target.cutoffFrequency = band.cutoffHz
        target.gain = band.gainDb
        effect.setPreEqBandByChannelIndex(channelIndex, bandIndex, target)
    }

    override fun setMbcBand(channelIndex: Int, bandIndex: Int, band: MbcBandSpec) {
        val target = effect.getMbcBandByChannelIndex(channelIndex, bandIndex)
        target.isEnabled = band.enabled
        target.cutoffFrequency = band.cutoffHz
        target.attackTime = band.attackMs
        target.releaseTime = band.releaseMs
        target.ratio = band.ratio
        target.threshold = band.thresholdDb
        target.kneeWidth = band.kneeWidthDb
        target.noiseGateThreshold = band.noiseGateThresholdDb
        target.expanderRatio = band.expanderRatio
        target.preGain = band.preGainDb
        target.postGain = band.postGainDb
        effect.setMbcBandByChannelIndex(channelIndex, bandIndex, target)
    }

    override fun setInputGainAllChannelsTo(db: Float) = effect.setInputGainAllChannelsTo(db)

    override fun setLimiterEnabled(channelIndex: Int, enabled: Boolean) {
        val limiter = effect.getLimiterByChannelIndex(channelIndex)
        limiter.isEnabled = enabled
        effect.setLimiterByChannelIndex(channelIndex, limiter)
    }

    override fun preEqBandGain(channelIndex: Int, bandIndex: Int): Float =
        effect.getPreEqBandByChannelIndex(channelIndex, bandIndex).gain

    override fun release() = effect.release()
}

/**
 * [SystemEqualizer] backed by Android's `DynamicsProcessing` effect.
 *
 * Layout: 2 channels x N pre-EQ bands (N from [EqBandLayout]), no multiband
 * compressor, no post-EQ, plus the built-in per-channel limiter as the final
 * stage.
 *
 * `DynamicsProcessing` fixes its band count at construction, so a layout change
 * is not a parameter update — the effect is released and rebuilt on the same
 * session. That is why [apply] tracks which layout the live effect was built
 * for and recreates it when the incoming settings disagree.
 *
 * Every call into the framework is wrapped defensively: OEM audio HALs are
 * inconsistent about `DynamicsProcessing` support and throw
 * `UnsupportedOperationException`/`IllegalStateException` rather than returning
 * errors. A dead effect flips [isAlive] to false; callers re-attach.
 *
 * ## Every death releases the native effect
 *
 * A `DynamicsProcessing` that is never released stays registered in
 * AudioFlinger on its session for the life of the process, still processing
 * audio, and nothing can ever ask for it again once the last Kotlin reference
 * is gone. This class used to produce exactly that in two ways: [guard] marked
 * the object dead on the first throwing framework call without releasing, and
 * a refused rebuild did the same — after which `close()` returned immediately
 * because `alive` was already false. Both are the direct mechanism for the
 * instance accumulation suspected behind the encoder starvation measured on
 * 2026-08-28 (~49 underflows/s from a long-lived chain, clean after a fresh
 * attach).
 *
 * So the rule is now: **the effect is released on every path that ends this
 * object's life**, exactly once, and a release that throws does not lose the
 * reference — the handle stays on [unreleased] and is retried at the next
 * [apply] and again at [close]. [createdInstanceCount] and
 * [releasedInstanceCount] make that auditable from a test instead of from a
 * dumpsys on the device.
 *
 * ## Threading
 *
 * One writer at a time. The release bookkeeping and the diff cache are plain
 * fields, as the effect reference and the liveness flag always were: every
 * caller reaches this object through `SessionAttachmentStrategy` or
 * `GlobalAttachmentStrategy`, and both hold their lock across every call. This
 * class must not grow a second entry point that skips them.
 */
class DynamicsProcessingEqualizer internal constructor(
    override val sessionId: Int,
    initialEffect: DpEffect,
    initialLayout: EqBandLayout,
    /** Builds a replacement effect for a layout change. Null = refused. */
    private val newEffect: (Int, EqBandLayout) -> DpEffect?,
) : SystemEqualizer {

    private var effect: DpEffect = initialEffect
    private var layout: EqBandLayout = initialLayout

    /**
     * Every native effect this object has owned and not yet managed to
     * release, the live one included.
     *
     * A list rather than a single reference because [rebuildFor]'s release can
     * throw: the old instance is then still registered in AudioFlinger, and
     * dropping the reference would make it permanently unreachable. Keeping it
     * here is what turns "we lost one" into "we will try again".
     */
    private val unreleased = mutableListOf(initialEffect)

    private var alive = true
    override val isAlive: Boolean get() = alive

    /** What was last written into the framework; see [writeBand]. */
    private var lastWritten = LastWritten(initialLayout.bandCount)

    init {
        instancesCreated.incrementAndGet()
    }

    override fun apply(settings: EqSettings) = guard {
        // Cheap when there is nothing outstanding, and this is the "next
        // opportunity" a failed release is waiting for.
        retryOutstandingReleases()
        val clean = settings.sanitized()
        if (clean.layout != layout) rebuildFor(clean.layout)
        // rebuildFor releases the old effect before creating the new one, so a
        // refused rebuild leaves `effect` pointing at a released object. Without
        // this bail the loop below would keep writing bands into it until the
        // framework threw and guard() caught the unwind — an exception used as
        // control flow, on the audio path, for a condition already known here.
        if (!alive) return@guard
        Ear.entries.forEach { ear ->
            // The split between the two stages is the model's, not this
            // class's: in loudness-restoration mode a boost belongs to the
            // compressor, where it fades out as the level rises, while the
            // static pre-EQ keeps the cuts and the volume-aware tilt. Off,
            // everything is static and the compressor bands sit at neutral.
            // See EqSettings.staticGainsFor / compressorGainsFor.
            val static = clean.staticGainsFor(ear)
            val compressed = clean.compressorGainsFor(ear)
            for (band in 0 until layout.bandCount) {
                writeBand(ear, band, static[band])
                writeMbcBand(ear, band, compressed[band])
            }
        }
        if (changed(lastWritten.preGainDb, clean.preGainDb)) {
            effect.setInputGainAllChannelsTo(clean.preGainDb)
            lastWritten.preGainDb = clean.preGainDb
        }
        if (lastWritten.limiterEnabled != clean.limiterEnabled) {
            Ear.entries.forEach { ear -> effect.setLimiterEnabled(ear.channelIndex, clean.limiterEnabled) }
            lastWritten.limiterEnabled = clean.limiterEnabled
        }
        // Deliberately *not* diffed. Every other write here can be skipped
        // because the framework keeps what it was given, but the enable is the
        // one value the framework is known to refuse behind our back (see
        // setEnabledVerified), and the read-back inside it is the only detector
        // this app has for an attached-but-inert effect. Two binder calls per
        // apply is what that costs.
        setEnabledVerified(clean.enabled)
    }

    /**
     * Switches the effect on and checks that it actually went on.
     *
     * `effect.enabled = x` looks like a property assignment, but underneath it
     * is `AudioEffect.setEnabled`, which **returns a status code instead of
     * throwing**. Kotlin discards that value, so a refusal is completely
     * silent: the effect stays attached, the app reports success, and nothing
     * is equalised.
     *
     * That is not hypothetical. Attaching to a freshly harvested foreign
     * session on the device produced exactly this - `Registered=y, Enabled=n` in
     * AudioFlinger - and the EQ only came alive when a later playback event
     * happened to re-attach it. The framework needs a moment after an effect is
     * created on someone else's session before it will accept the enable.
     *
     * So: assign, read back, and keep trying for a bounded budget if the
     * framework disagreed. The read-back is the point - it is the only way to
     * tell "on" from "asked to be on".
     *
     * ## Why it polls instead of sleeping once
     *
     * It used to be a single `Thread.sleep(120)`. That was written when this
     * ran on its own; it now runs inside `SessionAttachmentStrategy`'s lock,
     * which serialises every attach, re-attach, prune and volume-driven update
     * in the app — so a retry stretched that critical section by the full
     * 120 ms whether or not the framework needed 120 ms. Polling every
     * [ENABLE_VERIFY_POLL_MS] leaves the same worst case and the same
     * detection, and returns as soon as the framework has caught up, which on
     * the device was one poll. The verification is *not* moved out of the lock:
     * the value it verifies can be changed by the very callers the lock
     * serialises, so a read-back taken outside it would be a read-back of
     * somebody else's write.
     */
    private fun setEnabledVerified(target: Boolean) {
        effect.enabled = target
        // hasControl matters more than the return value here: another effect
        // holding control makes every setEnabled a no-op that still reports
        // success locally, which is exactly the "attached but Enabled=n" state
        // AudioFlinger showed on a harvested session.
        Log.i(TAG, "session $sessionId enable=$target control=${effect.hasControl()} readback=${effect.enabled}")
        if (effect.enabled == target) return

        var waitedMs = 0L
        while (waitedMs < ENABLE_VERIFY_BUDGET_MS) {
            Thread.sleep(ENABLE_VERIFY_POLL_MS)
            waitedMs += ENABLE_VERIFY_POLL_MS
            effect.enabled = target
            if (effect.enabled == target) {
                Log.i(TAG, "session $sessionId accepted enabled=$target after $waitedMs ms")
                return
            }
        }
        Log.w(
            TAG,
            "session $sessionId refused enabled=$target for $ENABLE_VERIFY_BUDGET_MS ms; " +
                "the EQ is attached but inert",
        )
    }

    override val activeLayout: EqBandLayout get() = layout

    override fun setBandGain(ear: Ear, bandIndex: Int, gainDb: Float) = guard {
        require(bandIndex in 0 until layout.bandCount) { "band index out of range: $bandIndex" }
        writeBand(ear, bandIndex, gainDb.coerceIn(EqBands.MIN_GAIN_DB, EqBands.MAX_GAIN_DB))
    }

    override fun setPreGain(db: Float) = guard {
        val clamped = db.coerceIn(-24f, 0f)
        effect.setInputGainAllChannelsTo(clamped)
        lastWritten.preGainDb = clamped
    }

    override fun setEnabled(enabled: Boolean) = guard {
        effect.enabled = enabled
    }

    /**
     * Swaps in an effect with the new band count. If the framework refuses the
     * new configuration the old effect is already gone, so the equaliser marks
     * itself dead and the caller re-attaches — the same path a HAL failure takes.
     *
     * The old handle is released *before* the replacement is built, and if that
     * release throws the handle stays on [unreleased] rather than being
     * dropped: a throwing `release()` used to be swallowed by a `runCatching`
     * and the replacement created regardless, which left two live instances on
     * one session with only one of them reachable.
     */
    private fun rebuildFor(target: EqBandLayout) {
        val old = effect
        if (releaseHandle(old, "layout change to ${target.id}")) {
            unreleased.remove(old)
        }
        val rebuilt = newEffect(sessionId, target)
        if (rebuilt == null) {
            Log.w(TAG, "Could not rebuild DynamicsProcessing for ${target.id}")
            // Dead *and* released: the old handle is gone (or on the retry
            // list) and there is no new one. Before this, a failed rebuild set
            // alive=false and returned, and close() then bailed on `!alive`
            // without ever releasing anything.
            die("a rebuild for ${target.id} was refused")
            return
        }
        effect = rebuilt
        unreleased += rebuilt
        instancesCreated.incrementAndGet()
        layout = target
        // Nothing written into the new effect yet, and its band count may
        // differ: the diff cache from the old one would suppress writes that
        // have never happened.
        lastWritten = LastWritten(target.bandCount)
    }

    override fun close() {
        // No `if (!alive) return` guard. `alive` goes false without a release
        // having happened (guard(), a refused rebuild), so bailing on it was
        // how a dying effect stayed registered in AudioFlinger forever.
        // Idempotence comes from `unreleased` emptying instead.
        alive = false
        releaseEverything("close")
    }

    /**
     * A band write, skipped when the framework already holds this value.
     *
     * WHY the skip: one `apply` writes 4 x bandCount band parameters plus the
     * limiter and the enable — 124 binder round-trips into audioserver on the
     * 31-band layout — and the foreground service calls `update` on every
     * distinct volume-derived curve. A volume ramp therefore hammered
     * audioserver with writes that were, band for band, identical to what was
     * already in the effect: the ISO 226 tilt moves a handful of low and high
     * bands and leaves the midrange exactly where it was.
     */
    private fun writeBand(ear: Ear, bandIndex: Int, gainDb: Float) {
        if (!changed(lastWritten.static[ear.channelIndex][bandIndex], gainDb)) return
        effect.setPreEqBand(
            ear.channelIndex,
            bandIndex,
            PreEqBandSpec(
                // The band's upper *edge*, not its centre: that is what
                // cutoffFrequency means, and the difference was measurable in
                // the air. Writing centres here shifted every band half an
                // octave down, so the slider labelled 1000 Hz cut 600-1000 Hz
                // and left 1300 Hz alone.
                //
                // Still the *active* layout's list, not the default one's:
                // reading the default indexed out of bounds from band 10 upward
                // on the 20- and 31-band layouts; guard() swallowed the
                // exception and marked the effect dead, so those layouts moved
                // sliders and changed nothing.
                cutoffHz = layout.upperEdgesHz[bandIndex],
                gainDb = gainDb,
            ),
        )
        lastWritten.static[ear.channelIndex][bandIndex] = gainDb
    }

    /**
     * One compressor band: [boostDb] of post-gain for quiet signal, taken back
     * above the threshold at the ratio that lands on net zero at full scale.
     * A boost of zero writes an explicitly neutral band rather than skipping
     * the write — a band that once carried a boost must not keep it after the
     * mode is switched off.
     *
     * "Skipping the write" above means skipping it *as a value*: the diff below
     * skips only a write of a boost the effect already has, which is the same
     * band content either way. Every other parameter of this band is a constant
     * or derived from [boostDb] and the layout, and a layout change resets the
     * cache — so the boost is a complete key for what would be written.
     */
    private fun writeMbcBand(ear: Ear, bandIndex: Int, boostDb: Float) {
        if (!changed(lastWritten.compressor[ear.channelIndex][bandIndex], boostDb)) return
        effect.setMbcBand(
            ear.channelIndex,
            bandIndex,
            MbcBandSpec(
                enabled = boostDb > 0f,
                cutoffHz = layout.upperEdgesHz[bandIndex],
                attackMs = LoudnessRestorationMath.ATTACK_MS,
                releaseMs = LoudnessRestorationMath.RELEASE_MS,
                ratio = LoudnessRestorationMath.ratioFor(boostDb),
                thresholdDb = LoudnessRestorationMath.THRESHOLD_DB,
                kneeWidthDb = LoudnessRestorationMath.KNEE_WIDTH_DB,
                // No downward expansion: this stage lifts quiet detail, gating
                // it away again would be the opposite feature.
                noiseGateThresholdDb = NOISE_GATE_OFF_DB,
                expanderRatio = 1f,
                preGainDb = 0f,
                postGainDb = boostDb,
            ),
        )
        lastWritten.compressor[ear.channelIndex][bandIndex] = boostDb
    }

    /** Reads a gain back out of the framework — proof the value reached the effect. */
    fun readBandGain(ear: Ear, bandIndex: Int): Float? = runCatching {
        effect.preEqBandGain(ear.channelIndex, bandIndex)
    }.getOrNull()

    private inline fun guard(block: () -> Unit) {
        if (!alive) return
        try {
            block()
        } catch (t: RuntimeException) {
            Log.w(TAG, "DynamicsProcessing call failed on session $sessionId; marking dead", t)
            // Released, not merely marked. This is the path a HAL quirk takes,
            // and it used to leave the native effect registered on the session
            // with no reference left to release it.
            die("a framework call threw")
        }
    }

    /** Ends this object's life: never alive again, and never still registered. */
    private fun die(reason: String) {
        alive = false
        releaseEverything(reason)
    }

    /** Releases everything still outstanding, keeping what it could not release. */
    private fun releaseEverything(reason: String) {
        if (unreleased.isEmpty()) return
        val iterator = unreleased.iterator()
        while (iterator.hasNext()) {
            if (releaseHandle(iterator.next(), reason)) iterator.remove()
        }
        if (unreleased.isNotEmpty()) {
            Log.e(
                TAG,
                "session $sessionId: ${unreleased.size} DynamicsProcessing instance(s) could not be " +
                    "released and stay registered in AudioFlinger",
            )
        }
    }

    /**
     * Retries the releases that threw earlier, on everything except the live
     * effect. Called from [apply], which is the next thing that happens to a
     * still-usable equaliser after a rebuild.
     */
    private fun retryOutstandingReleases() {
        if (unreleased.size <= 1) return
        val iterator = unreleased.iterator()
        while (iterator.hasNext()) {
            val handle = iterator.next()
            if (handle === effect) continue
            if (releaseHandle(handle, "retry of a release that threw earlier")) iterator.remove()
        }
    }

    /**
     * @return true when [handle] is now unregistered. False means the
     *   framework refused and the caller must keep the reference.
     */
    private fun releaseHandle(handle: DpEffect, reason: String): Boolean {
        // Best-effort, and separate: a throwing setEnabled must not stop the
        // release, which is the call that actually unregisters the effect.
        runCatching { handle.enabled = false }
        return try {
            handle.release()
            instancesReleased.incrementAndGet()
            true
        } catch (t: RuntimeException) {
            Log.e(
                TAG,
                "release() threw on session $sessionId ($reason); the native effect is STILL " +
                    "registered in AudioFlinger and will be retried",
                t,
            )
            false
        }
    }

    /** Values already in the framework, so an identical write can be skipped. */
    private class LastWritten(bandCount: Int) {
        // NaN because no comparison against it is ever equal: the first write
        // of every band always happens, whatever value it carries.
        val static = Array(CHANNEL_COUNT) { FloatArray(bandCount) { Float.NaN } }
        val compressor = Array(CHANNEL_COUNT) { FloatArray(bandCount) { Float.NaN } }
        var preGainDb: Float = Float.NaN
        var limiterEnabled: Boolean? = null
    }

    /**
     * Whether [next] has to be written given that [last] is already in the
     * effect.
     *
     * The NaN test is not decoration: "never written" is stored as NaN, and
     * every comparison against NaN is false — including `>=`. Written as a bare
     * `abs(last - next) >= EPSILON` this would answer "unchanged" for the very
     * first write of every band and the effect would keep its construction-time
     * zeros forever.
     */
    private fun changed(last: Float, next: Float): Boolean =
        last.isNaN() || abs(last - next) >= WRITE_EPSILON_DB

    companion object {
        private const val TAG = "DpEqualizer"
        private const val EFFECT_PRIORITY = 0

        /** Stereo, everywhere: the pre-EQ is written per channel. */
        private const val CHANNEL_COUNT = 2

        /**
         * How long the enable verification keeps trying, and how long it waits
         * between attempts. See [setEnabledVerified] for why it is a poll.
         *
         * The budget is the old single sleep, unchanged, because it is the
         * number that was measured to be long enough for the framework to
         * finish wiring a new effect onto a foreign session. The poll interval
         * is short enough that the common recovery costs one interval instead
         * of the whole budget inside the attachment lock.
         */
        private const val ENABLE_VERIFY_BUDGET_MS = 120L
        private const val ENABLE_VERIFY_POLL_MS = 10L

        /**
         * The smallest gain difference worth a binder call, in dB.
         *
         * Two orders of magnitude below the ~0.5 dB that is audible on a single
         * band, and well below the 0.25 dB the volume tilt quantises to, so
         * nothing this suppresses could ever have been heard. It is not zero
         * because the values arrive from log-frequency resampling and ISO 226
         * interpolation, where recomputing the same curve can differ in the
         * last bits of a float — an exact comparison would let those through
         * and write all 124 parameters for a change of 1e-7 dB.
         */
        private const val WRITE_EPSILON_DB = 0.01f

        /**
         * Far enough below any real signal that the gate can never trigger.
         * The framework wants a value; "off" is not one of its options.
         */
        private const val NOISE_GATE_OFF_DB = -120f

        /** Attack/release/ratio/threshold/post-gain for the output limiter. */
        private const val LIMITER_ATTACK_MS = 1f
        private const val LIMITER_RELEASE_MS = 60f
        private const val LIMITER_RATIO = 10f
        private const val LIMITER_THRESHOLD_DB = -1f
        private const val LIMITER_POST_GAIN_DB = 0f

        private val instancesCreated = AtomicInteger()
        private val instancesReleased = AtomicInteger()

        /**
         * Native effects this class has created since the process started, and
         * how many of them it has managed to release.
         *
         * Monotone counters rather than a live gauge, because the question they
         * answer is historical: *did every effect we ever built get released?*
         * A difference that grows over a session is the instance accumulation
         * that starves the Bluetooth encoder — the same thing :core-monitor
         * counts from the AudioFlinger dump, but visible from a unit test and
         * without a device.
         */
        val createdInstanceCount: Int get() = instancesCreated.get()
        val releasedInstanceCount: Int get() = instancesReleased.get()

        /** Created minus released: what should still be registered right now. */
        val liveInstanceCount: Int get() = createdInstanceCount - releasedInstanceCount

        /**
         * Attaches a fresh effect to [sessionId]. Returns null on any failure —
         * missing permission for a foreign/global session, unsupported device,
         * or a session that disappeared between discovery and attach.
         */
        fun create(
            sessionId: Int,
            layout: EqBandLayout = EqBandLayout.DEFAULT,
            priority: Int = EFFECT_PRIORITY,
        ): DynamicsProcessingEqualizer? {
            val effect = createEffect(sessionId, layout, priority) ?: return null
            return DynamicsProcessingEqualizer(sessionId, effect, layout) { session, target ->
                createEffect(session, target, priority)
            }
        }

        /** Raw effect for [create] and [rebuildFor]; the only construction site. */
        private fun createEffect(
            sessionId: Int,
            layout: EqBandLayout,
            priority: Int = EFFECT_PRIORITY,
        ): DpEffect? = try {
            FrameworkDpEffect(DynamicsProcessing(priority, sessionId, buildConfig(layout)))
        } catch (t: RuntimeException) {
            Log.w(TAG, "Could not attach DynamicsProcessing to session $sessionId", t)
            null
        }

        private fun buildConfig(layout: EqBandLayout): DynamicsProcessing.Config {
            val edges = layout.upperEdgesHz
            val eq = DynamicsProcessing.Eq(true, true, layout.bandCount).apply {
                for (i in 0 until layout.bandCount) {
                    setBand(
                        i,
                        DynamicsProcessing.EqBand(
                            /* enabled = */ true,
                            // Upper edge, not centre - see writeBand.
                            /* cutoffFrequency = */ edges[i],
                            /* gain = */ 0f,
                        ),
                    )
                }
            }
            // The compressor stage exists from the start, at neutral, because
            // the band count is fixed at construction: enabling loudness
            // restoration later must be a parameter write, not a rebuild that
            // audibly drops the effect mid-song. A disabled band with unity
            // ratio and zero gains is bit-transparent.
            val mbc = DynamicsProcessing.Mbc(true, true, layout.bandCount).apply {
                for (i in 0 until layout.bandCount) {
                    setBand(
                        i,
                        DynamicsProcessing.MbcBand(
                            /* enabled = */ false,
                            /* cutoffFrequency = */ edges[i],
                            /* attackTime = */ LoudnessRestorationMath.ATTACK_MS,
                            /* releaseTime = */ LoudnessRestorationMath.RELEASE_MS,
                            /* ratio = */ 1f,
                            /* threshold = */ LoudnessRestorationMath.THRESHOLD_DB,
                            /* kneeWidth = */ LoudnessRestorationMath.KNEE_WIDTH_DB,
                            /* noiseGateThreshold = */ NOISE_GATE_OFF_DB,
                            /* expanderRatio = */ 1f,
                            /* preGain = */ 0f,
                            /* postGain = */ 0f,
                        ),
                    )
                }
            }
            val limiter = DynamicsProcessing.Limiter(
                /* inUse = */ true,
                /* enabled = */ true,
                /* linkGroup = */ 0,
                LIMITER_ATTACK_MS,
                LIMITER_RELEASE_MS,
                LIMITER_RATIO,
                LIMITER_THRESHOLD_DB,
                LIMITER_POST_GAIN_DB,
            )
            // WHY the expensive variant and the 10 ms frame stay, considered and
            // rejected as a cause on 2026-08-28: VARIANT_FAVOR_FREQUENCY_RESOLUTION
            // with a 10 ms preferred frame is the highest-CPU configuration
            // DynamicsProcessing offers, so it is the obvious suspect for an
            // encoder that starves while the EQ is attached. It cannot be the
            // cause on its own: switching the EQ off and straight back on — a
            // *fresh* effect with this identical config, same music, same
            // session — was clean, and the starvation only ever appeared on a
            // long-lived chain. A configuration constant cannot explain a
            // failure that depends on how long the chain has been alive; effect
            // accumulation can, which is what the release accounting above is
            // for. Changing this would cost real frequency resolution (the
            // whole point of a 31-band correction) and would have hidden the
            // actual mechanism behind a partial improvement.
            return DynamicsProcessing.Config.Builder(
                /* variant = */ DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                /* channelCount = */ CHANNEL_COUNT,
                /* preEqInUse = */ true,
                /* preEqBandCount = */ layout.bandCount,
                /* mbcInUse = */ true,
                /* mbcBandCount = */ layout.bandCount,
                /* postEqInUse = */ false,
                /* postEqBandCount = */ 0,
                /* limiterInUse = */ true,
            )
                .setPreferredFrameDuration(10f)
                .setPreEqAllChannelsTo(eq)
                .setMbcAllChannelsTo(mbc)
                .setLimiterAllChannelsTo(limiter)
                .build()
        }
    }
}

/** Default factory; :core-system decides which session id to pass in. */
class DynamicsProcessingEqualizerFactory : SystemEqualizerFactory {
    override fun create(sessionId: Int): SystemEqualizer? =
        DynamicsProcessingEqualizer.create(sessionId)
}
