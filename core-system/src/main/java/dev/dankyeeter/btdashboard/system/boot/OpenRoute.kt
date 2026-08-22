package dev.dankyeeter.btdashboard.system.boot

/**
 * How something outside the UI asks for a particular screen.
 *
 * Notifications live in `core-system`, which sits below `:app` and cannot name
 * an Activity or a navigation route. So the contract is a string extra on the
 * ordinary launcher intent: this module writes it, `:app` reads it on the way
 * in and navigates. Keeping both halves in one place is what stops them from
 * drifting apart silently - a mismatch here fails by doing nothing, which is
 * the hardest kind of bug to notice.
 */
object OpenRoute {

    /** Extra on the launcher intent naming the screen to open. */
    const val EXTRA = "dev.dankyeeter.btdashboard.OPEN_ROUTE"

    /**
     * The setup screen that hands over the ADB command.
     *
     * Must match `ROUTE_WIZARD` in `:app`. It is a literal rather than a
     * reference because the dependency only points one way.
     */
    const val SETUP = "wizard"

    /**
     * The one-button screen that brings the helper back.
     *
     * Separate from [SETUP] because the two answer different questions. Setup is
     * a five-step review of everything the app needs; this is the single thing
     * that broke during the night, and putting it behind five steps would be
     * asking the user to audit their phone when all they wanted was their
     * equaliser back.
     */
    const val ACTIVATE = "activate"
}
