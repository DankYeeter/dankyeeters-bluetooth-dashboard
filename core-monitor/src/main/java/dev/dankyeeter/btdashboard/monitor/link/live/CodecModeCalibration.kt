package dev.dankyeeter.btdashboard.monitor.link.live

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * One learned signature: while [modeRawValue] was pinned on this device, the
 * link produced this packing.
 *
 * Keyed by (device, codec name, mode value) rather than by anything derived,
 * because all three change the answer: a different headphone negotiates a
 * different MTU, a different codec has different frames, and the mode value is
 * what the pin actually set. The codec is stored by **name** for the reason
 * given in [CodecModeSignatureRegistry] — the numeric type is ambiguous on this
 * hardware and the name is not.
 */
data class ModeSignatureSample(
    val deviceKey: String,
    val codecName: String,
    val modeRawValue: Long,
    val sampleRateHz: Int,
    /** MEASURED: the frames-per-packet band observed while the mode was pinned. */
    val framesPerPacket: ClosedFloatingPointRange<Double>,
    /** MEASURED: the packet rate observed alongside it. Diagnostic, not the key. */
    val packetsPerSecond: ClosedFloatingPointRange<Double>,
    val capturedAtMs: Long,
)

/**
 * Where learned signatures live.
 *
 * Lookup is **case-insensitive in the codec name** and exact in the other two
 * parts of the key; every implementation has to honour that, because the name
 * arrives from whichever dump printed it and its case is not something a caller
 * can rely on.
 *
 * The shipped implementation is `RoomCodecModeSignatureStore`, which persists to
 * `MonitorDatabase`: a calibration outlives the process, so the UI should say it
 * is saved rather than that it lasts until the app is killed. (An earlier note
 * here said the opposite, and was correct at the time — the database was built
 * with `fallbackToDestructiveMigration()`, so adding an entity would have wiped
 * the user's monitor history. That fallback is gone and the schema change is
 * migrated, which is what made persisting these affordable.)
 *
 * [InMemoryCodecModeSignatureStore] remains for tests and as the fallback when
 * the database cannot be opened at all.
 */
interface CodecModeSignatureStore {
    suspend fun signatures(deviceKey: String, codecName: String): List<ModeSignatureSample>
    suspend fun put(sample: ModeSignatureSample)
    suspend fun clear(deviceKey: String, codecName: String)
}

/** The volatile implementation: test double, and the fallback for a dead database. */
class InMemoryCodecModeSignatureStore : CodecModeSignatureStore {

    private val samples = mutableMapOf<Triple<String, String, Long>, ModeSignatureSample>()
    private val lock = Any()

    override suspend fun signatures(deviceKey: String, codecName: String): List<ModeSignatureSample> =
        synchronized(lock) {
            samples.values.filter { it.deviceKey == deviceKey && it.codecName.equals(codecName, true) }
        }

    override suspend fun put(sample: ModeSignatureSample) = synchronized(lock) {
        // One sample per (device, codec, mode): a recalibration replaces rather
        // than accumulates, so a band measured on a bad day cannot outvote a
        // later, cleaner one.
        samples[Triple(sample.deviceKey, sample.codecName.uppercase(), sample.modeRawValue)] = sample
    }

    override suspend fun clear(deviceKey: String, codecName: String) = synchronized(lock) {
        samples.keys
            .filter { it.first == deviceKey && it.second.equals(codecName.uppercase(), true) }
            .forEach(samples::remove)
    }
}

/**
 * Sets a codec's bitrate mode through the privileged codec-preference path.
 *
 * Separate from `CodecController` on purpose: that one selects a codec
 * *family*, and this sets `codecSpecific1` **within** a family, which is a
 * different request with a different failure mode. Implemented in `:app` over
 * the helper's `setCodecPreference`, exactly as `CodecController` is.
 */
