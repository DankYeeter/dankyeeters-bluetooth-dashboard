package dev.dankyeeter.btdashboard.system.devices

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SR-025 (T-047j point 1): [GlobalSettingsController.clear] used to confirm
 * through [SecureSettingsController.read], which folds [SettingRead.Unreadable]
 * into null — the same value a real clear produces. [confirms] is that
 * decision, isolated so it is checkable without Robolectric or a real phone.
 */
class GlobalSettingsControllerConfirmTest {

    @Test
    fun `a clear is confirmed only by Unset, never by an unreadable read`() {
        assertTrue(confirms(null, SettingRead.Unset))
        assertFalse("SR-025: an unreadable read must not pass as cleared", confirms(null, SettingRead.Unreadable))
        assertFalse(confirms(null, SettingRead.Value("1")))
    }

    @Test
    fun `a write is confirmed only by the same value read back`() {
        assertTrue(confirms("1", SettingRead.Value("1")))
        assertFalse(confirms("1", SettingRead.Value("0")))
        assertFalse(confirms("1", SettingRead.Unreadable))
        assertFalse(confirms("1", SettingRead.Unset))
    }
}
