package dev.dankyeeter.btdashboard.monitor.diagnostic

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.codec.CodecReadResult
import dev.dankyeeter.btdashboard.monitor.codec.CodecStatusSource
import dev.dankyeeter.btdashboard.monitor.codec.CodecController
import dev.dankyeeter.btdashboard.monitor.codec.NoOpCodecController
import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample
import dev.dankyeeter.btdashboard.monitor.sampling.LinkSampleCollector

/** The steps of the guided "test device" routine, in order. */
enum class DiagnosticStep(val title: String) {
    CONNECTION_CHECK("Connection check"),
    CODEC_NEGOTIATION("Codec negotiation"),
    CODEC_CYCLING("Codec cycling"),
    SOAK("Link stability soak"),
    SUMMARY("Summary"),
}

sealed interface StepOutcome {
    val detail: String

    data class Passed(override val detail: String) : StepOutcome
    data class Warned(override val detail: String) : StepOutcome
    data class Failed(override val detail: String) : StepOutcome

    /** The step needs hardware/privileges we could not use — honestly skipped. */
    data class Skipped(override val detail: String) : StepOutcome
}

data class DiagnosticStepResult(val step: DiagnosticStep, val outcome: StepOutcome)

data class DiagnosticReport(
    val deviceAddress: String,
    val deviceName: String?,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val steps: List<DiagnosticStepResult> = emptyList(),
    val bestStableCodec: CodecFamily? = null,
    val codecChanges: Int = 0,
    val dropCount: Int = 0,
    val rssiRangeDbm: IntRange? = null,
    val sampleCount: Int = 0,
) {
    val durationMs: Long get() = finishedAtMs - startedAtMs

    /** One-paragraph verdict for the report card. */
    val verdict: String
        get() = buildString {
            if (steps.any { it.outcome is StepOutcome.Failed }) {
                append("The device failed at least one check. ")
            }
            append(
                bestStableCodec?.let { "Most stable codec: ${it.displayName}. " }
                    ?: "No codec could be confirmed. ",
            )
            append("$dropCount drop(s) over ${durationMs / 1000}s")
            rssiRangeDbm?.let { append(", signal ${it.first} to ${it.last} dBm") }
            append(".")
        }
}

/**
 * Runs the guided diagnostic. Everything it touches is an interface, so the
 * whole flow — including the soak and the report maths — is unit-tested with
 * fakes and a virtual clock; nothing here needs a phone.
 *
 * Steps that genuinely require hardware or privileges we do not have (forcing a
 * codec) come back as [StepOutcome.Skipped] with the reason, never as a fake
 * pass.
 */
