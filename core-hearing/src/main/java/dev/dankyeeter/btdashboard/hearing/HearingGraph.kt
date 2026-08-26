package dev.dankyeeter.btdashboard.hearing

import android.content.Context
import dev.dankyeeter.btdashboard.hearing.store.AudiogramStore
import dev.dankyeeter.btdashboard.hearing.store.CompensationProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    /**
     * App-lifetime scope for the one thing in this graph that has to keep
     * reading: the derived presets.
     *
     * Nothing else here needs a scope — every other member is a store or a pure
     * calculator, and the ViewModels do their own collecting. But
     * [CalibrationPresetRepository.byId] is synchronous and is called from the
     * compensation math, so the derivations have to be *already* in memory when
     * it asks. That means one collector with no owner but the process itself.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            audiogramStore.derivedCalibrations.collect(presets::setDerived)
        }
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

    /**
     * The bundled table plus the user's own derivations. Kept eager and free of
     * [ctx] on purpose: [compensationCalculator] below captures it while this
     * object is being constructed, which is before `init` has run.
     */
    val presets: DerivedCalibrationPresetRepository =
        DerivedCalibrationPresetRepository(BundledCalibrationPresets)

    val compensationCalculator: NalRCompensationCalculator = NalRCompensationCalculator(presets)
}
