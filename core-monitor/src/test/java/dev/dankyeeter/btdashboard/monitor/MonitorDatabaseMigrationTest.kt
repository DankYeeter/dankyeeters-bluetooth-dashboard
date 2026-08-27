package dev.dankyeeter.btdashboard.monitor

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.monitor.data.CodecModeSignatureEntity
import dev.dankyeeter.btdashboard.monitor.data.MonitorDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That upgrading to the calibration schema keeps the user's monitor history.
 *
 * ## Why this is hand-rolled rather than `MigrationTestHelper`
 *
 * `androidx.room:room-testing` is not in this project's version catalog, and
 * the guarantee it provides is reachable without it: a version-1 database file
 * with no `room_master_table` sends Room down its pre-packaged-database path,
 * where it runs the registered migrations and then **validates the resulting
 * schema against every entity**, throwing if the two disagree. So opening a
 * hand-built v1 file with the production [MonitorDatabase.create] exercises the
 * migration DDL exactly as a user's phone will, and a `CREATE TABLE` that does
 * not match `CodecModeSignatureEntity` fails this test rather than a device.
 *
 * ## Why it exists at all
 *
 * The database used to carry `fallbackToDestructiveMigration()`, under which
 * this test could not fail: the timeline would simply have been deleted and
 * recreated, silently, on the first launch after the update. That is the
 * mechanism this file is here to keep switched off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MonitorDatabaseMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val address = "xx:xx:xx:xx:ab:cd"

    /**
     * The schema as version 1 shipped it, written out rather than derived.
     *
     * A migration test that generated the old schema from today's classes would
     * only ever prove that today agrees with itself. The indices are included
     * because the whole claim under test is that the migration leaves this
     * untouched, and Room validates them too.
     */
    private val version1Schema = listOf(
        "CREATE TABLE IF NOT EXISTS `monitor_events` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`timestamp_ms` INTEGER NOT NULL, `device_address` TEXT, `device_name` TEXT, " +
            "`type` TEXT NOT NULL, `detail` TEXT NOT NULL, `codec` TEXT, " +
            "`bitrate_kbps` INTEGER)",
        "CREATE INDEX IF NOT EXISTS `index_monitor_events_timestamp_ms` " +
            "ON `monitor_events` (`timestamp_ms`)",
        "CREATE INDEX IF NOT EXISTS `index_monitor_events_device_address` " +
            "ON `monitor_events` (`device_address`)",
        "CREATE TABLE IF NOT EXISTS `link_samples` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`timestamp_ms` INTEGER NOT NULL, `device_address` TEXT NOT NULL, " +
            "`source` TEXT NOT NULL, `rssi_dbm` INTEGER, `codec` TEXT, " +
            "`bitrate_kbps` INTEGER, `sample_rate_hz` INTEGER, " +
            "`is_playing` INTEGER NOT NULL, `retransmissions` INTEGER, " +
            "`dropped_packets` INTEGER, `glitch_count` INTEGER)",
        "CREATE INDEX IF NOT EXISTS `index_link_samples_timestamp_ms` " +
            "ON `link_samples` (`timestamp_ms`)",
        "CREATE INDEX IF NOT EXISTS `index_link_samples_device_address` " +
            "ON `link_samples` (`device_address`)",
    )

    /**
     * A version-1 `monitor.db` holding one event and one sample — the file the
     * production builder will find on a phone that has been running the monitor.
     */
    private fun writeVersion1Database() {
        context.deleteDatabase(DB_NAME)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase
        version1Schema.forEach(db::execSQL)
        db.execSQL(
            "INSERT INTO `monitor_events` " +
                "(`timestamp_ms`, `device_address`, `device_name`, `type`, `detail`, " +
                "`codec`, `bitrate_kbps`) " +
                "VALUES (1000, '$address', 'Headphones', 'CONNECTED', 'connected', " +
                "'LDAC', 660)",
        )
        db.execSQL(
            "INSERT INTO `link_samples` " +
                "(`timestamp_ms`, `device_address`, `source`, `rssi_dbm`, `codec`, " +
                "`bitrate_kbps`, `sample_rate_hz`, `is_playing`, `retransmissions`, " +
                "`dropped_packets`, `glitch_count`) " +
                "VALUES (2000, '$address', 'DUMPSYS', -55, 'LDAC', 660, 96000, 1, 0, 0, 0)",
        )
        db.version = 1
        // Through the helper, so it does not keep a handle on a file Room is
        // about to upgrade.
        helper.close()
    }

    /**
     * The one that matters. If this ever fails by *passing silently* — history
     * gone, no exception — the destructive fallback is back.
     */
    @Test
    fun `upgrading to version 2 keeps the recorded history`() = runTest {
        writeVersion1Database()

        val db = MonitorDatabase.create(context)
        try {
            val events = db.monitorDao().events(0L).first()
            assertEquals(1, events.size)
            assertEquals("connected", events.single().detail)
            assertEquals(660, events.single().bitrateKbps)

            val samples = db.monitorDao().samplesBetween(address, 0L, Long.MAX_VALUE)
            assertEquals(1, samples.size)
            assertEquals(-55, samples.single().rssiDbm)
            assertEquals(96_000, samples.single().sampleRateHz)
        } finally {
            db.close()
        }
    }

    /**
     * The other half: the migrated file is not merely intact, it has the new
     * table and it works. Reaching this line at all already means Room accepted
     * the migration's DDL as matching [CodecModeSignatureEntity] — that check
     * runs during the upgrade and throws on any mismatch.
     */
    @Test
    fun `the migrated database can store a calibration`() = runTest {
        writeVersion1Database()

        val db = MonitorDatabase.create(context)
        try {
            val dao = db.codecModeSignatureDao()
            assertTrue(dao.all().isEmpty())
            dao.upsert(
                CodecModeSignatureEntity(
                    deviceKey = address,
                    codecName = "LDAC",
                    modeRawValue = 1001L,
                    sampleRateHz = 96_000,
                    framesPerPacketMin = 5.5,
                    framesPerPacketMax = 6.5,
                    packetsPerSecondMin = 55.0,
                    packetsPerSecondMax = 70.0,
                    capturedAtMs = 1_234L,
                    createdAtMillis = 9_000L,
                ),
            )
            assertEquals(6.5, dao.all().single().framesPerPacketMax, 0.0)
        } finally {
            db.close()
        }
    }

    private companion object {
        /** The name [MonitorDatabase.create] uses; the test has to target that file. */
        const val DB_NAME = "monitor.db"
    }
}