class DeviceDiagnosticRunner(
    private val codecSource: CodecStatusSource,
    private val collector: LinkSampleCollector,
    private val codecController: CodecController,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {

    /**
     * @param soakDurationMs total soak length; [soakIntervalMs] is the deep
     *   capture resolution used during it (10 s per PLAN.md).
     */
    suspend fun run(
        address: String,
        soakDurationMs: Long = 3 * 60 * 1000L,
        soakIntervalMs: Long = 10_000L,
        onProgress: (DiagnosticStepResult) -> Unit = {},
    ): DiagnosticReport {
        val startedAt = clock()
        val steps = mutableListOf<DiagnosticStepResult>()
        fun record(step: DiagnosticStep, outcome: StepOutcome) {
            val result = DiagnosticStepResult(step, outcome)
            steps += result
            onProgress(result)
        }

        val device = codecSource.connectedDevices().firstOrNull { it.address == address }
        if (device == null) {
            record(
                DiagnosticStep.CONNECTION_CHECK,
                StepOutcome.Failed("Device is not connected"),
            )
            return DiagnosticReport(address, null, startedAt, clock(), steps)
        }
        record(
            DiagnosticStep.CONNECTION_CHECK,
            StepOutcome.Passed(
                "Connected as ${device.name}" +
                    if (device.isActive) " and active for media" else " (not the active device)",
            ),
        )

        val negotiated = when (val result = codecSource.codecStatus(address)) {
            is CodecReadResult.Available -> {
                record(
                    DiagnosticStep.CODEC_NEGOTIATION,
                    StepOutcome.Passed("Negotiated ${result.status.badge}"),
                )
                result.status.family
            }

            is CodecReadResult.Unsupported -> {
                record(
                    DiagnosticStep.CODEC_NEGOTIATION,
                    StepOutcome.Skipped("Codec status unreadable: ${result.reason}"),
                )
                null
            }

            is CodecReadResult.Failed -> {
                record(
                    DiagnosticStep.CODEC_NEGOTIATION,
                    StepOutcome.Warned("Codec status failed: ${result.reason}"),
                )
                null
            }
        }

        val cyclingResults = cycleCodecs(address, restoreTo = negotiated)
        record(DiagnosticStep.CODEC_CYCLING, cyclingResults.first)

        val samples = soak(address, soakDurationMs, soakIntervalMs)
        val analysis = DiagnosticAnalysis.analyse(samples)
        record(
            DiagnosticStep.SOAK,
            when {
                samples.isEmpty() -> StepOutcome.Warned("No samples captured during the soak")
                analysis.dropCount > 0 ->
                    StepOutcome.Warned("${analysis.dropCount} drop(s) in ${samples.size} samples")
                else -> StepOutcome.Passed("${samples.size} samples, no drops")
            },
        )

        val report = DiagnosticReport(
            deviceAddress = address,
            deviceName = device.name,
            startedAtMs = startedAt,
            finishedAtMs = clock(),
            steps = steps,
            bestStableCodec = analysis.bestStableCodec ?: cyclingResults.second ?: negotiated,
            codecChanges = analysis.codecChanges,
            dropCount = analysis.dropCount,
            rssiRangeDbm = analysis.rssiRange,
            sampleCount = samples.size,
        )
        record(DiagnosticStep.SUMMARY, StepOutcome.Passed(report.verdict))
        return report
    }

    /**
     * Tries every offered codec, then puts the link back the way it was found.
     *
     * [restoreTo] is not a nicety. `setCodecConfigPreference` pins the codec it
     * sets at `CODEC_PRIORITY_HIGHEST`, so without a restore the diagnostic
     * silently leaves the headphone on whichever codec happened to be last in
     * the list — observed on a Focal Bathys: a run ended with the device pinned
     * to AAC at priority 1000000, and every later connection kept choosing AAC
     * over the aptX HD it would otherwise negotiate. A read-only check that
     * degrades the thing it measured is worse than no check.
     *
     * The restore is best-effort and deliberately not part of the verdict: if
     * it fails the cycling result is still true, and the detail says the link
     * was left elsewhere so nobody has to guess why their headphones sound
     * different afterwards.
     *
     * @return the step outcome plus the best codec that actually held, if any.
     */
    private suspend fun cycleCodecs(
        address: String,
        restoreTo: CodecFamily?,
    ): Pair<StepOutcome, CodecFamily?> {
        val available = codecController.availableCodecs(address)
        if (available.isEmpty()) {
            // Two very different situations produce an empty list, and saying
            // "no privileged access" for both sent a real debugging session
            // down the wrong path for hours: the helper was running the whole
            // time and the list was empty for its own reasons.
            val reason = if (codecController === NoOpCodecController) {
                "the privileged helper is not running, so no codec can be set or checked"
            } else {
                "the helper is running but reported no selectable codecs for this device"
            }
            return StepOutcome.Skipped("Codec switching skipped — $reason") to null
        }
        val accepted = mutableListOf<CodecFamily>()
        for (codec in available) {
            val applied = codecController.selectCodec(address, codec)
            if (applied == codec) accepted += codec
        }

        // Put it back before reporting. Only when something was actually
        // changed, and only to a codec the device offered — restoring to a
        // family that is not in `available` would just pin the wrong one.
        val restored = when {
            accepted.isEmpty() -> null
            restoreTo == null -> null
            accepted.last() == restoreTo -> restoreTo
            restoreTo !in available -> null
            else -> codecController.selectCodec(address, restoreTo)
        }
        val restoreNote = when {
            accepted.isEmpty() -> ""
            restoreTo == null -> " — the codec before the run was unknown, so the link was left on ${accepted.last().displayName}"
            restored == restoreTo -> " — restored to ${restoreTo.displayName}"
            else -> " — could not restore ${restoreTo.displayName}; the link is on ${accepted.last().displayName}"
        }

        return if (accepted.isEmpty()) {
            StepOutcome.Warned("None of the ${available.size} offered codecs could be applied") to null
        } else {
            StepOutcome.Passed(
                "Applied: ${accepted.joinToString { it.displayName }}$restoreNote",
            ) to accepted.maxByOrNull { CODEC_PREFERENCE.indexOf(it) }
        }
    }

    private suspend fun soak(
        address: String,
        durationMs: Long,
        intervalMs: Long,
    ): List<LinkQualitySample> {
        val samples = mutableListOf<LinkQualitySample>()
        val deadline = clock() + durationMs
        while (clock() < deadline) {
            samples += collector.collect().filter { it.deviceAddress == address }
            sleep(intervalMs)
        }
        return samples
    }

    private companion object {
        /** Ascending "goodness" — last entry wins when several codecs held. */
        val CODEC_PREFERENCE = listOf(
            CodecFamily.UNKNOWN,
            CodecFamily.SBC,
            CodecFamily.AAC,
            CodecFamily.APTX,
            CodecFamily.APTX_HD,
            CodecFamily.APTX_ADAPTIVE,
            CodecFamily.LDAC,
        )
    }
}

/** Pure statistics over a soak run. Separated out so it is trivially testable. */
object DiagnosticAnalysis {

    data class Result(
        val bestStableCodec: CodecFamily? = null,
        val codecChanges: Int = 0,
        val dropCount: Int = 0,
        val rssiRange: IntRange? = null,
    )

    fun analyse(samples: List<LinkQualitySample>): Result {
        if (samples.isEmpty()) return Result()

        var codecChanges = 0
        var drops = 0
        var previous: LinkQualitySample? = null
        // Time each codec held while playing — the "most stable" is the codec
        // that survived the longest, not the one seen most often.
        val heldMs = mutableMapOf<CodecFamily, Long>()

        for (sample in samples) {
            val prev = previous
            if (prev != null) {
                if (prev.codec != null && sample.codec != null && prev.codec != sample.codec) {
                    codecChanges++
                }
                prev.codec?.let { codec ->
                    val delta = (sample.timestampMs - prev.timestampMs).coerceAtLeast(0)
                    heldMs[codec] = (heldMs[codec] ?: 0) + delta
                }
                if (prev.isPlaying && !sample.isPlaying) drops++
            }
            drops += sample.droppedPackets?.let { if (it > 0) 1 else 0 } ?: 0
            previous = sample
        }

        val rssis = samples.mapNotNull { it.rssiDbm }
        return Result(
            bestStableCodec = heldMs.maxByOrNull { it.value }?.key
                ?: samples.lastOrNull { it.codec != null }?.codec,
            codecChanges = codecChanges,
            dropCount = drops,
            rssiRange = if (rssis.isEmpty()) null else rssis.min()..rssis.max(),
        )
    }
}
