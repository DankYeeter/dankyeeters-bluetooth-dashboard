package dev.dankyeeter.btdashboard.monitor.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
import dev.dankyeeter.btdashboard.monitor.link.live.CodecModeSignatureStore
import dev.dankyeeter.btdashboard.monitor.link.live.EffectChainForensics
import dev.dankyeeter.btdashboard.monitor.link.live.EncoderStarvationReport
import dev.dankyeeter.btdashboard.monitor.link.live.ModeSignatureSample
import dev.dankyeeter.btdashboard.monitor.link.live.SessionEffectCount
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "monitor_events")
data class MonitorEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp_ms", index = true) val timestampMs: Long,
    @ColumnInfo(name = "device_address", index = true) val deviceAddress: String?,
    @ColumnInfo(name = "device_name") val deviceName: String?,
    val type: String,
    val detail: String,
    val codec: String?,
    @ColumnInfo(name = "bitrate_kbps") val bitrateKbps: Int?,
)

@Entity(tableName = "link_samples")
data class LinkSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp_ms", index = true) val timestampMs: Long,
    @ColumnInfo(name = "device_address", index = true) val deviceAddress: String,
    val source: String,
    @ColumnInfo(name = "rssi_dbm") val rssiDbm: Int?,
    val codec: String?,
    @ColumnInfo(name = "bitrate_kbps") val bitrateKbps: Int?,
    @ColumnInfo(name = "sample_rate_hz") val sampleRateHz: Int?,
    @ColumnInfo(name = "is_playing") val isPlaying: Boolean,
    val retransmissions: Int?,
    @ColumnInfo(name = "dropped_packets") val droppedPackets: Int?,
    @ColumnInfo(name = "glitch_count") val glitchCount: Int?,
)

/**
 * One bitrate-mode signature measured by `CodecModeCalibrator` on one link.
 *
 * The primary key is the same triple the store keys on — device, codec name,
 * mode value — so a recalibration *replaces* its predecessor instead of leaving
 * two bands that disagree about the same mode. That is the in-memory store's
 * documented rule, expressed here as a constraint the database enforces.
 *
 * [ModeSignatureSample]'s two ranges are spread over four `REAL` columns rather
 * than encoded into one. A band in a blob cannot be compared in SQL, and — the
 * reason that actually matters — cannot be read in a `sqlite3` dump when a user
 * reports the panel naming the wrong mode.
 *
 * The codec name is stored **uppercased**. SQLite compares `TEXT` keys
 * byte-for-byte while [CodecModeSignatureStore]'s lookup has always been
 * case-insensitive, and uppercase is the form this module already folds codec
 * names to when matching them against providers.
 */
@Entity(
    tableName = "codec_mode_signatures",
    primaryKeys = ["device_key", "codec_name", "mode_raw_value"],
)
data class CodecModeSignatureEntity(
    @ColumnInfo(name = "device_key") val deviceKey: String,
    @ColumnInfo(name = "codec_name") val codecName: String,
    @ColumnInfo(name = "mode_raw_value") val modeRawValue: Long,
    @ColumnInfo(name = "sample_rate_hz") val sampleRateHz: Int,
    @ColumnInfo(name = "frames_per_packet_min") val framesPerPacketMin: Double,
    @ColumnInfo(name = "frames_per_packet_max") val framesPerPacketMax: Double,
    @ColumnInfo(name = "packets_per_second_min") val packetsPerSecondMin: Double,
    @ColumnInfo(name = "packets_per_second_max") val packetsPerSecondMax: Double,
    /** MEASURED: when the calibrator took the reading, on the clock it was given. */
    @ColumnInfo(name = "captured_at_ms") val capturedAtMs: Long,
    /**
     * When the row was written, on the wall clock.
     *
     * Deliberately not the same field as [capturedAtMs]: that one comes from a
     * clock the caller injects — the calibration tests pin it to zero — so it
     * cannot be trusted to order two runs or to age a stale band out later.
     */
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
)

/**
 * One tripped encoder-starvation capture, flattened for storage.
 *
 * ## Why the shape is this shape
 *
 * The point of persisting these is that somebody — possibly months later,
 * possibly with nothing but `sqlite3 monitor.db` — can answer one question
 * about an incident that has already ended: *how many effect instances were
 * attached, and to how many sessions.* So the two numbers that answer it are
 * their own integer columns, comparable and aggregatable in SQL, rather than
 * fields inside a blob.
 *
 * [effectsPerSession], [effectNames] and [playbackSessionIds] are the long tail
 * and are stored as short delimited text. That is a deliberate denormalisation:
 * three child tables would make a join out of something whose whole purpose is
 * to be readable in a dump, and these lists are a handful of entries each. The
 * formats are fixed and parsed back by [EncoderStarvationEntity.toReport] —
 * `145:3,0:2` for the breakdown, `|`-separated for names, `,`-separated for ids.
 *
 * No foreign key to `monitor_events`. The event and the record are written by
 * two different paths that can each fail on their own, and a capture that
 * survives while its timeline line did not is still the useful half.
 */
