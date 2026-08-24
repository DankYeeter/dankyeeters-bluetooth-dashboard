package dev.dankyeeter.btdashboard.system.secure

/**
 * Whether WRITE_SECURE_SETTINGS is granted.
 *
 * The grant is a `pm grant` away and survives reboots. It used to live in a
 * package named after Shizuku, which had nothing to do with it; the rename
 * waited for Shizuku to be gone from the project entirely, which it now is.
 */
enum class SecureSettingsState { GRANTED, NOT_GRANTED }
