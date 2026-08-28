package dev.dankyeeter.btdashboard.ui.tuning

import androidx.test.core.app.ApplicationProvider
import android.app.Application
import dev.dankyeeter.btdashboard.system.devices.CodecPreference
import dev.dankyeeter.btdashboard.system.devices.DeviceKey
import dev.dankyeeter.btdashboard.system.devices.DeviceProfile
import dev.dankyeeter.btdashboard.system.devices.DeviceProfileStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bitrate really survives the app being closed.
 *
 * `LdacQualityTest` proves the in-memory round trip: a tap becomes a stored
 * wish and reading it back lights the same chip. This one goes through the thing
 * that actually holds it — [DeviceProfileStore]'s JSON in DataStore — because
 * that encoder is hand-written, and a field that is set on the way out and
 * dropped on the way in would pass every pure test in the suite and lose the
 * user's choice on every restart.
 *
 * There is no backup assertion here on purpose: device profiles do not travel in
 * the app's backup document, which carries hearing runs, the audiogram and
 * compensation profiles. This JSON is the whole persistence contract for the
 * field, so the defaulting rules are asserted against it instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LdacQualityPersistenceTest {

    private val key = DeviceKey.fromAddress("AC:DE:48:00:37:8F")!!

    private fun store() =
        DeviceProfileStore(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun `a pinned rate survives a reload`() = runTest {
        val chosen = LdacQuality.withQuality(
            DeviceProfile(deviceKey = key, name = "Bathys"),
            LdacQuality.HIGH_QUALITY,
        )
        store().save(chosen)

        // A second store over the same DataStore file — the closest a unit test
        // gets to the app being started again.
        val reloaded = store().profileFor(key)

        assertEquals("LDAC", reloaded?.codecPreference?.codec)
        assertEquals(1000L, reloaded?.codecPreference?.ldacQuality)
        assertEquals(LdacQuality.HIGH_QUALITY, LdacQuality.storedQuality(reloaded))
        // And the chip that comes back up is the one that went down.
        assertEquals(LdacQuality.HIGH_QUALITY, LdacQuality.selected(LdacQuality.storedQuality(reloaded)))
    }

    /**
     * Withdrawing the pin has to persist too, and as a *zero* rather than as a
     * missing codec wish: the profile still asks for LDAC, it just stops forcing
     * a rate.
     */
    @Test
    fun `withdrawing a pin survives a reload`() = runTest {
        val pinned = LdacQuality.withQuality(
            DeviceProfile(deviceKey = key, name = "Bathys"),
            LdacQuality.STANDARD,
        )
        store().save(pinned)
        store().save(LdacQuality.withQuality(store().profileFor(key)!!, LdacQuality.ADAPTIVE))

        val reloaded = store().profileFor(key)

        assertEquals("LDAC", reloaded?.codecPreference?.codec)
        assertEquals(0L, reloaded?.codecPreference?.ldacQuality)
        assertEquals(LdacQuality.ADAPTIVE, LdacQuality.selected(LdacQuality.storedQuality(reloaded)))
    }

    /**
     * A profile written before this feature existed carries no codec wish at
     * all, and must come back as "no preference" rather than as a zeroed one —
     * the two mean different things to the applier: leave the stack alone
     * versus renegotiate it.
     */
    @Test
    fun `a profile stored without a codec wish reads back as no preference`() = runTest {
        val plain = DeviceProfile(deviceKey = key, name = "Bathys", mediaVolumePercent = 40)
        store().save(plain)

        val reloaded = store().profileFor(key)

        assertNull(reloaded?.codecPreference)
        assertEquals(LdacQuality.NONE, LdacQuality.storedQuality(reloaded))
        assertEquals(40, reloaded?.mediaVolumePercent)
    }

    /**
     * Restored data can carry a rate this build would never write. Sanitising
     * drops the whole wish rather than repairing it, because a half-trusted
     * codec request is a renegotiation nobody asked for.
     */
    @Test
    fun `an impossible rate does not come back out of the store`() = runTest {
        store().save(
            DeviceProfile(
                deviceKey = key,
                name = "Bathys",
                codecPreference = CodecPreference(codec = "LDAC", ldacQuality = 1500L),
            ),
        )

        assertNull(store().profileFor(key)?.codecPreference)
    }
}
