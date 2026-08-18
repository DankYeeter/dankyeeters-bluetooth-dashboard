package dev.dankyeeter.btdashboard.system.shizuku

/**
 * Onboarding states the UI walks the user through. The distinction matters:
 * each state has a different next action, and there is no Play Store here, so
 * the install step points at the GitHub release APK.
 */
sealed interface ShizukuState {
    /** Shizuku is not installed at all → guide to the GitHub release APK. */
    data object NotInstalled : ShizukuState

    /**
     * Installed, but the service is not running. On a Pixel without root this
     * means the user has not completed the wireless-debugging pairing + start
     * step (and it must be redone after every reboot).
     */
    data object InstalledNotRunning : ShizukuState

    /** Service running, but our app has not been authorized yet. */
    data object NotAuthorized : ShizukuState

    /** The user actively denied our permission request. */
    data object PermissionDenied : ShizukuState

    /** Service running and our app authorized. */
    data class Ready(val uid: Int, val apiVersion: Int) : ShizukuState

    /** Something unexpected — treated as "not usable", never fatal. */
    data class Error(val message: String) : ShizukuState

    val isReady: Boolean get() = this is Ready
}

/** Whether WRITE_SECURE_SETTINGS was granted (via ADB or via Shizuku). */
enum class SecureSettingsState { GRANTED, NOT_GRANTED }