interface CodecModePinner {
    /**
     * Returns the mode value **observed afterwards**, or null when the request
     * was refused, unreachable, or had not taken effect by the time we looked.
     * Never the value that was requested — the read-back is the whole point.
     */
    suspend fun pinMode(address: String, codec: CodecFamily, modeRawValue: Long): Long?
}

/** What runs without the privileged helper: nothing can be set, and nothing is claimed. */
object NoOpCodecModePinner : CodecModePinner {
    override suspend fun pinMode(address: String, codec: CodecFamily, modeRawValue: Long): Long? = null
}

/** One mode's result, for progress reporting while the run is in flight. */
data class CalibrationStep(
    val mode: CodecMode,
    val sample: ModeSignatureSample?,
    val skippedReason: String?,
)

data class CalibrationReport(
    val deviceKey: String,
    val codecName: String,
    val learned: List<ModeSignatureSample> = emptyList(),
    val skipped: List<CalibrationStep> = emptyList(),
    /** The `codecSpecific1` the link was on before, and whether it got back there. */
    val previousModeRawValue: Long? = null,
    val restored: Boolean = false,
    val note: String = "",
) {
    val succeeded: Boolean get() = learned.isNotEmpty()
}

/**
 * Teaches the app what each bitrate mode looks like on one particular link.
 *
 * ## Why this is worth a mutating operation
 *
 * [CodecModeInference]'s analytic path is stuck behind one unknown: the A2DP
 * media MTU, which no dump on this phone prints. Pinning a mode removes it —
 * the link demonstrates its own frames-per-packet under a mode we chose, and
 * from then on that packing identifies that mode with no assumption left in the
 * chain. One ten-second pass replaces a table of plausible MTUs with a
 * measurement.
 *
 * ## What it does to the device
 *
 * This **renegotiates the codec three times**. Each renegotiation restarts the
 * A2DP stream and is audible as a short gap. It must therefore only ever run
 * from an explicit user action, never on a timer, never on screen entry, and
 * never as part of a poll — the data layer offers it as a suspend function and
 * takes no view on when it is called.
 *
 * The previous `codecSpecific1` is captured first and restored in a `finally`,
 * so a cancelled or failed run still leaves the link on whatever the user had —
 * normally `0`, meaning unpinned and adaptive.
 */
