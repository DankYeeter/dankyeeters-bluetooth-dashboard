package dev.dankyeeter.btdashboard.ui.screens.hearing

/**
 * Swallows the hardware volume keys while a hearing test is running.
 *
 * The whole protocol is calibrated against the media volume latched at the
 * start of the run — one press of the volume rocker would silently rescale
 * every threshold measured so far. Compose cannot see volume keys, so
 * `MainActivity.dispatchKeyEvent` consults this flag.
 *
 * Volume changes the app *cannot* intercept (headphone buttons, assistant,
 * other apps) are caught by `VolumeGuard` and abort the run instead.
 */
object VolumeKeyLock {

    @Volatile
    var locked: Boolean = false

    /** Invoked on the main thread when a volume key press was swallowed. */
    @Volatile
    var onBlocked: (() -> Unit)? = null

    /** @return true when the key event must not reach the system. */
    fun consume(): Boolean {
        if (!locked) return false
        onBlocked?.invoke()
        return true
    }
}
