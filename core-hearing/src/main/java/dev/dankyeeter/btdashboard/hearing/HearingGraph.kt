package dev.dankyeeter.btdashboard.hearing

import android.content.Context
import dev.dankyeeter.btdashboard.hearing.store.AudiogramStore
import dev.dankyeeter.btdashboard.hearing.store.CompensationProfileStore

/**
 * Hand-rolled wiring for the hearing module, mirroring
 * `dev.dankyeeter.btdashboard.system.SystemGraph` (no DI framework in this
 * project). The tone generator and the test controller are *not* singletons:
 * they own an audio stream and are created per test run.
 */
object HearingGraph {

    @Volatile private var appContext: Context? = null
    private val lock = Any()
    private var _store: AudiogramStore? = null
    private var _profileStore: CompensationProfileStore? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun ctx(): Context =
        requireNotNull(appContext) { "HearingGraph.init() must be called from Application.onCreate" }

    val audiogramStore: AudiogramStore
        get() = synchronized(lock) { _store ?: AudiogramStore(ctx()).also { _store = it } }

    val aggregator: AudiogramAggregator = MedianAudiogramAggregator()

    // --- Stage C (compensation) ---

    val profileStore: CompensationProfileStore
        get() = synchronized(lock) {
            _profileStore ?: CompensationProfileStore(ctx()).also { _profileStore = it }
        }

    val presets: CalibrationPresetRepository = BundledCalibrationPresets

    val compensationCalculator: NalRCompensationCalculator = NalRCompensationCalculator(presets)
}