class CodecModeCalibrator(
    private val source: LiveLinkSource,
    private val pinner: CodecModePinner,
    private val store: CodecModeSignatureStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * @param dwellMs how long to measure once a pinned mode has settled. The
     *   band is only as good as the counter delta across it.
     * @param settleMs discarded after each pin, because the window containing
     *   the renegotiation contains both packings and identifies neither.
     */
    suspend fun calibrate(
        address: String,
        deviceKey: String,
        dwellMs: Long = DEFAULT_DWELL_MS,
        settleMs: Long = DEFAULT_SETTLE_MS,
        onProgress: (CalibrationStep) -> Unit = {},
    ): CalibrationReport {
        val opening = source.readOnce()
        val codec = opening.codec
            ?: return CalibrationReport(deviceKey, "", note = "no negotiated codec to calibrate")
        val codecName = codec.rawCodecName ?: codec.family.displayName

        refusalFor(opening, codec)?.let {
            return CalibrationReport(deviceKey, codecName, note = it)
        }

        val provider = CodecModeSignatureRegistry.providerFor(codec.family, codec.rawCodecName)
        val modes = provider?.signatures(codec.sampleRateHz ?: 0)?.map { it.mode }.orEmpty()
        if (modes.isEmpty()) {
            return CalibrationReport(
                deviceKey,
                codecName,
                note = provider?.unverifiedReason
                    ?: "$codecName has no pinnable bitrate modes to calibrate",
            )
        }

        val previous = codec.codecSpecific1
        val learned = mutableListOf<ModeSignatureSample>()
        val skipped = mutableListOf<CalibrationStep>()
        var restored = false
        try {
            for (mode in modes) {
                val step = measure(address, deviceKey, codecName, codec, mode, dwellMs, settleMs)
                onProgress(step)
                step.sample?.let {
                    learned += it
                    store.put(it)
                } ?: skipped.add(step)
            }
        } finally {
            // The link must not be left pinned because a run was cancelled. 0 is
            // "no user preference", which is what an untouched phone reports and
            // what puts LDAC back on adaptive.
            restored = pinner.pinMode(address, codec.family, previous ?: 0L) != null
        }

        return CalibrationReport(
            deviceKey = deviceKey,
            codecName = codecName,
            learned = learned,
            skipped = skipped,
            previousModeRawValue = previous,
            restored = restored,
            note = when {
                learned.size == modes.size -> "learned all ${modes.size} modes on this link"
                learned.isEmpty() -> "no mode could be measured — see the skipped reasons"
                else -> "learned ${learned.size} of ${modes.size} modes; the rest are unchanged"
            },
        )
    }

    /**
     * Why a run cannot start. Checked up front so the user is told before the
     * link is disturbed, not after three renegotiations produced nothing.
     */
    private fun refusalFor(snapshot: LinkLiveSnapshot, codec: LiveCodecSnapshot): String? = when {
        snapshot.device?.isConnected != true -> "the device is not connected"
        snapshot.device.isPlaying != true ->
            "nothing is playing — with no stream there are no packets to count"
        codec.isOffloaded ->
            "${codec.family.displayName} is encoded by the controller, so the host " +
                "cannot count its packets at all"
        else -> null
    }

    private suspend fun measure(
        address: String,
        deviceKey: String,
        codecName: String,
        codec: LiveCodecSnapshot,
        mode: CodecMode,
        dwellMs: Long,
        settleMs: Long,
    ): CalibrationStep {
        val applied = pinner.pinMode(address, codec.family, mode.rawValue)
        if (applied != mode.rawValue) {
            return CalibrationStep(
                mode = mode,
                sample = null,
                skippedReason = if (applied == null) {
                    "${mode.label} was refused or could not be read back"
                } else {
                    "asked for ${mode.label} but the link reports $applied"
                },
            )
        }
        delay(settleMs)

        val before = source.readOnce()
        delay(dwellMs)
        val after = source.readOnce(before)
        val delta = after.txDelta
        val framesPerPacket = delta?.framesPerPacket
        val packetsPerSecond = delta?.packetsPerSecond
        if (framesPerPacket == null || packetsPerSecond == null || delta.enqueued <= 0) {
            return CalibrationStep(mode, null, "no packets moved while ${mode.label} was pinned")
        }
        // Confirm the pin survived the dwell. A stack that quietly renegotiated
        // back would otherwise have its packing recorded under the wrong label,
        // which is worse than learning nothing.
        if (after.codec?.codecSpecific1 != mode.rawValue) {
            return CalibrationStep(mode, null, "${mode.label} did not hold for the whole window")
        }

        val whole = framesPerPacket.roundToInt()
        return CalibrationStep(
            mode = mode,
            sample = ModeSignatureSample(
                deviceKey = deviceKey,
                codecName = codecName,
                modeRawValue = mode.rawValue,
                sampleRateHz = after.codec.sampleRateHz ?: 0,
                // Half an integer either side: frames per packet is a whole
                // number for a fixed link, so this band is exact and two
                // distinct packings can never overlap. If two modes do land on
                // the same integer, the bands collide and the inference says so
                // rather than picking one.
                framesPerPacket = (whole - 0.5)..(whole + 0.5),
                packetsPerSecond = (packetsPerSecond * 0.9)..(packetsPerSecond * 1.1),
                capturedAtMs = clock(),
            ),
            skippedReason = null,
        )
    }

    companion object {
        /** Long enough for a counter delta to be dominated by signal, not skew. */
        const val DEFAULT_DWELL_MS = 10_000L

        /** The renegotiation itself, plus the first packets after it. */
        const val DEFAULT_SETTLE_MS = 3_000L
    }
}