@Entity(tableName = "encoder_starvation_events")
data class EncoderStarvationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp_ms", index = true) val timestampMs: Long,
    @ColumnInfo(name = "device_address", index = true) val deviceAddress: String?,
    @ColumnInfo(name = "device_name") val deviceName: String?,
    /** DERIVED: the rate that tripped the wire. See `A2dpTxDelta.underflowsPerSecond`. */
    @ColumnInfo(name = "underflows_per_second") val underflowsPerSecond: Double,
    /** MEASURED: the polling window the rate was derived over. */
    @ColumnInfo(name = "window_ms") val windowMs: Long,
    @ColumnInfo(name = "sustained_passes") val sustainedPasses: Int,
    @ColumnInfo(name = "effect_instances") val effectInstances: Int,
    @ColumnInfo(name = "effect_sessions") val effectSessions: Int,
    /** `<session>:<count>` pairs, comma separated, heaviest session first. */
    @ColumnInfo(name = "effects_per_session") val effectsPerSession: String,
    /** Distinct effect names, `|` separated — names may contain commas. */
    @ColumnInfo(name = "effect_names") val effectNames: String,
    /** Session ids that were `state:started`, comma separated. */
    @ColumnInfo(name = "playback_session_ids") val playbackSessionIds: String,
    /** Why a section could not be read, verbatim. Null when both parsed. */
    @ColumnInfo(name = "capture_note") val captureNote: String?,
)

@Dao
interface MonitorDao {
    @Insert suspend fun insertEvent(event: MonitorEventEntity): Long

    @Insert suspend fun insertSample(sample: LinkSampleEntity): Long

    @Query("SELECT * FROM monitor_events WHERE timestamp_ms >= :sinceMs ORDER BY timestamp_ms ASC")
    fun events(sinceMs: Long): Flow<List<MonitorEventEntity>>

    @Query("SELECT * FROM link_samples WHERE timestamp_ms >= :sinceMs ORDER BY timestamp_ms ASC")
    fun samples(sinceMs: Long): Flow<List<LinkSampleEntity>>

    @Query(
        "SELECT * FROM link_samples WHERE device_address = :address " +
            "ORDER BY timestamp_ms DESC LIMIT 1",
    )
    suspend fun latestSample(address: String): LinkSampleEntity?

    @Query(
        "SELECT * FROM link_samples WHERE device_address = :address " +
            "AND timestamp_ms BETWEEN :fromMs AND :toMs ORDER BY timestamp_ms ASC",
    )
    suspend fun samplesBetween(address: String, fromMs: Long, toMs: Long): List<LinkSampleEntity>

    @Query("DELETE FROM monitor_events WHERE timestamp_ms < :cutoffMs")
    suspend fun purgeEvents(cutoffMs: Long)

    @Query("DELETE FROM link_samples WHERE timestamp_ms < :cutoffMs")
    suspend fun purgeSamples(cutoffMs: Long)

    @Insert suspend fun insertStarvation(record: EncoderStarvationEntity): Long

    /**
     * Newest first, and a plain suspend read rather than a [Flow].
     *
     * These are rare by construction — the tripwire's cooldown caps them at one
     * per ten minutes of continuous starvation — so nothing needs to observe
     * them continuously. They are looked at after the fact, which is a query.
     */
    @Query(
        "SELECT * FROM encoder_starvation_events WHERE timestamp_ms >= :sinceMs " +
            "ORDER BY timestamp_ms DESC",
    )
    suspend fun starvations(sinceMs: Long): List<EncoderStarvationEntity>

    @Query("DELETE FROM encoder_starvation_events WHERE timestamp_ms < :cutoffMs")
    suspend fun purgeStarvations(cutoffMs: Long)
}

@Dao
interface CodecModeSignatureDao {

    /**
     * Every row, for the one-shot hydration of the in-memory snapshot.
     *
     * Not filtered by device on purpose: the table holds one row per
     * (device, codec, mode) anyone ever calibrated — a handful, bounded by the
     * paired-device list — so a single read at startup is cheaper than teaching
     * the live path to query per poll.
     */
    @Query("SELECT * FROM codec_mode_signatures")
    suspend fun all(): List<CodecModeSignatureEntity>

