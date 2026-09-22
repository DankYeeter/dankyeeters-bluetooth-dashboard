package dev.dankyeeter.btdashboard.monitor.link.live

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
    /**
     * MEASURED: the [A2dpTxDelta.framesPerEnqueue] band observed while the mode
     * was pinned.
     *
     * The name is the one the database column carries and predates knowing what
     * this ratio actually is. It is a duty-cycle band, not a packing.
     */
    val framesPerPacket: ClosedFloatingPointRange<Double>,
    /** MEASURED: the packet rate observed alongside it. Diagnostic, not the key. */
    val packetsPerSecond: ClosedFloatingPointRange<Double>,
    val capturedAtMs: Long,
)

/**
 * Where learned signatures lived. The calibrator that filled it is gone; the
 * interface stays only as long as `MonitorDatabase` still maps the table.
 */
interface CodecModeSignatureStore {
    suspend fun signatures(deviceKey: String, codecName: String): List<ModeSignatureSample>
    suspend fun put(sample: ModeSignatureSample)
    suspend fun clear(deviceKey: String, codecName: String)
}
