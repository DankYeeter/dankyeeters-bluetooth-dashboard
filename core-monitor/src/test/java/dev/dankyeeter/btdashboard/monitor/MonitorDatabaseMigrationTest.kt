package dev.dankyeeter.btdashboard.monitor

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.monitor.data.CodecModeSignatureEntity
import dev.dankyeeter.btdashboard.monitor.data.MonitorDatabase
import dev.dankyeeter.btdashboard.monitor.data.RoomMonitorRepository
import dev.dankyeeter.btdashboard.monitor.link.live.EffectChainForensics
import dev.dankyeeter.btdashboard.monitor.link.live.EncoderStarvationReport
import dev.dankyeeter.btdashboard.monitor.link.live.SessionEffectCount
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
     * The schema as version 2 shipped it: version 1 plus the calibration table.
     *
     * Copied from `schemas/2.json` rather than generated, for the same reason
     * [version1Schema] is written out — a test that derives the old schema from
     * today's classes proves only that today agrees with itself.
     */
    private val version2Schema = version1Schema + listOf(
        "CREATE TABLE IF NOT EXISTS `codec_mode_signatures` (" +
            "`device_key` TEXT NOT NULL, `codec_name` TEXT NOT NULL, " +
            "`mode_raw_value` INTEGER NOT NULL, `sample_rate_hz` INTEGER NOT NULL, " +
            "`frames_per_packet_min` REAL NOT NULL, `frames_per_packet_max` REAL NOT NULL, " +
            "`packets_per_second_min` REAL NOT NULL, `packets_per_second_max` REAL NOT NULL, " +
            "`captured_at_ms` INTEGER NOT NULL, `created_at_millis` INTEGER NOT NULL, " +
            "PRIMARY KEY(`device_key`, `codec_name`, `mode_raw_value`))",
    )

    /**
     * A version-1 `monitor.db` holding one event and one sample — the file the
     * production builder will find on a phone that has been running the monitor.
     */
    private fun writeVersion1Database() = writeDatabase(1, version1Schema)

    /**
     * A version-2 file, i.e. what a phone that already took the calibration
     * update is carrying. It holds the same history plus one calibration, so
     * the v2 to v3 step has something of both kinds to preserve.
     */
    private fun writeVersion2Database() = writeDatabase(2, version2Schema) { db ->
        db.execSQL(
            "INSERT INTO `codec_mode_signatures` " +
                "(`device_key`, `codec_name`, `mode_raw_value`, `sample_rate_hz`, " +
                "`frames_per_packet_min`, `frames_per_packet_max`, " +
                "`packets_per_second_min`, `packets_per_second_max`, " +
                "`captured_at_ms`, `created_at_millis`) " +
                "VALUES ('$address', 'LDAC', 1000, 96000, 5.5, 6.5, 55.0, 70.0, 1234, 9000)",
        )
    }

    private fun writeDatabase(
        version: Int,
        schema: List<String>,
        seedExtra: (SupportSQLiteDatabase) -> Unit = {},
    ) {
        context.deleteDatabase(DB_NAME)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase
        schema.forEach(db::execSQL)
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
        seedExtra(db)
        db.version = version
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

    // ---- version 2 to 3: the encoder-starvation forensics table --------------

    /**
     * The same guarantee one version on. A phone that already took the
     * calibration update keeps *both* kinds of history across this one.
     *
     * Worth its own test rather than an extension of the v1 case: v1 files exist
     * only on installs that predate the calibration release, while v2 is what
     * most upgrading phones actually carry, so this is the path that will
     * really run.
     */
    @Test
    fun `upgrading to version 3 keeps the history and the calibrations`() = runTest {
        writeVersion2Database()

        val db = MonitorDatabase.create(context)
        try {
            val events = db.monitorDao().events(0L).first()
            assertEquals(1, events.size)
            assertEquals("connected", events.single().detail)

            val samples = db.monitorDao().samplesBetween(address, 0L, Long.MAX_VALUE)
            assertEquals(1, samples.size)
            assertEquals(96_000, samples.single().sampleRateHz)

            val calibrations = db.codecModeSignatureDao().all()
            assertEquals("the calibration table must survive its own successor", 1, calibrations.size)
            assertEquals(1000L, calibrations.single().modeRawValue)
        } finally {
            db.close()
        }
    }

    /**
     * A v1 file skipping straight to 3 — a phone that never took the middle
     * release. Both migrations have to run in order, which is the case a
     * hand-written `MIGRATION_1_3` shortcut would silently break.
     */
    @Test
    fun `a version 1 file upgrades all the way to 3 without losing anything`() = runTest {
        writeVersion1Database()

        val db = MonitorDatabase.create(context)
        try {
            assertEquals(1, db.monitorDao().events(0L).first().size)
            assertTrue(db.codecModeSignatureDao().all().isEmpty())
            assertTrue(db.monitorDao().starvations(0L).isEmpty())
        } finally {
            db.close()
        }
    }

    /**
     * The migrated database can store a capture, and the capture survives the
     * round trip through the three delimited columns.
     *
     * Reaching this line already means Room accepted `MIGRATION_2_3`'s DDL as
     * matching `EncoderStarvationEntity` — that validation runs during the
     * upgrade and throws on any mismatch. What is added here is the encoding:
     * a per-session breakdown that comes back wrong turns the one record of an
     * incident into a record of something else, and it would do so silently.
     */
    @Test
    fun `the migrated database round-trips an encoder-starvation capture`() = runTest {
        writeVersion2Database()

        val db = MonitorDatabase.create(context)
        try {
            val repository = RoomMonitorRepository(db.monitorDao())
            val report = EncoderStarvationReport(
                timestampMs = 5_000L,
                deviceAddress = address,
                deviceName = "Headphones",
                underflowsPerSecond = 49.0,
                windowMs = 2_000L,
                sustainedPasses = 3,
                forensics = EffectChainForensics(
                    effectInstances = 5,
                    sessionsWithEffects = 2,
                    effectsPerSession = listOf(
                        SessionEffectCount(145, 3),
                        SessionEffectCount(0, 2),
                    ),
                    effectNames = listOf("DynamicsProcessing", "Volume"),
                    playbackSessionIds = listOf(8009, 8137),
                    note = null,
                ),
            )
            repository.recordStarvation(report)

            assertEquals(
                "the capture must come back exactly as it went in",
                report,
                repository.starvations(0L).single(),
            )
            // Older than the cutoff: forensics are diagnostic data like the rest
            // of the history and are trimmed on the same schedule.
            repository.purgeOlderThan(10_000L)
            assertTrue(repository.starvations(0L).isEmpty())
        } finally {
            db.close()
        }
    }

    private companion object {
        /** The name [MonitorDatabase.create] uses; the test has to target that file. */
        const val DB_NAME = "monitor.db"
    }
}
