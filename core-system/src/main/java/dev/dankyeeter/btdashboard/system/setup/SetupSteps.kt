package dev.dankyeeter.btdashboard.system.setup

/**
 * Everything the app needs from the user or the OS, in the order the first-run
 * wizard asks for it.
 *
 * The order is deliberate: cheap runtime dialogs first, Settings round-trips
 * next, and the ADB/Shizuku work — the only steps that need a computer — last,
 * so the user gets a working app before hitting the part that cannot be
 * finished on the phone alone.
 */
enum class SetupStep(
    val id: String,
    val title: String,
    /** Why we ask. Every entry says what breaks without it — no vague pleading. */
    val rationale: String,
    /** True when the app is still useful without it. Drives the "Skip" copy. */
    val optional: Boolean,
) {
    BLUETOOTH(
        id = "bluetooth",
        title = "Bluetooth access",
        rationale = "Needed to see which headphone is connected, read its codec, and " +
            "apply that device's profile. Without it the dashboard and the link " +
            "monitor stay empty.",
        optional = false,
    ),
    MICROPHONE(
        id = "microphone",
        title = "Microphone",
        rationale = "Used only for the ambient-noise check before a hearing test — a " +
            "few seconds of level measurement so you find out that the room is too " +
            "loud before the test, not after. Nothing is recorded, nothing is stored, " +
            "and the app has no INTERNET permission to send anything anywhere.",
        optional = true,
    ),
    NOTIFICATIONS(
        id = "notifications",
        title = "Notifications",
        rationale = "Lets the app tell you when the EQ has gone inactive after a " +
            "reboot. Without it the EQ can be silently off and you would not know.",
        optional = true,
    ),
    SHIZUKU(
        id = "shizuku",
        title = "Shizuku",
        rationale = "The system-wide EQ needs an elevated identity, which Shizuku " +
            "provides without root after a one-time ADB wireless-debugging pairing. " +
            "Without it the EQ only reaches apps that broadcast their audio session — " +
            "Tidal does not do so reliably.",
        optional = true,
    ),
    SECURE_SETTINGS(
        id = "secure_settings",
        title = "WRITE_SECURE_SETTINGS",
        rationale = "Only needed for the absolute-volume toggle. It can never be " +
            "granted from inside an app; you run one ADB command from a computer. " +
            "Everything else works without it.",
        optional = true,
    ),
}

/** Live status of one step. */
enum class SetupStepStatus {
    /** Requirement met. */
    DONE,

    /** Not met, and the user has not skipped it. */
    PENDING,

    /** Not met, but explicitly skipped — the wizard stops nagging. */
    SKIPPED,

    /**
     * Not met and not reachable on this device/build (e.g. Shizuku's binder is
     * gone). Counted as outstanding but the wizard says so instead of offering
     * a button that would do nothing.
     */
    BLOCKED,
}

/**
 * What the wizard reads. Implemented against Android in the app module and
 * against plain values in the tests, so the counting rules below can be
 * verified without an emulator.
 */
interface SetupEnvironment {
    fun isSatisfied(step: SetupStep): Boolean

    /** False when the step cannot currently be completed at all. */
    fun isReachable(step: SetupStep): Boolean = true
}

data class SetupStepState(
    val step: SetupStep,
    val status: SetupStepStatus,
)

/**
 * Turns the environment plus the set of skipped step ids into the list the UI
 * renders and the "setup incomplete: N steps" count.
 *
 * A satisfied step always reports DONE, even if it was skipped earlier —
 * granting a permission later must clear the nag, not leave it stuck.
 */
object SetupStatus {

    fun evaluate(environment: SetupEnvironment, skipped: Set<String>): List<SetupStepState> =
        SetupStep.entries.map { step ->
            val status = when {
                environment.isSatisfied(step) -> SetupStepStatus.DONE
                skipped.contains(step.id) -> SetupStepStatus.SKIPPED
                !environment.isReachable(step) -> SetupStepStatus.BLOCKED
                else -> SetupStepStatus.PENDING
            }
            SetupStepState(step, status)
        }

    /** Steps still worth showing a badge for: pending or blocked, never skipped. */
    fun outstanding(states: List<SetupStepState>): List<SetupStepState> =
        states.filter { it.status == SetupStepStatus.PENDING || it.status == SetupStepStatus.BLOCKED }

    /** Whether any *required* step is still unmet — skipping cannot clear these. */
    fun hasUnmetRequirements(states: List<SetupStepState>): Boolean =
        states.any { !it.step.optional && it.status != SetupStepStatus.DONE }

    /** Copy for the dashboard card. Null when there is nothing to say. */
    fun summary(states: List<SetupStepState>): String? {
        val count = outstanding(states).size
        return when {
            count == 0 -> null
            count == 1 -> "Setup incomplete: 1 step left"
            else -> "Setup incomplete: $count steps left"
        }
    }
}
