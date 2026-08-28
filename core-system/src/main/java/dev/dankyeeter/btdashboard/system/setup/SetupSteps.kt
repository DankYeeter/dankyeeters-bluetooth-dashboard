package dev.dankyeeter.btdashboard.system.setup

/**
 * What a step being unmet actually costs. This, not a stored flag, is what
 * decides which of the three faces of the app the user gets.
 */
enum class SetupNeed {
    /**
     * Missing means the app cannot work at all, and the whole setup process
     * opens until it is granted.
     */
    REQUIRED,

    /** Asked once inside the process. Never blocks anything, ever. */
    OPTIONAL,

    /**
     * Missing means the app shows the Activate button alone — not the whole
     * process. This is the ordinary state after a reboot, and walking someone
     * through four steps to reach the one tap they need would be an insult.
     */
    ACTIVATION,
}

/**
 * Everything the app needs from the user or the OS, in the order the setup
 * process asks for it.
 *
 * The order is deliberate: the cheap runtime dialogs come first and the pairing
 * last, because pairing is the only step that leaves the app — and it is the
 * one that needs [NOTIFICATIONS] to already be granted, since the six-digit
 * code is typed into a notification.
 */
enum class SetupStep(
    val id: String,
    val title: String,
    /**
     * The one line the wizard puts on its face.
     *
     * Separate from [rationale] because the two answer different questions.
     * Someone walking through four steps wants to know what each one is for in
     * the time it takes to read a line; the paragraph is for the one person in
     * ten who wants to know why the app needs it at all, and it belongs behind
     * the question mark rather than in front of everyone.
     */
    val summary: String,
    /** Why we ask. Every entry says what breaks without it — no vague pleading. */
    val rationale: String,
    val need: SetupNeed,
) {
    BLUETOOTH(
        id = "bluetooth",
        title = "Bluetooth access",
        summary = "So the app can see your headphone.",
        rationale = "Lets the app see which headphone is connected, read its codec and " +
            "apply that device's profile. Without it the dashboard and the link " +
            "monitor stay empty.",
        need = SetupNeed.REQUIRED,
    ),
    MICROPHONE(
        id = "microphone",
        title = "Microphone",
        summary = "So the hearing test can check the room is quiet.",
        rationale = "Used only for the few seconds of level measurement before a " +
            "hearing test. Nothing is recorded and nothing is stored.",
        need = SetupNeed.OPTIONAL,
    ),
    // Required since the app started pairing itself: Android's pairing code is
    // typed into a notification, so without this permission the last step below
    // cannot be finished at all. It used to be optional, back when the helper
    // was started with an ADB command from a computer.
    NOTIFICATIONS(
        id = "notifications",
        title = "Notifications",
        summary = "The pairing code below is typed into one.",
        rationale = "The pairing code is typed into a notification, so the last step " +
            "here cannot be finished without this. Afterwards they are used for one " +
            "thing: telling you when the equaliser has gone inactive.",
        need = SetupNeed.REQUIRED,
    ),
    // One step, because it is one action: the phone pairs with its own
    // debugging service, that starts the helper, and the helper grants the app
    // WRITE_SECURE_SETTINGS. Splitting it in two was a leftover from the time
    // when each half needed its own ADB command from a computer.
    HELPER(
        id = "helper",
        title = "Pairing and helper",
        summary = "The phone starts the helper by pairing with itself.",
        rationale = "The helper reads what the Bluetooth stack negotiated, sets codecs " +
            "and equalises players that hide their audio session. The phone pairs with " +
            "its own debugging service to start it, then switches that back off.",
        need = SetupNeed.ACTIVATION,
    ),
    ;

    /** True when the app is still useful without it. Drives the "Skip" copy. */
    val optional: Boolean get() = need == SetupNeed.OPTIONAL
}

/**
 * Live status of one step.
 *
 * There used to be a fourth, BLOCKED, for a step that could not be completed on
 * this device at all. Nothing ever produced it — every environment reported
 * every step as reachable — so it was a status the user could only reach by
 * reading the source, and its copy ("Not available right now.") was a dead end
 * that named no way forward. A state that cannot occur is not a safeguard.
 */
enum class SetupStepStatus {
    /** Requirement met. */
    DONE,

    /** Not met, and the user has not skipped it. */
    PENDING,

    /** Not met, but explicitly skipped — the process stops nagging. */
    SKIPPED,
}

/**
 * Which face of the app the current state calls for.
 *
 * Derived on every look rather than remembered. Android revokes the
 * permissions of unused apps by itself and the user can switch notifications
 * off at any moment — a stored "setup done" would then be a lie in exactly the
 * place that hurts, because it is the notification the pairing code is typed
 * into. A live answer cannot go stale.
 */
enum class SetupPhase {
    /** Something required is missing: the whole process, from the top. */
    FULL_SETUP,

    /** Permissions are in place and only the helper is gone: one button. */
    ACTIVATION_ONLY,

    /** Nothing to show. The setup lives on as an entry in Settings. */
    READY,
}

/**
 * What the setup process reads. Implemented against Android in the app module
 * and against plain values in the tests, so the rules below can be verified
 * without an emulator.
 */
interface SetupEnvironment {
    fun isSatisfied(step: SetupStep): Boolean
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
                else -> SetupStepStatus.PENDING
            }
            SetupStepState(step, status)
        }

    /**
     * The entry condition of the whole app.
     *
     * Deliberately reads the environment rather than the step list: skipping
     * must not be able to open the door. A user can skip the microphone; a
     * skipped Bluetooth permission is still a missing Bluetooth permission.
     */
    fun phase(environment: SetupEnvironment): SetupPhase = when {
        SetupStep.entries.any { it.need == SetupNeed.REQUIRED && !environment.isSatisfied(it) } ->
            SetupPhase.FULL_SETUP

        SetupStep.entries.any { it.need == SetupNeed.ACTIVATION && !environment.isSatisfied(it) } ->
            SetupPhase.ACTIVATION_ONLY

        else -> SetupPhase.READY
    }

    /** Steps still worth showing a badge for: pending, never skipped. */
    fun outstanding(states: List<SetupStepState>): List<SetupStepState> =
        states.filter { it.status == SetupStepStatus.PENDING }

    /** Whether any *required* step is still unmet — skipping cannot clear these. */
    fun hasUnmetRequirements(states: List<SetupStepState>): Boolean =
        states.any { it.step.need == SetupNeed.REQUIRED && it.status != SetupStepStatus.DONE }

    /** Copy for the settings card. Null when there is nothing to say. */
    fun summary(states: List<SetupStepState>): String? {
        val count = outstanding(states).size
        return when {
            count == 0 -> null
            count == 1 -> "Setup incomplete: 1 step left"
            else -> "Setup incomplete: $count steps left"
        }
    }
}
