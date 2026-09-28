package dev.dankyeeter.btdashboard.system.devices

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.system.secure.SecureSettingsGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

private const val ADDRESS = "AC:DE:48:00:37:8F"

/** Robolectric for the real receiver, `DeviceProfileStore` and log (B7, neighbour of SR-030). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceConnectionWatcherLogTest {

    @Test
    fun `a nameless device never puts its address in the log`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val profiles = DeviceProfileStore(context)
        val gate = SecureSettingsGate(context)
        val work = Job()
        val watcher = DeviceConnectionWatcher(
            context = context,
            store = profiles,
            applier = DeviceProfileApplier(
                profiles = profiles,
                volume = SystemMediaVolumeController(context),
                compensation = object : CompensationApplier {
                    override suspend fun apply(compensationProfileId: String) = false
                },
                absoluteVolume = AbsoluteVolumeGate(context, gate),
                secureSettings = GlobalSettingsController(context, gate),
            ),
            scope = CoroutineScope(work + Dispatchers.IO),
        )
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(ADDRESS)
        ShadowLog.clear()

        watcher.start()
        context.sendBroadcast(Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, device))
        shadowOf(Looper.getMainLooper()).idle()
        work.children.toList().joinAll()
        watcher.stop()

        val logged = ShadowLog.getLogsForTag("DeviceConnectionWatcher").joinToString("\n") { it.msg }
        assertTrue("both lines ran: $logged", logged.contains("ACL connect") && logged.contains("auto-apply for"))
        assertFalse(logged, logged.contains(ADDRESS))
    }
}
