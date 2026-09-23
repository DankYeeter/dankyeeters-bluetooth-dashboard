package dev.dankyeeter.btdashboard.ui.screens.devices

import android.Manifest
import android.app.Application
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.devices.LedgerEntry
import dev.dankyeeter.btdashboard.ui.screens.bluetooth.BluetoothScreen
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import dev.dankyeeter.btdashboard.ui.tuning.RestoreBanner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private const val HEAD = "Settings changed by the process are still active."
private const val SHOW_NAMELESS = "bluetooth_show_devices_without_names"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsRestoreBannerTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var confirmed = 0

    private fun showBanner(banner: RestoreBanner) {
        composeRule.setContent {
            BtDashboardTheme {
                SettingsRestoreBannerContent(
                    banner = banner,
                    report = null,
                    restoring = false,
                    onConfirm = { confirmed++ },
                    onReportRead = {},
                )
            }
        }
    }

    @After
    fun forgetSeededEntries() = runBlocking {
        SystemGraph.settingsLedger.entries().getOrThrow().forEach { SystemGraph.settingsLedger.remove(it) }
    }

    @Test
    fun `the confirmation is worded as specified and Cancel puts nothing back (AK-T047-3)`() {
        showBanner(RestoreBanner.Open(2, tryAgain = false))

        composeRule.onNodeWithText("Back to before").performClick()
        composeRule.onNodeWithText("Put settings back to before?").assertExists()
        composeRule.onNodeWithText(
            "This puts back every setting this app has changed and not restored yet — global options, " +
                "HD audio, and codec preference. Profiles that made any of these changes stop applying " +
                "automatically until you switch them back on.",
        ).assertExists()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("Put settings back to before?").assertDoesNotExist()
        assertEquals(0, confirmed)
    }

    @Test
    fun `confirming starts the way back once`() {
        showBanner(RestoreBanner.Open(2, tryAgain = false))

        composeRule.onNodeWithText("Back to before").performClick()
        // The dialog's own button carries the same words as the banner's.
        composeRule.onNodeWithText("Cancel").assertExists()
        composeRule.onAllNodes(hasText("Back to before"))[1].performClick()

        assertEquals(1, confirmed)
    }

    @Test
    fun `entries left behind keep the banner with a new count and Try again (AK-T047-5)`() {
        showBanner(RestoreBanner.Open(1, tryAgain = true))

        composeRule.onNodeWithText(HEAD).assertExists()
        composeRule.onNodeWithText("1 setting not put back yet.").assertExists()
        composeRule.onNodeWithText("Try again").assertExists()
    }

    @Test
    fun `with nothing held the banner is gone entirely (AK-T047-5)`() {
        showBanner(RestoreBanner.Hidden)

        composeRule.onNodeWithText(HEAD).assertDoesNotExist()
    }

    @Test
    fun `on the Bluetooth tab the banner stands above the codec section (AK-T047-2)`() {
        runBlocking { SystemGraph.settingsLedger.recordIfAbsent(LedgerEntry.Global(SHOW_NAMELESS, prior = null)) }

        composeRule.setContent { BtDashboardTheme { BluetoothScreen() } }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText(HEAD)).fetchSemanticsNodes().isNotEmpty()
        }

        val banner = composeRule.onNodeWithText(HEAD).fetchSemanticsNode().boundsInRoot.top
        val codecSection = composeRule.onNodeWithText("Bluetooth audio", ignoreCase = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue("banner at $banner, codec section at $codecSection", banner < codecSection)
    }

    @Test
    fun `a global set now is held before it is written (M15)`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Global.putString(app.contentResolver, SHOW_NAMELESS, null)

        DeviceProfilesViewModel(app).setGlobalNow(SHOW_NAMELESS, "1")

        // D-001: the ledger's edit resumes on the main looper; wait it out.
        var waited = 0
        while (Settings.Global.getString(app.contentResolver, SHOW_NAMELESS) == null && waited++ < 100) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }

        val held = runBlocking { SystemGraph.settingsLedger.entries().getOrThrow() }
        assertEquals(listOf(LedgerEntry.Global(SHOW_NAMELESS, prior = null)), held)
        assertEquals("1", Settings.Global.getString(app.contentResolver, SHOW_NAMELESS))
        Settings.Global.putString(app.contentResolver, SHOW_NAMELESS, null)
    }
}
