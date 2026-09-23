package dev.dankyeeter.btdashboard.ui.tuning

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.PairingFacts
import dev.dankyeeter.btdashboard.monitor.optimize.Condition
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S3-4's literal cases (AD-035, T-047g): `settingValue`/`usbValue` as the pure
 * functions the interface names, and [readConditions] for the two facts that
 * only exist by reading around it — pairing facts and the discovery flag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EnvironmentConditionsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun snapshot(pairing: PairingFacts? = null) = LinkLiveSnapshot(timestampMs = 0L, pairing = pairing)

    // -- settingValue --

    @Test
    fun `a set setting reads as on or off`() {
        assertEquals(ConditionValue.Read("on"), settingValue("1", null))
        assertEquals(ConditionValue.Read("off"), settingValue("0", null))
    }

    @Test
    fun `an unset setting is unreadable, never assumed off`() {
        assertTrue(settingValue(null, null) is ConditionValue.Unreadable)
    }

    @Test
    fun `a thrown read is unreadable with its own message`() {
        val value = settingValue(null, IllegalStateException("no such provider"))
        assertEquals("no such provider", (value as ConditionValue.Unreadable).reason)
    }

    // -- usbValue --

    @Test
    fun `usb power reads as usb`() {
        assertEquals(ConditionValue.Read("usb"), usbValue(BatteryManager.BATTERY_PLUGGED_USB))
    }

    @Test
    fun `no plug reads as none`() {
        assertEquals(ConditionValue.Read("none"), usbValue(0))
    }

    @Test
    fun `a source other than usb still counts as present, not as the cable`() {
        assertEquals(ConditionValue.Read("other"), usbValue(BatteryManager.BATTERY_PLUGGED_AC))
    }

    @Test
    fun `an unread battery state is unreadable`() {
        assertTrue(usbValue(null) is ConditionValue.Unreadable)
    }

    // -- readConditions: OTHER_ACL_LINKS --

    @Test
    fun `missing pairing facts are unreadable, never read as zero other links`() {
        val book = readConditions(context, snapshot(pairing = null), discoverySeen = null)

        assertTrue(book.getValue(Condition.OTHER_ACL_LINKS) is ConditionValue.Unreadable)
    }

    @Test
    fun `a read other-link count is carried through`() {
        val book = readConditions(context, snapshot(PairingFacts(otherAclLinks = 2)), discoverySeen = null)

        assertEquals(ConditionValue.Read("2"), book.getValue(Condition.OTHER_ACL_LINKS))
    }

    // -- readConditions: DISCOVERY_SEEN --

    @Test
    fun `discovery seen in the arm reads as yes`() {
        val book = readConditions(context, snapshot(), discoverySeen = true)

        assertEquals(ConditionValue.Read("yes"), book.getValue(Condition.DISCOVERY_SEEN))
    }

    @Test
    fun `discovery never seen reads as no`() {
        val book = readConditions(context, snapshot(), discoverySeen = false)

        assertEquals(ConditionValue.Read("no"), book.getValue(Condition.DISCOVERY_SEEN))
    }

    // -- readConditions: WIFI_RADIO off Settings.Global --

    @Test
    fun `an unset wifi radio setting is unreadable`() {
        val book = readConditions(context, snapshot(), discoverySeen = null)

        assertTrue(book.getValue(Condition.WIFI_RADIO) is ConditionValue.Unreadable)
    }

    @Test
    fun `a set wifi radio setting is read`() {
        Settings.Global.putString(context.contentResolver, "wifi_on", "1")

        val book = readConditions(context, snapshot(), discoverySeen = null)

        assertEquals(ConditionValue.Read("on"), book.getValue(Condition.WIFI_RADIO))
    }

    // -- readConditions: USB_POWER off the battery sticky intent --

    @Test
    fun `no battery broadcast yet is unreadable usb power`() {
        val book = readConditions(context, snapshot(), discoverySeen = null)

        assertTrue(book.getValue(Condition.USB_POWER) is ConditionValue.Unreadable)
    }

    @Test
    fun `a usb charging broadcast is read as usb power`() {
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB),
        )

        val book = readConditions(context, snapshot(), discoverySeen = null)

        assertEquals(ConditionValue.Read("usb"), book.getValue(Condition.USB_POWER))
    }
}
