package dev.dankyeeter.btdashboard.system.airpods

/**
 * Decoded state of one Apple "proximity pairing" BLE advertisement.
 *
 * Everything in here is *broadcast in the clear* by the earbuds themselves; we
 * only listen. No pairing, no Apple hardware, no writes — reading a public
 * advertisement is the entire mechanism. The second half of the payload is
 * encrypted with a key only Apple devices hold; we never touch it.
 *
 * Battery levels are reported by the buds in 10 % steps and are frequently
 * absent, hence nullable [Int] percentages instead of a fake 0.
 */
data class AirPodsBeacon(
    val model: AirPodsModel,
    val leftBatteryPercent: Int?,
    val rightBatteryPercent: Int?,
    val caseBatteryPercent: Int?,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val caseCharging: Boolean,
)

/**
 * Recognised AirPods models.
 *
 * The id ↔ model mapping comes from the publicly documented proximity-beacon
 * format (the same knowledge open-source projects such as CAPod work from —
 * reimplemented here from the format description, no code taken). Ids that are
 * not in this table degrade to [UNKNOWN] and the app falls back to the generic
 * calibration preset instead of guessing.
 *
 * @param calibrationPresetId id of the bundled preset in :core-hearing that
 *   matches this hardware; null when we ship no measured preset for it.
 */
enum class AirPodsModel(val displayName: String, val calibrationPresetId: String?) {
    AIRPODS_1("AirPods (1st generation)", null),
    AIRPODS_2("AirPods (2nd generation)", "airpods_2"),
    AIRPODS_3("AirPods (3rd generation)", "airpods_3"),
    AIRPODS_4("AirPods 4", "airpods_4"),
    AIRPODS_4_ANC("AirPods 4 (ANC)", "airpods_4_anc"),
    AIRPODS_PRO("AirPods Pro", null),
    AIRPODS_PRO_2("AirPods Pro 2", "airpods_pro_2"),
    AIRPODS_PRO_2_USB_C("AirPods Pro 2 (USB-C)", "airpods_pro_2"),
    AIRPODS_PRO_3("AirPods Pro 3", "airpods_pro_3"),
    /** Deliberately without a preset: docs/archiv/PLAN.md excludes AirPods Max. */
    AIRPODS_MAX("AirPods Max", null),
    UNKNOWN("Unknown Apple audio device", null),
    ;

    companion object {
        /**
         * Model ids as they appear in the advertisement, big-endian.
         *
         * Honesty note: the ids for the newest hardware (AirPods 4 / Pro 3) are
         * the least verified entries here. A wrong id costs us nothing worse
         * than [UNKNOWN] plus the generic preset — it can never mis-apply a
         * different device's correction, because the preset is looked up from
         * the enum, not from the raw id.
         */
        private val BY_ID: Map<Int, AirPodsModel> = mapOf(
            0x2002 to AIRPODS_1,
            0x200F to AIRPODS_2,
            0x2013 to AIRPODS_3,
            0x2019 to AIRPODS_4,
            0x201B to AIRPODS_4_ANC,
            0x200E to AIRPODS_PRO,
            0x2014 to AIRPODS_PRO_2,
            0x2024 to AIRPODS_PRO_2_USB_C,
            0x2026 to AIRPODS_PRO_3,
            0x200A to AIRPODS_MAX,
        )

        fun fromId(id: Int): AirPodsModel = BY_ID[id] ?: UNKNOWN
    }
}
