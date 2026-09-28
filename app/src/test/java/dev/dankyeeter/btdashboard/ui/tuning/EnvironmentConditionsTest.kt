package dev.dankyeeter.btdashboard.ui.tuning

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.PairingFacts
import dev.dankyeeter.btdashboard.monitor.optimize.Condition
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowWifiInfo

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

    // -- bandValue (AD-046) --

    @Test
    fun `Wi-Fi off reads as off, whatever the frequency`() {
        assertEquals(ConditionValue.Read("off"), bandValue(ConditionValue.Read("off"), null, null))
    }

    @Test
    fun `a frequency is read as its band, at the band edges too`() {
        val on = ConditionValue.Read("on")
        mapOf(2437 to "2.4 GHz", 2484 to "2.4 GHz", 5180 to "5 GHz", 5935 to "6 GHz", 5955 to "6 GHz").forEach { (mhz, band) ->
            assertEquals("$mhz MHz", ConditionValue.Read(band), bandValue(on, mhz, null))
        }
        assertEquals(ConditionValue.Unreadable("2485 MHz in no known band"), bandValue(on, 2485, null))
    }

    @Test
    fun `no Wi-Fi as the active network, a failed read or an unread radio is unreadable, never a band`() {
        val on = ConditionValue.Read("on")
        assertTrue(bandValue(on, null, null) is ConditionValue.Unreadable)
        assertEquals(ConditionValue.Unreadable("x"), bandValue(on, null, SecurityException("x")))
        assertTrue(bandValue(ConditionValue.Unreadable("not set on this phone"), 2437, null) is ConditionValue.Unreadable)
    }

    // -- readConditions: WIFI_BAND off the active network's frequency --

    @Test
    fun `the active Wi-Fi network's frequency is read as its band`() {
        Settings.Global.putString(context.contentResolver, "wifi_on", "1")
        val wifi = ShadowWifiInfo.newInstance().also { shadowOf(it).setFrequency(2437) }
        val capabilities = ShadowNetworkCapabilities.newInstance().also { shadowOf(it).setTransportInfo(wifi) }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities)

        val book = readConditions(context, snapshot(), discoverySeen = null)

        assertEquals(ConditionValue.Read("2.4 GHz"), book.getValue(Condition.WIFI_BAND))
    }

    /** W6 (T-058s): checked against the merged manifest, the band needs no location or Wi-Fi permission. */
    @Test
    fun `the app asks for network state and for no location or Wi-Fi permission`() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()

        assertTrue("merged manifest", "android.permission.BLUETOOTH_SCAN" in requested)
        assertTrue("android.permission.ACCESS_NETWORK_STATE" in requested)
        listOf(
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "NEARBY_WIFI_DEVICES",
            "ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE", "CHANGE_NETWORK_STATE", "LOCAL_MAC_ADDRESS",
        ).forEach { assertFalse(it, "android.permission.$it" in requested) }
    }
}
