package dev.dankyeeter.btdashboard.monitor.optimize

/**
 * A condition of the surroundings the comparison reads at each end of an arm
 * (AD-035). [stable] conditions make two arms incomparable when they change;
 * the others are events, named in the frame and never blocking.
 */
enum class Condition(val stable: Boolean) {
    WIFI_RADIO(true),
    WIFI_SCAN_ALWAYS(true),
    USB_POWER(true),
    OTHER_ACL_LINKS(true),

    /** An event, not a state: seen in a reading or not. */
    DISCOVERY_SEEN(false),
}

/** One reading of a [Condition]. What could not be read says why, and nothing is derived from it. */
sealed interface ConditionValue {
    /** "on"/"off", "usb"/"other"/"none", "0".."n", "yes"/"no". */
    data class Read(val value: String) : ConditionValue

    data class Unreadable(val reason: String) : ConditionValue
}

/** The conditions as read at one moment. */
typealias ConditionBook = Map<Condition, ConditionValue>

/** Whether a reading shows a measure as carried out. */
enum class Verification { VERIFIED, CONTRADICTED, NOT_VERIFIABLE }

/**
 * The measures of R-010 part 1, in the order given there. A6 is missing: it is
 * a property of the pairing, not a setting. Identifiers only; the words for
 * the screen come from `UI_SPEC.md`.
 */
enum class Measure(val r010: String, val condition: Condition?) {
    NO_2_4_GHZ_WIFI("A2", Condition.WIFI_RADIO),

    /** An event; no reading can show it absent. */
    NO_DISCOVERY("A1", null),
    NO_SECOND_DEVICE("A3", Condition.OTHER_ACL_LINKS),
    BODY_OUT_OF_PATH("A4", null),
    USB_CABLE_OFF("A5", Condition.USB_POWER),
    SINK_ALLOWS_LDAC("A7", null),
    ;

    /**
     * Whether [value], a reading of [condition], shows this measure carried
     * out. Wi-Fi "on" is not a contradiction: the radio may be on a 5 GHz
     * network, and `wifi_on` does not say the band.
     */
    fun verify(value: ConditionValue?): Verification {
        val read = (value as? ConditionValue.Read)?.value ?: return Verification.NOT_VERIFIABLE
        return when (this) {
            NO_2_4_GHZ_WIFI -> if (read == "off") Verification.VERIFIED else Verification.NOT_VERIFIABLE
            NO_SECOND_DEVICE -> if (read == "0") Verification.VERIFIED else Verification.CONTRADICTED
            USB_CABLE_OFF -> if (read == "none") Verification.VERIFIED else Verification.CONTRADICTED
            NO_DISCOVERY, BODY_OUT_OF_PATH, SINK_ALLOWS_LDAC -> Verification.NOT_VERIFIABLE
        }
    }
}

/**
 * R-010 part 2: these lower the bitrate. Never a fix, only shown as a way
 * around; no comparison and no pinning belongs to them.
 */
enum class Fallback { LOWER_STEP, ADAPTIVE_BITRATE, CODEC_CHANGE, FAMILY_44_1_KHZ }
