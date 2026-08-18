package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.hearing.fit.DeviceFormFactor

/**
 * The bundled device calibration presets.
 *
 * ## Honesty rules that govern every number in this file
 *
 * 1. These are **approximations**, hand-read from published frequency-response
 *    charts and rounded to 0.5 dB. Every preset carries `approximate = true`
 *    and the UI prints that. They are *shape* corrections relative to the named
 *    target curve — never absolute calibrated levels.
 * 2. Over-ear rigs (GRAS 45CA with anthropometric pinna) and IEM couplers
 *    (IEC 60318-4) are **not comparable**. That is why the rig and the target
 *    curve are stored per preset instead of being assumed globally.
 * 3. Values are entered as *response deviation* (positive = the headphone plays
 *    that band louder than its target), which is how measurement databases
 *    publish them. [CalibrationPreset.fromResponseDeviation] negates them into
 *    the threshold correction the compensation math subtracts.
 *
 * ## Dropping real measurement data in later
 *
 * Replace the `responseDeviationDb` list of a preset — nothing else. The lists
 * are index-aligned with [TEST_FREQUENCIES_HZ] (250, 500, 1k, 2k, 3k, 4k, 6k,
 * 8k Hz). A future importer only has to produce that 8-value list from a raw
 * measurement file and call the same factory.
 */
object BundledCalibrationPresets : CalibrationPresetRepository {

    private const val APPROX_NOTE =
        "Approximate shape read from published measurements; replace with real " +
            "data when available. Not an absolute calibration."

    /** Zero correction. Always available, always the safe fallback. */
    val generic: CalibrationPreset = CalibrationPreset(
        id = CalibrationPresetRepository.GENERIC_ID,
        displayName = "Uncalibrated generic",
        dataSource = "None",
        measurementRig = "None",
        targetCurve = "None (raw device response)",
        offsetsDb = List(TEST_FREQUENCIES_HZ.size) { 0.0 },
        formFactor = DeviceFormFactor.OVER_EAR,
        approximate = false,
        notes = "No correction at all. Thresholds are whatever your device " +
            "produced — use this when no measured preset fits.",
    )

    val focalBathys: CalibrationPreset = CalibrationPreset.fromResponseDeviation(
        id = "focal_bathys",
        displayName = "Focal Bathys",
        dataSource = "Public measurement databases (Rtings / Crinacle), approximated",
        measurementRig = "GRAS 45CA (over-ear, anthropometric pinna)",
        targetCurve = "Harman OE 2018",
        formFactor = DeviceFormFactor.OVER_EAR,
        responseDeviationDb = listOf(1.0, 0.5, 0.0, -1.0, -2.5, -1.0, 2.0, -3.0),
        notes = APPROX_NOTE,
    )

    val nobleEncore: CalibrationPreset = CalibrationPreset.fromResponseDeviation(
        id = "noble_encore",
        displayName = "Noble FoKus Prestige Encore",
        dataSource = "Public measurement databases (Crinacle-style IEM graphs), approximated",
        measurementRig = "IEC 60318-4 occluded-ear simulator (IEM coupler)",
        targetCurve = "Harman IE 2019",
        formFactor = DeviceFormFactor.IN_EAR,
        responseDeviationDb = listOf(2.0, 1.0, 0.0, -1.5, -3.0, -1.0, 1.5, -2.0),
        notes = "$APPROX_NOTE Tip choice and insertion depth shift the treble " +
            "more than this preset does — run the fit check.",
    )

    val sennheiserMomentum4: CalibrationPreset = CalibrationPreset.fromResponseDeviation(
        id = "sennheiser_momentum4",
        displayName = "Sennheiser Momentum 4 Wireless",
        dataSource = "Public measurement databases (Rtings), approximated",
        measurementRig = "GRAS 45CA (over-ear, anthropometric pinna)",
        targetCurve = "Harman OE 2018",
        formFactor = DeviceFormFactor.OVER_EAR,
        responseDeviationDb = listOf(2.0, 1.0, 0.0, -2.0, -3.0, -2.0, 1.0, -2.5),
        notes = "$APPROX_NOTE Set the Sennheiser Smart Control EQ to flat first.",
    )

    val airPodsPro3: CalibrationPreset = airPods(
        id = "airpods_pro_3",
        name = "AirPods Pro 3",
        deviation = listOf(1.0, 0.5, 0.0, -1.0, -2.0, -1.0, 1.0, -2.0),
        sealed = true,
    )

    val airPodsPro2: CalibrationPreset = airPods(
        id = "airpods_pro_2",
        name = "AirPods Pro 2",
        deviation = listOf(1.5, 0.5, 0.0, -1.5, -2.5, -1.5, 1.5, -2.5),
        sealed = true,
    )

    val airPods4Anc: CalibrationPreset = airPods(
        id = "airpods_4_anc",
        name = "AirPods 4 (ANC)",
        deviation = listOf(0.5, 0.0, 0.0, -2.0, -3.0, -2.0, 1.0, -3.0),
        sealed = false,
    )

    val airPods4: CalibrationPreset = airPods(
        id = "airpods_4",
        name = "AirPods 4",
        deviation = listOf(0.0, 0.0, 0.0, -2.5, -3.5, -2.5, 0.5, -3.5),
        sealed = false,
    )

    val airPods3: CalibrationPreset = airPods(
        id = "airpods_3",
        name = "AirPods (3rd generation)",
        deviation = listOf(-1.0, -0.5, 0.0, -2.5, -4.0, -3.0, 0.0, -4.0),
        sealed = false,
    )

    val airPods2: CalibrationPreset = airPods(
        id = "airpods_2",
        name = "AirPods (2nd generation)",
        deviation = listOf(-3.0, -1.5, 0.0, -3.0, -4.5, -3.5, -1.0, -5.0),
        sealed = false,
    )

    private val presets: List<CalibrationPreset> = listOf(
        generic,
        focalBathys,
        nobleEncore,
        sennheiserMomentum4,
        airPodsPro3,
        airPodsPro2,
        airPods4Anc,
        airPods4,
        airPods3,
        airPods2,
    )

    private val index: Map<String, CalibrationPreset> = presets.associateBy { it.id }

    override fun all(): List<CalibrationPreset> = presets

    override fun byId(id: String): CalibrationPreset? = index[id]

    /** Never-null lookup: unknown ids degrade to [generic] instead of crashing. */
    fun byIdOrGeneric(id: String?): CalibrationPreset = id?.let { index[it] } ?: generic

    /**
     * All AirPods models are plain AAC A2DP devices on Android — no vendor
     * features, no readable onboard EQ. Only the acoustic shape differs, and
     * the open-fit (non-sealing) models lose low end on top of that.
     */
    private fun airPods(
        id: String,
        name: String,
        deviation: List<Double>,
        sealed: Boolean,
    ): CalibrationPreset = CalibrationPreset.fromResponseDeviation(
        id = id,
        displayName = name,
        dataSource = "Public measurement databases, approximated",
        measurementRig = "IEC 60318-4 occluded-ear simulator (IEM coupler)",
        targetCurve = "Harman IE 2019",
        formFactor = DeviceFormFactor.IN_EAR,
        responseDeviationDb = deviation,
        notes = buildString {
            append(APPROX_NOTE)
            append(" On Android this is a plain AAC A2DP device — no vendor features.")
            if (!sealed) {
                append(" Open fit: bass response depends heavily on ear shape, ")
                append("so the low bands are the least trustworthy here.")
            }
        },
    )
}
