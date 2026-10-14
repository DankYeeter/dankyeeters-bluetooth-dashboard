package dev.dankyeeter.btdashboard.system.devices

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Looper
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.privateDataStore
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
private val KEY = DeviceKey.fromAddress(ADDRESS)!!

/** Robolectric for the real receiver, `DeviceProfileStore` and log (B7, neighbour of SR-030). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceConnectionWatcherLogTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val profiles = DeviceProfileStore(context)

    @Test
    fun `a nameless device never puts its address in the log`() {
        val logged = logOfNamelessConnect(profiles)

        assertTrue("both lines ran: $logged", logged.contains("ACL connect") && logged.contains("auto-apply for"))
        assertFalse(logged, logged.contains(ADDRESS))
        assertFalse("the key can be reversed from a known vendor prefix", logged.contains(KEY))
        assertTrue(logged, logged.contains("unnamed device"))
    }

    /** V12: an exception's message can quote the device; its class cannot. */
    @Test
    fun `a failed auto-apply logs the exception class, not the exception`() {
        val throwing = object : DeviceProfileSource {
            override suspend fun profileFor(deviceKey: String): DeviceProfile? =
                throw IllegalStateException("no profile for $deviceKey at $ADDRESS")
        }

        val logged = logOfNamelessConnect(throwing)

        assertTrue(logged, logged.contains("profile auto-apply failed") && logged.contains("IllegalStateException"))
        assertFalse(logged, logged.contains(ADDRESS))
        assertFalse(logged, logged.contains(KEY))
    }

    /** V12: org.json quotes the text it could not parse, and stored profiles carry device keys and names. */
    @Test
    fun `an unreadable profile store keeps its content out of the log`() = runBlocking {
        val raw = privateDataStore(context, "dev.dankyeeter.btdashboard.system.devices.DeviceProfileStoreKt", "deviceProfileDataStore")
        raw.edit { it[stringPreferencesKey("device_profiles_json")] = """[{"deviceKey":"$KEY","name":"$ADDRESS"""" }
        try {
            ShadowLog.clear()

            assertTrue(profiles.current().isEmpty())

            val logged = ShadowLog.getLogsForTag("DeviceProfileStore").joinToString("\n") { "${it.msg} ${it.throwable}" }
            assertTrue(logged, logged.contains("JSONException"))
            assertFalse(logged, logged.contains(ADDRESS))
            assertFalse(logged, logged.contains(KEY))
        } finally {
            raw.edit { it.clear() }
        }
    }

    /** Every log line of the watcher for one nameless connect, messages and throwables. */
    private fun logOfNamelessConnect(applierProfiles: DeviceProfileSource): String = runBlocking {
        val gate = SecureSettingsGate(context)
        val work = Job()
        val watcher = DeviceConnectionWatcher(
            context = context,
            store = profiles,
            applier = DeviceProfileApplier(
                profiles = applierProfiles,
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

        ShadowLog.getLogsForTag("DeviceConnectionWatcher").joinToString("\n") { "${it.msg} ${it.throwable}" }
    }
}
