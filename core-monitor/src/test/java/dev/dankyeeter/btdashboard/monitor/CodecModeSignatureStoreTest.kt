package dev.dankyeeter.btdashboard.monitor

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.monitor.data.MonitorDatabase
import dev.dankyeeter.btdashboard.monitor.data.RoomCodecModeSignatureStore
import dev.dankyeeter.btdashboard.monitor.link.live.ModeSignatureSample
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The calibration store against a real SQLite database.
 *
 * What these are actually protecting is the promise the UI is now allowed to
 * make — that pressing Calibrate is worth something *tomorrow*. A calibration
 * costs three audible renegotiations of the user's link, so a store that
 * quietly forgot would be charging that price once per process.
 *
 * Robolectric rather than an instrumented test because this needs nothing from
 * a device: SQLite and a `Context` are all that is under test, and an
 * androidTest would not run in `./gradlew test` where the rest of this module's
 * suite lives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CodecModeSignatureStoreTest {

    private lateinit var db: MonitorDatabase

    private val address = "xx:xx:xx:xx:ab:cd"

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MonitorDatabase::class.java,
        ).build()
    }

    @After
    fun close() = db.close()

    private fun sample(
        mode: Long,
        framesPerPacket: Int,
        codecName: String = "LDAC",
    ) = ModeSignatureSample(
        deviceKey = address,
        codecName = codecName,
        modeRawValue = mode,
        sampleRateHz = 96_000,
        framesPerPacket = (framesPerPacket - 0.5)..(framesPerPacket + 0.5),
        packetsPerSecond = 55.0..70.0,
        capturedAtMs = 1_234L,
    )

    /** A store over the same table, as a second process would see it. */
    private fun store(scope: kotlinx.coroutines.CoroutineScope, now: Long = 9_000L) =
        RoomCodecModeSignatureStore(db.codecModeSignatureDao(), scope) { now }

    @Test
    fun `a band survives the round trip through the table`() = runTest {
        store(this).put(sample(mode = 1001L, framesPerPacket = 6))

        val restored = store(this).signatures(address, "LDAC").single()

        // The band is the whole product of a calibration run; a rounding loss
        // here would silently widen or narrow what the inference matches on.
        assertEquals(5.5, restored.framesPerPacket.start, 0.0)
        assertEquals(6.5, restored.framesPerPacket.endInclusive, 0.0)
        assertEquals(55.0, restored.packetsPerSecond.start, 0.0)
        assertEquals(70.0, restored.packetsPerSecond.endInclusive, 0.0)
        assertEquals(96_000, restored.sampleRateHz)
        assertEquals(1_001L, restored.modeRawValue)
        assertEquals(1_234L, restored.capturedAtMs)
    }

    /**
     * The point of the whole change: a store built fresh — which is what the
     * next process start produces — already knows what the last one learned.
     */
    @Test
    fun `a fresh store hydrates what an earlier one wrote`() = runTest {
        val first = store(this)
        first.put(sample(mode = 1000L, framesPerPacket = 4))
        first.put(sample(mode = 1001L, framesPerPacket = 6))
        first.put(sample(mode = 1002L, framesPerPacket = 12))

        val hydrated = store(this).signatures(address, "LDAC")

        assertEquals(3, hydrated.size)
        assertEquals(
            listOf(1000L, 1001L, 1002L),
            hydrated.map { it.modeRawValue }.sorted(),
        )
    }

    /**
     * Reads must not go near the database. The live panel asks for these on
     * every poll, and the calibration has to be visible on the *next* reading
     * after the run finishes — which is the snapshot's job, not a query's.
     */
    @Test
    fun `a put is visible before the database is consulted again`() = runTest {
        val store = store(this)
        store.put(sample(mode = 1002L, framesPerPacket = 12))

        assertEquals(12.5, store.signatures(address, "LDAC").single().framesPerPacket.endInclusive, 0.0)
        assertEquals(1, db.codecModeSignatureDao().all().size)
    }

    /**
     * A recalibration replaces its predecessor rather than accumulating a
     * second band for the same mode — the in-memory store's rule, here enforced
     * by the primary key.
     */
    @Test
    fun `recalibrating a mode replaces the stored band`() = runTest {
        val first = store(this)
        first.put(sample(mode = 1001L, framesPerPacket = 6))
        first.put(sample(mode = 1001L, framesPerPacket = 7))

        val rows = db.codecModeSignatureDao().all()
        assertEquals(1, rows.size)
        assertEquals(7.5, rows.single().framesPerPacketMax, 0.0)
        assertEquals(6.5, rows.single().framesPerPacketMin, 0.0)
    }

    @Test
    fun `clear empties the table as well as the snapshot`() = runTest {
        val store = store(this)
        store.put(sample(mode = 1000L, framesPerPacket = 4))
        store.put(sample(mode = 1001L, framesPerPacket = 6))

        store.clear(address, "LDAC")

        assertTrue(store.signatures(address, "LDAC").isEmpty())
        assertTrue(db.codecModeSignatureDao().all().isEmpty())
        // And it stays cleared for the next process, which is the half a purely
        // in-memory clear could not get wrong.
        assertTrue(store(this).signatures(address, "LDAC").isEmpty())
    }

    /**
     * The codec name arrives from whichever dump printed it, so its case is not
     * something a caller can rely on. SQLite compares TEXT byte-for-byte, which
     * is why the store folds the key rather than trusting the column.
     */
    @Test
    fun `codec name case does not split one mode into two rows`() = runTest {
        val store = store(this)
        store.put(sample(mode = 1001L, framesPerPacket = 6, codecName = "LDAC"))
        store.put(sample(mode = 1001L, framesPerPacket = 8, codecName = "ldac"))

        assertEquals(1, db.codecModeSignatureDao().all().size)
        assertEquals(1, store(this).signatures(address, "ldac").size)
        assertEquals(1, store(this).signatures(address, "LDAC").size)
    }

    /** Another device's calibration is not this device's. */
    @Test
    fun `signatures are scoped to the device that produced them`() = runTest {
        val store = store(this)
        store.put(sample(mode = 1001L, framesPerPacket = 6))
        store.put(sample(mode = 1001L, framesPerPacket = 9).copy(deviceKey = "other"))

        assertEquals(6.5, store(this).signatures(address, "LDAC").single().framesPerPacket.endInclusive, 0.0)
        assertEquals(2, db.codecModeSignatureDao().all().size)
    }

    /**
     * `capturedAtMs` comes from the calibrator's injected clock and is pinned to
     * a constant by its tests, so it cannot order two runs. The write time can.
     */
    @Test
    fun `the row records when it was written, separately from when it was measured`() = runTest {
        store(this, now = 77_000L).put(sample(mode = 1001L, framesPerPacket = 6))

        val row = db.codecModeSignatureDao().all().single()
        assertEquals(77_000L, row.createdAtMillis)
        assertEquals(1_234L, row.capturedAtMs)
    }
}
