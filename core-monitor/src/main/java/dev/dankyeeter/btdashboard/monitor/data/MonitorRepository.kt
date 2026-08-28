package dev.dankyeeter.btdashboard.monitor.data

import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample
import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.live.EncoderStarvationReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Storage seam. Everything above (sampler, diagnostic, UI) depends only on this
 * interface, so the whole monitor can be exercised on the JVM with
 * [InMemoryMonitorRepository].
 */
interface MonitorRepository {
    suspend fun recordEvent(event: MonitorEvent)
    suspend fun recordSample(sample: LinkQualitySample)
    fun events(sinceMs: Long): Flow<List<MonitorEvent>>
    fun samples(sinceMs: Long): Flow<List<LinkQualitySample>>
    suspend fun latestSample(address: String): LinkQualitySample?
    suspend fun samplesBetween(address: String, fromMs: Long, toMs: Long): List<LinkQualitySample>

    /**
     * Stores one tripped encoder-starvation capture.
     *
     * Kept apart from [recordEvent] rather than folded into it, because the two
     * carry different things and outlive each other differently: the event is a
     * sentence for the timeline, this is the counts behind that sentence, and
     * the counts are what somebody re-reads months later when the sentence is no
     * longer enough. See `link.live.EncoderStarvationTripwire`.
     */
    suspend fun recordStarvation(report: EncoderStarvationReport)

    /** Captures since [sinceMs], newest first. Empty when none were recorded. */
    suspend fun starvations(sinceMs: Long): List<EncoderStarvationReport>

    /** History is diagnostic data, not archive material — trimmed on start. */
    suspend fun purgeOlderThan(cutoffMs: Long)
}

class RoomMonitorRepository(private val dao: MonitorDao) : MonitorRepository {
    override suspend fun recordEvent(event: MonitorEvent) {
        runCatching { dao.insertEvent(event.toEntity()) }
    }

    override suspend fun recordSample(sample: LinkQualitySample) {
        runCatching { dao.insertSample(sample.toEntity()) }
    }

    override fun events(sinceMs: Long): Flow<List<MonitorEvent>> =
        dao.events(sinceMs).map { list -> list.map { it.toModel() } }

    override fun samples(sinceMs: Long): Flow<List<LinkQualitySample>> =
        dao.samples(sinceMs).map { list -> list.map { it.toModel() } }

    override suspend fun latestSample(address: String): LinkQualitySample? =
        runCatching { dao.latestSample(address)?.toModel() }.getOrNull()

    override suspend fun samplesBetween(address: String, fromMs: Long, toMs: Long) =
        runCatching { dao.samplesBetween(address, fromMs, toMs).map { it.toModel() } }
            .getOrDefault(emptyList())

    override suspend fun recordStarvation(report: EncoderStarvationReport) {
        runCatching { dao.insertStarvation(report.toEntity()) }
    }

    override suspend fun starvations(sinceMs: Long): List<EncoderStarvationReport> =
        runCatching { dao.starvations(sinceMs).map { it.toReport() } }.getOrDefault(emptyList())

    override suspend fun purgeOlderThan(cutoffMs: Long) {
        runCatching {
            dao.purgeEvents(cutoffMs)
            dao.purgeSamples(cutoffMs)
            dao.purgeStarvations(cutoffMs)
        }
    }
}

/** Test/preview double; also the fallback if the database fails to open. */
class InMemoryMonitorRepository : MonitorRepository {
    private val eventState = MutableStateFlow<List<MonitorEvent>>(emptyList())
    private val sampleState = MutableStateFlow<List<LinkQualitySample>>(emptyList())
    private val starvationState = MutableStateFlow<List<EncoderStarvationReport>>(emptyList())

    override suspend fun recordEvent(event: MonitorEvent) {
        eventState.value = (eventState.value + event).sortedBy { it.timestampMs }
    }

    override suspend fun recordSample(sample: LinkQualitySample) {
        sampleState.value = (sampleState.value + sample).sortedBy { it.timestampMs }
    }

    override fun events(sinceMs: Long): Flow<List<MonitorEvent>> =
        eventState.map { list -> list.filter { it.timestampMs >= sinceMs } }

    override fun samples(sinceMs: Long): Flow<List<LinkQualitySample>> =
        sampleState.map { list -> list.filter { it.timestampMs >= sinceMs } }

    override suspend fun latestSample(address: String): LinkQualitySample? =
        sampleState.value.lastOrNull { it.deviceAddress == address }

    override suspend fun samplesBetween(address: String, fromMs: Long, toMs: Long) =
        sampleState.value.filter {
            it.deviceAddress == address && it.timestampMs in fromMs..toMs
        }

    override suspend fun recordStarvation(report: EncoderStarvationReport) {
        starvationState.value = (starvationState.value + report).sortedByDescending { it.timestampMs }
    }

    override suspend fun starvations(sinceMs: Long): List<EncoderStarvationReport> =
        starvationState.value.filter { it.timestampMs >= sinceMs }

    override suspend fun purgeOlderThan(cutoffMs: Long) {
        eventState.value = eventState.value.filter { it.timestampMs >= cutoffMs }
        sampleState.value = sampleState.value.filter { it.timestampMs >= cutoffMs }
        starvationState.value = starvationState.value.filter { it.timestampMs >= cutoffMs }
    }
}
