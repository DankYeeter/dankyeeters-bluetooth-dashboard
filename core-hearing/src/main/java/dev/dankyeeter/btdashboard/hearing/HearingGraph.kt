package dev.dankyeeter.btdashboard.hearing

import android.content.Context
import dev.dankyeeter.btdashboard.hearing.store.AudiogramStore

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

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun ctx(): Context =
        requireNotNull(appContext) { "HearingGraph.init() must be called from Application.onCreate" }

    val audiogramStore: AudiogramStore
        get() = synchronized(lock) { _store ?: AudiogramStore(ctx()).also { _store = it } }

    val aggregator: AudiogramAggregator = MedianAudiogramAggregator()
}
