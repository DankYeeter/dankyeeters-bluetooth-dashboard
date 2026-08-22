package dev.dankyeeter.btdashboard.system.shizuku

/**
 * Whether WRITE_SECURE_SETTINGS is granted.
 *
 * Lives in the `shizuku` package for historical reasons only — the grant comes
 * from one ADB command (`pm grant … WRITE_SECURE_SETTINGS`), survives reboots,
 * and has nothing to do with any shell provider. Move alongside a wider rename
 * of this package, not piecemeal.
 */
enum class SecureSettingsState { GRANTED, NOT_GRANTED }
