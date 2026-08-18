package dev.dankyeeter.btdashboard.monitor.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
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
}

@Database(
    entities = [MonitorEventEntity::class, LinkSampleEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MonitorDatabase : RoomDatabase() {
    abstract fun monitorDao(): MonitorDao

    companion object {
        fun create(context: Context): MonitorDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MonitorDatabase::class.java,
                "monitor.db",
            ).fallbackToDestructiveMigration().build()
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
