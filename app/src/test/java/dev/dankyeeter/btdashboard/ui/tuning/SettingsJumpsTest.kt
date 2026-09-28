package dev.dankyeeter.btdashboard.ui.tuning

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import dev.dankyeeter.btdashboard.monitor.optimize.Fallback
import dev.dankyeeter.btdashboard.monitor.optimize.Measure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** AK-20, AK-T057-6, W3: which entry jumps where, and that only those two pages ever open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsJumpsTest {

    private val app: Application = RuntimeEnvironment.getApplication()

    @Before
    fun resolveOnlyWhatIsRegistered() = shadowOf(app).checkActivities(true)

    @Test
    fun `each of the ten entries has a decided jump, two of them a page`() {
        val table = Measure.entries.associate { it.name to settingsAction(it) } +
            Fallback.entries.associate { it.name to settingsAction(it) }

        assertEquals(10, table.size)
        assertEquals(
            mapOf(
                "NO_2_4_GHZ_WIFI" to "android.settings.WIFI_SETTINGS",
                "NO_SECOND_DEVICE" to "android.settings.BLUETOOTH_SETTINGS",
            ),
            table.filterValues { it != null },
        )
    }

    @Test
    fun `a page nobody answers is reported, not swallowed`() {
        assertFalse(openSettings(app, "android.settings.WIFI_SETTINGS"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `both pages open bare, with nothing handed along`() {
        listOf("android.settings.WIFI_SETTINGS", "android.settings.BLUETOOTH_SETTINGS").forEach { action ->
            register(action)

            assertTrue(action, openSettings(app, action))
            val started = shadowOf(app).nextStartedActivity
            assertEquals(action, started.action)
            assertTrue(action, started.extras?.isEmpty ?: true)
            assertNull(action, started.data)
            assertNull(action, started.clipData)
            assertEquals(action, 0, started.flags and GRANT_FLAGS)
        }
    }

    @Test
    fun `any other action is refused before it is started`() {
        register(Intent.ACTION_VIEW)

        assertFalse(openSettings(app, Intent.ACTION_VIEW))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    private fun register(action: String) {
        val component = ComponentName("com.android.settings", action)
        shadowOf(app.packageManager).apply {
            addActivityIfNotPresent(component)
            addIntentFilterForActivity(component, IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        }
    }

    private companion object {
        const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