    /** REPLACE, because recalibrating a mode supersedes the earlier band. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(signature: CodecModeSignatureEntity)

    @Query(
        "DELETE FROM codec_mode_signatures WHERE device_key = :deviceKey " +
            "AND codec_name = :codecName",
    )
    suspend fun clear(deviceKey: String, codecName: String)
}

/**
 * The monitor's history plus the calibrations learned from it.
 *
 * ## Why `exportSchema` is on and destructive fallback is gone
 *
 * This database used to be built with `fallbackToDestructiveMigration()`, which
 * turns *any* schema change into a silent wipe of the user's recorded timeline
 * — a bug waiting for whoever next adds a column, and one that leaves no trace
 * when it fires. It is removed rather than worked around: from here every
 * version bump must ship a [Migration] or the open fails loudly.
 *
 * Schema export is the other half of the same decision. Writing migration N→N+1
 * correctly requires knowing what N actually looked like, and with export off
 * the only record of that is whatever the entity classes happened to say at the
 * time. The cost is a checked-in `schemas/` directory; the alternative is
 * discovering a mismatch on a user's device, after the fact. Version 1 predates
 * this switch and therefore has no exported JSON — 2 onwards do.
 */
@Database(
    entities = [
        MonitorEventEntity::class,
        LinkSampleEntity::class,
        CodecModeSignatureEntity::class,
        EncoderStarvationEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class MonitorDatabase : RoomDatabase() {
    abstract fun monitorDao(): MonitorDao

    abstract fun codecModeSignatureDao(): CodecModeSignatureDao

    companion object {

        /**
         * Adds the calibration table and touches nothing else.
         *
         * `monitor_events` and `link_samples` are deliberately absent from this
         * statement: the point of the migration is that the history already in
         * them survives untouched. The DDL is written to match exactly what
         * Room generates for [CodecModeSignatureEntity], because Room validates
         * the two against each other on the next open and refuses to run on a
         * mismatch — `MonitorDatabaseMigrationTest` pins that agreement.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `codec_mode_signatures` (" +
                        "`device_key` TEXT NOT NULL, " +
                        "`codec_name` TEXT NOT NULL, " +
                        "`mode_raw_value` INTEGER NOT NULL, " +
                        "`sample_rate_hz` INTEGER NOT NULL, " +
                        "`frames_per_packet_min` REAL NOT NULL, " +
                        "`frames_per_packet_max` REAL NOT NULL, " +
                        "`packets_per_second_min` REAL NOT NULL, " +
                        "`packets_per_second_max` REAL NOT NULL, " +
                        "`captured_at_ms` INTEGER NOT NULL, " +
                        "`created_at_millis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`device_key`, `codec_name`, `mode_raw_value`))",
                )
            }
        }

        /**
         * Adds the encoder-starvation forensics table and touches nothing else.
         *
         * Written to the same rule as [MIGRATION_1_2], and for the same reason:
         * the three existing tables do not appear in this statement, because the
         * point of the migration is that everything already recorded survives.
         * Room validates the resulting schema against every entity on the next
         * open and refuses to run on a mismatch, so a typo here fails
         * `MonitorDatabaseMigrationTest` rather than a user's phone.
         *
         * The two indices are part of the entity and therefore part of the DDL.
         * Leaving them out of a migration is the classic way to produce a
         * database that Room rejects at open time on the one device that
         * upgraded rather than installed fresh.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `encoder_starvation_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestamp_ms` INTEGER NOT NULL, " +
                        "`device_address` TEXT, " +
                        "`device_name` TEXT, " +
                        "`underflows_per_second` REAL NOT NULL, " +
                        "`window_ms` INTEGER NOT NULL, " +
                        "`sustained_passes` INTEGER NOT NULL, " +
                        "`effect_instances` INTEGER NOT NULL, " +
                        "`effect_sessions` INTEGER NOT NULL, " +
                        "`effects_per_session` TEXT NOT NULL, " +
                        "`effect_names` TEXT NOT NULL, " +
                        "`playback_session_ids` TEXT NOT NULL, " +
                        "`capture_note` TEXT)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_encoder_starvation_events_timestamp_ms` " +
                        "ON `encoder_starvation_events` (`timestamp_ms`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_encoder_starvation_events_device_address` " +
                        "ON `encoder_starvation_events` (`device_address`)",
                )
            }
        }

        fun create(context: Context): MonitorDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MonitorDatabase::class.java,
                "monitor.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}

internal fun MonitorEvent.toEntity() = MonitorEventEntity(
    timestampMs = timestampMs,
    deviceAddress = deviceAddress,
    deviceName = deviceName,
    type = type.name,
    detail = detail,
    codec = codec?.name,
    bitrateKbps = bitrateKbps,
)

internal fun MonitorEventEntity.toModel() = MonitorEvent(
    timestampMs = timestampMs,
    deviceAddress = deviceAddress,
    deviceName = deviceName,
    type = runCatching { MonitorEventType.valueOf(type) }
        .getOrDefault(MonitorEventType.MONITOR_NOTE),
    detail = detail,
    codec = codec?.let { name -> CodecFamily.entries.firstOrNull { it.name == name } },
    bitrateKbps = bitrateKbps,
)

internal fun LinkQualitySample.toEntity() = LinkSampleEntity(
    timestampMs = timestampMs,
    deviceAddress = deviceAddress,
    source = source.name,
    rssiDbm = rssiDbm,
    codec = codec?.name,
    bitrateKbps = bitrateKbps,
    sampleRateHz = sampleRateHz,
    isPlaying = isPlaying,
    retransmissions = retransmissions,
    droppedPackets = droppedPackets,
    glitchCount = glitchCount,
)

/** @param createdAtMillis wall-clock write time; see [CodecModeSignatureEntity]. */
internal fun ModeSignatureSample.toEntity(createdAtMillis: Long) = CodecModeSignatureEntity(
    deviceKey = deviceKey,
    codecName = codecName.uppercase(),
    modeRawValue = modeRawValue,
    sampleRateHz = sampleRateHz,
    framesPerPacketMin = framesPerPacket.start,
    framesPerPacketMax = framesPerPacket.endInclusive,
    packetsPerSecondMin = packetsPerSecond.start,
    packetsPerSecondMax = packetsPerSecond.endInclusive,
    capturedAtMs = capturedAtMs,
    createdAtMillis = createdAtMillis,
)

internal fun CodecModeSignatureEntity.toModel() = ModeSignatureSample(
    deviceKey = deviceKey,
    codecName = codecName,
    modeRawValue = modeRawValue,
    sampleRateHz = sampleRateHz,
    framesPerPacket = framesPerPacketMin..framesPerPacketMax,
    packetsPerSecond = packetsPerSecondMin..packetsPerSecondMax,
    capturedAtMs = capturedAtMs,
)

// ---- encoder-starvation forensics -------------------------------------------
//
// The three delimited columns are written and read in exactly one place each,
// right here, so the formats cannot drift apart. `EncoderStarvationRoundTripTest`
// pins that they survive the trip; a lossy encoding here would turn the one
// record that exists of an incident into a record of something else.

/** `145:3,0:2` — heaviest session first, as the report already sorted them. */
private fun List<SessionEffectCount>.encode(): String =
    joinToString(",") { "${it.sessionId}:${it.effectCount}" }

private fun String.decodeSessionCounts(): List<SessionEffectCount> =
    split(',').mapNotNull { pair ->
        val session = pair.substringBefore(':', "").trim().toIntOrNull() ?: return@mapNotNull null
        val count = pair.substringAfter(':', "").trim().toIntOrNull() ?: return@mapNotNull null
        SessionEffectCount(session, count)
    }

internal fun EncoderStarvationReport.toEntity() = EncoderStarvationEntity(
    timestampMs = timestampMs,
    deviceAddress = deviceAddress,
    deviceName = deviceName,
    underflowsPerSecond = underflowsPerSecond,
    windowMs = windowMs,
    sustainedPasses = sustainedPasses,
    effectInstances = forensics.effectInstances,
    effectSessions = forensics.sessionsWithEffects,
    effectsPerSession = forensics.effectsPerSession.encode(),
    // A `|` inside an effect name would split one name into two. Nothing on the
    // device prints one, and a silently mangled list is still worse than a name
    // with an underscore in it.
    effectNames = forensics.effectNames.joinToString("|") { it.replace('|', '_') },
    playbackSessionIds = forensics.playbackSessionIds.joinToString(","),
    captureNote = forensics.note,
)

internal fun EncoderStarvationEntity.toReport() = EncoderStarvationReport(
    timestampMs = timestampMs,
    deviceAddress = deviceAddress,
    deviceName = deviceName,
    underflowsPerSecond = underflowsPerSecond,
    windowMs = windowMs,
    sustainedPasses = sustainedPasses,
    forensics = EffectChainForensics(
        effectInstances = effectInstances,
        sessionsWithEffects = effectSessions,
        effectsPerSession = effectsPerSession.decodeSessionCounts(),
        effectNames = effectNames.split('|').filter { it.isNotBlank() },
        playbackSessionIds = playbackSessionIds.split(',').mapNotNull { it.trim().toIntOrNull() },
        note = captureNote,
    ),
)

internal fun LinkSampleEntity.toModel() = LinkQualitySample(
    timestampMs = timestampMs,
    deviceAddress = deviceAddress,
    source = runCatching { LinkDataSource.valueOf(source) }.getOrDefault(LinkDataSource.NONE),
    rssiDbm = rssiDbm,
    codec = codec?.let { name -> CodecFamily.entries.firstOrNull { it.name == name } },
    bitrateKbps = bitrateKbps,
    sampleRateHz = sampleRateHz,
    isPlaying = isPlaying,
    retransmissions = retransmissions,
    droppedPackets = droppedPackets,
    glitchCount = glitchCount,
)
