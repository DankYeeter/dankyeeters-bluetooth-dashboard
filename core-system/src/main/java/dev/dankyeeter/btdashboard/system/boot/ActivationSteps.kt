package dev.dankyeeter.btdashboard.system.boot

/**
 * What the user has to do to bring the helper up, written once.
 *
 * The same instructions belong in three places - the notification that appears
 * after a reboot, the notification that asks for the pairing code, and the
 * Activate screen itself. Three copies of a procedure drift, and the copy that
 * goes stale is whichever one the user happens to be reading. This is the one
 * that is allowed to exist.
 *
 * ## Why it is hidden by default
 *
 * All of this is needed exactly once per phone. After the first pairing the key
 * is stored on the device and survives reboots, so every later activation is a
 * single tap and these steps are noise. They are therefore never in the
 * collapsed notification and never on the face of the Activate screen - they
 * are one expansion away, for the run where the tap is not enough.
 */
object ActivationSteps {

    /** The collapsed line. It only has to say why the notification is there. */
    const val SHORT = "Tap Activate to restore it."

    /**
     * The full procedure.
     *
     * Numbered because it is order-dependent - wireless debugging does not exist
     * as a setting until developer options are on - and because a user reading
     * this is holding a six-digit code that expires.
     *
     * Developer options are step one and cannot be automated away: turning them
     * on requires a permission the app can only obtain *after* this procedure
     * has been completed once.
     */
    val FULL: String = listOf(
        "Tap Activate. If it asks for a pairing code:",
        "",
        "1. Settings → About phone → tap Build number seven times",
        "2. Settings → System → Developer options → Wireless debugging → on",
        "3. Open \"Pair device with pairing code\" and leave it open",
        "4. Type the six digits into this notification",
        "",
        "Needed once. After that, Activate on its own is enough.",
    ).joinToString("\n")

    /**
     * The same steps for the Activate screen, where "this notification" is the
     * wrong noun and the shade is not where the code goes.
     */
    val FULL_IN_APP: String = FULL
        .replace("into this notification", "into the notification that appears")

    /**
     * The steps plus the one fact that makes them urgent.
     *
     * Only the notification that actually asks for the code says this. Elsewhere
     * it would be a warning about a deadline the reader is not yet under.
     */
    val FULL_FOR_PAIRING: String = listOf(
        FULL,
        "",
        "The code stops working the moment you leave Android's pairing screen.",
    ).joinToString("\n")
}
