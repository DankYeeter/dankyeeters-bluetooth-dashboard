package dev.dankyeeter.btdashboard.ui.screens.preference

/**
 * Everything the preference screens can ask for.
 *
 * An interface with no-op defaults rather than a dozen lambda parameters, for
 * one reason: the screens are rendered in tests without a ViewModel behind them
 * (there is no Bluetooth device, no DataStore and no audio effect in a
 * Robolectric run), and a test that only cares about one button should not have
 * to supply fifteen empty lambdas to get there.
 */
internal interface PreferenceTestActions {
    /** Begin a song-run, or another one. */
    fun start() {}

    /** Switch the live EQ to one of the two candidates. */
    fun play(slot: AbSlot) {}

    /** Take the candidate that is playing as the answer. */
    fun confirm() {}

    fun noDifference() {}

    fun setRunLabel(text: String) {}

    fun addAnotherSong() {}

    fun finish() {}

    fun playFinalCheck(slot: AbSlot) {}

    /** null answers "no difference". */
    fun answerFinalCheck(slot: AbSlot?) {}

    fun skipFinalCheck() {}

    fun setBassDb(db: Float) {}

    fun setTrebleDb(db: Float) {}

    /** Drop the hand adjustment and go back to what the songs said. */
    fun clearAdjustment() {}

    fun removeRun(id: String) {}

    /** Reopen a stored profile to look at or edit. */
    fun openResult() {}

    fun save() {}

    fun discard() {}

    fun deleteProfile() {}

    fun requestCancel() {}

    fun confirmCancel() {}

    fun dismissDialog() {}

    fun dismissMessage() {}

    /** Keep the hand adjustment when a new song would overwrite it. */
    fun keepAdjustment() {}

    /** Let the new song's answer replace the hand adjustment. */
    fun useNewMeasurement() {}
}
