package dev.dankyeeter.btdashboard.monitor.data

import dev.dankyeeter.btdashboard.monitor.link.live.CodecModeSignatureStore
import dev.dankyeeter.btdashboard.monitor.link.live.ModeSignatureSample
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The [CodecModeSignatureStore] that survives a restart: an in-memory snapshot
 * hydrated once from the database and written through on every change.
 *
 * ## Why a snapshot rather than a query per read
 *
 * `LiveLinkSource` asks for the signatures of the current device on **every
 * poll**, and deliberately so — a calibration run has to take effect on the very
 * next reading, which is the whole reason the user pressed the button. Routing
 * that through SQLite would put a file read inside a loop that already spends
 * around half a second per pass shelling out to `dumpsys`, in exchange for
 * re-reading a table of a handful of rows that only changes when a calibration
 * finishes. The map is the working copy; the table is the record.
 *
 * ## Failure degrades, it does not crash
 *
 * Every database call is wrapped and [hydrated] completes either way. If the
 * file cannot be opened — or a future schema change arrives without a migration,
 * now that the destructive fallback is gone — this behaves exactly like
 * `InMemoryCodecModeSignatureStore` and the calibration lasts one process. That
 * is the same stance [RoomMonitorRepository] takes, for the same reason: a
 * broken database must not take the app down.
 */
class RoomCodecModeSignatureStore(
    private val dao: CodecModeSignatureDao,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : CodecModeSignatureStore {

    private val samples = mutableMapOf<Triple<String, String, Long>, ModeSignatureSample>()
    private val lock = Any()

    /**
     * Completes once the table has been read, successfully or not.
     *
     * All three operations await it. For reads that is so the first poll after
     * process start already sees what was learned last time, instead of showing
     * an un-calibrated panel that changes its mind a moment later. For writes it
     * is so a `clear` issued during startup cannot be undone by an in-flight
     * hydration putting the rows it just deleted back into the snapshot.
     */
    private val hydrated = CompletableDeferred<Unit>()

    init {
        scope.launch {
            runCatching { dao.all() }
                .getOrDefault(emptyList())
                .forEach { entity ->
                    val sample = entity.toModel()
                    // Never over a value already put this session: a user who
                    // calibrates immediately at startup has produced the fresher
                    // measurement, and the stored row is the stale one.
                    synchronized(lock) { samples.putIfAbsent(sample.key(), sample) }
                }
            hydrated.complete(Unit)
        }
    }

    override suspend fun signatures(deviceKey: String, codecName: String): List<ModeSignatureSample> {
        hydrated.await()
        return synchronized(lock) {
            samples.values.filter {
                it.deviceKey == deviceKey && it.codecName.equals(codecName, true)
            }
        }
    }

    override suspend fun put(sample: ModeSignatureSample) {
        hydrated.await()
        // Snapshot first, database second: a write that fails must still leave
        // the calibration usable for the session that just measured it.
        synchronized(lock) { samples[sample.key()] = sample }
        runCatching { dao.upsert(sample.toEntity(clock())) }
    }

    override suspend fun clear(deviceKey: String, codecName: String) {
        hydrated.await()
        val key = codecName.uppercase()
        synchronized(lock) {
            samples.keys.filter { it.first == deviceKey && it.second == key }
                .forEach(samples::remove)
        }
        runCatching { dao.clear(deviceKey, key) }
    }

    /** The same case-insensitive triple the table's primary key enforces. */
    private fun ModeSignatureSample.key() =
        Triple(deviceKey, codecName.uppercase(), modeRawValue)
}
