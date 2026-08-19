package dev.dankyeeter.btdashboard.hearing

/**
 * Finds the calibration preset for a device from the name Bluetooth reports.
 *
 * The bundled presets are shipped support, not a catalogue to browse: the user
 * connects a headphone and the right correction is simply in force. Nothing in
 * the UI lists the supported models, so this matcher is the only thing standing
 * between "Focal Bathys" on the Bluetooth link and the Focal Bathys curve.
 *
 * Matching is deliberately conservative. A wrong preset is worse than none —
 * it applies a correction curve measured on different hardware and quietly
 * makes every threshold that follows meaningless — so anything short of a
 * confident match returns null and the generic (zero-correction) preset stays.
 */
object PresetMatching {

    /**
     * Distinctive tokens per preset. Model numbers matter: "Momentum 4" and
     * "Momentum 3" are different curves, so the digit is part of the key.
     */
    private val keys: List<Pair<String, List<String>>> = listOf(
        "focal_bathys" to listOf("bathys"),
        "noble_encore" to listOf("fokus prestige encore", "prestige encore", "fokus encore"),
        "sennheiser_momentum4" to listOf("momentum 4", "momentum4"),
        "airpods_pro_3" to listOf("airpods pro 3", "airpods pro (3"),
        "airpods_pro_2" to listOf("airpods pro 2", "airpods pro (2"),
        "airpods_4_anc" to listOf("airpods 4 anc", "airpods 4 (anc"),
        "airpods_4" to listOf("airpods 4"),
        "airpods_3" to listOf("airpods 3", "airpods (3rd"),
        "airpods_2" to listOf("airpods 2", "airpods (2nd"),
    )

    /**
     * @param deviceName the Bluetooth friendly name, e.g. "Focal Bathys".
     * @return the preset id, or null when nothing matches confidently.
     */
    fun presetIdFor(deviceName: String?): String? {
        val name = deviceName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        // Longest token first: "airpods pro 2" has to win over "airpods 2",
        // and "momentum 4" over any looser "momentum" key added later.
        return keys
            .flatMap { (id, tokens) -> tokens.map { id to it } }
            .sortedByDescending { it.second.length }
            .firstOrNull { (_, token) -> name.contains(token) }
            ?.first
    }

    /** Convenience: the matching preset, or the generic one. */
    fun presetFor(
        deviceName: String?,
        repository: CalibrationPresetRepository,
    ): CalibrationPreset? = presetIdFor(deviceName)?.let(repository::byId)
}
