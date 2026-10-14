package dev.dankyeeter.btdashboard

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.monitor.effects.VendorEqApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * AK-22 / AD-050: the one build is the store build, so the merged manifest must
 * not ask for QUERY_ALL_PACKAGES or BLUETOOTH_PRIVILEGED, and the equaliser
 * detection must still see every app it knows by name through `<queries>`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StoreManifestTest {

    /**
     * The whole set as a literal, not just "those two are gone": a permission
     * that arrives through a library or a module manifest shows up here first.
     */
    @Test
    fun `the merged manifest requests exactly the store permission set`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSortedSet()

        assertEquals(
            sortedSetOf(
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
                "android.permission.INTERNET",
                "android.permission.MODIFY_AUDIO_SETTINGS",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.RECORD_AUDIO",
                "android.permission.WRITE_SECURE_SETTINGS",
                "dev.dankyeeter.btdashboard.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ),
            requested,
        )
    }

    /** Without QUERY_ALL_PACKAGES a vendor app missing from `<queries>` is invisible — a silent all-clear. */
    @Test
    fun `queries names every known vendor app and the equaliser panel intent`() {
        val queries = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
            .getElementsByTagName("queries").item(0) as org.w3c.dom.Element
        val packages = queries.getElementsByTagName("package").let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttribute("android:name") }
        }.toSet()
        val intentActions = queries.getElementsByTagName("action").let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttribute("android:name") }
        }

        assertEquals(12, VendorEqApps.known.size)
        assertEquals(VendorEqApps.known.map { it.packageName }.toSet(), packages)
        assertTrue("android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL" in intentActions)
    }
}
