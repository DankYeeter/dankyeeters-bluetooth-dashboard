package dev.dankyeeter.btdashboard.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import dev.dankyeeter.btdashboard.monitor.codec.A2dpCodecStatusSource
import dev.dankyeeter.btdashboard.monitor.codec.CodecStatusSource
import dev.dankyeeter.btdashboard.monitor.codec.FallbackCodecStatusSource
import dev.dankyeeter.btdashboard.monitor.data.InMemoryMonitorRepository
import dev.dankyeeter.btdashboard.monitor.data.MonitorDatabase
import dev.dankyeeter.btdashboard.monitor.data.MonitorRepository
import dev.dankyeeter.btdashboard.monitor.data.RoomMonitorRepository
import dev.dankyeeter.btdashboard.monitor.dumpsys.ShellDumpsysLinkSource
import dev.dankyeeter.btdashboard.monitor.effects.AudioManagerPlayingAppsSource
import dev.dankyeeter.btdashboard.monitor.effects.AudioPlaybackWatcher
import dev.dankyeeter.btdashboard.monitor.effects.EqCandidateScanner
import dev.dankyeeter.btdashboard.monitor.effects.ForeignEqScanner
import dev.dankyeeter.btdashboard.monitor.effects.PackageManagerAppSource
import dev.dankyeeter.btdashboard.monitor.effects.ShellProcessResolver
import dev.dankyeeter.btdashboard.monitor.link.BluetoothBroadcastSource
import dev.dankyeeter.btdashboard.monitor.link.QualityReportSource
import dev.dankyeeter.btdashboard.monitor.link.ShizukuQualityReportSource
import dev.dankyeeter.btdashboard.monitor.sampling.LinkSampleCollector
import dev.dankyeeter.btdashboard.monitor.sampling.MonitorEngine
import dev.dankyeeter.btdashboard.monitor.shell.ShizukuShellRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide wiring for the monitor, in the same hand-rolled style as
 * `SystemGraph` — one object, lazily built, no DI framework.
 */
object MonitorGraph {

    @Volatile private var appContext: Context? = null
    private val lock = Any()

    private var _repository: MonitorRepository? = null
    private var _codecSource: CodecStatusSource? = null
    private var _a2dp: A2dpCodecStatusSource? = null
    private var _engine: MonitorEngine? = null
    private var _bqr: QualityReportSource? = null
    private var _scanner: ForeignEqScanner? = null
    private var _candidates: EqCandidateScanner? = null
    private var _playbackWatcher: AudioPlaybackWatcher? = null
    private var _screenOn: MutableStateFlow<Boolean>? = null

    private val shell by lazy { ShizukuShellRunner() }

    /**
     * The monitor outlives any single screen: a ViewModel scope would stop
     * sampling the moment the user leaves the Monitor tab. This is a plain
     * app-lifetime scope — the sampler idles to zero work by itself when the
     * screen is off and nothing is playing (see SamplingPolicy).
     */
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun ctx(): Context =
        requireNotNull(appContext) { "MonitorGraph.init() must be called from Application.onCreate" }

    val repository: MonitorRepository
        get() = synchronized(lock) {
            _repository ?: buildRepository().also { _repository = it }
        }

    private fun buildRepository(): MonitorRepository = runCatching {
        RoomMonitorRepository(MonitorDatabase.create(ctx()).monitorDao()) as MonitorRepository
    }.getOrElse {
        // A broken database must never take the app down; history is expendable.
        InMemoryMonitorRepository()
    }

    /**
     * The A2DP system API first, `dumpsys` under the shell identity second.
     * Without the fallback the codec reads as "unknown" on stock Android for
     * every app that does not hold BLUETOOTH_PRIVILEGED — which is all of them.
     */
    val codecSource: CodecStatusSource
        get() = synchronized(lock) {
            _codecSource ?: run {
                val a2dp = A2dpCodecStatusSource(ctx()).also { it.connect() }
                _a2dp = a2dp
                FallbackCodecStatusSource(
                    primary = a2dp,
                    dumpsys = ShellDumpsysLinkSource(shell),
                ).also { _codecSource = it }
            }
        }

    val qualityReportSource: QualityReportSource
        get() = synchronized(lock) {
            _bqr ?: ShizukuQualityReportSource(ctx()).also { _bqr = it }
        }

    val foreignEqScanner: ForeignEqScanner
        get() = synchronized(lock) {
            _scanner ?: ForeignEqScanner(
                shell = shell,
                processResolver = ShellProcessResolver(ctx(), shell),
                installedPackages = { installedPackageNames() },
            ).also { _scanner = it }
        }

    /**
     * The "which apps could have an EQ" scanner.
     *
     * Built lazily on first use — i.e. the first time the user actually opens
     * the other-equalizers section. The package-change receiver is registered
     * at the same moment and for the same reason: an app that never looks at
     * the list has no business listening for changes to it.
     */
    val eqCandidateScanner: EqCandidateScanner
        get() = synchronized(lock) {
            _candidates ?: EqCandidateScanner(
                apps = PackageManagerAppSource(ctx()),
                playing = AudioManagerPlayingAppsSource(ctx()),
                ownPackage = ctx().packageName,
            ).also {
                _candidates = it
                registerPackageChangeInvalidation(it)
            }
        }

    val playbackWatcher: AudioPlaybackWatcher
        get() = synchronized(lock) {
            _playbackWatcher ?: AudioPlaybackWatcher(ctx()).also { _playbackWatcher = it }
        }

    /**
     * The only thing that may drop the cached package pass. No timer, no
     * periodic job: the installed-app list changes when a package changes and
     * at no other time, and the system already tells us when that happens.
     */
    private fun registerPackageChangeInvalidation(scanner: EqCandidateScanner) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = scanner.invalidate()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        runCatching { ctx().registerReceiver(receiver, filter) }
    }

    val screenOn: StateFlow<Boolean>
        get() = synchronized(lock) { screenState() }

    private fun screenState(): MutableStateFlow<Boolean> =
        _screenOn ?: MutableStateFlow(isScreenCurrentlyOn()).also { state ->
            _screenOn = state
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    state.value = intent?.action == Intent.ACTION_SCREEN_ON
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            runCatching { ctx().registerReceiver(receiver, filter) }
        }

    /**
     * Only the vendor EQ apps are looked up, one `getPackageInfo` each.
     *
     * The app now holds QUERY_ALL_PACKAGES for the candidate scan, so a full
     * enumeration would work here too — it just costs a hundred times more for
     * a list of twelve packages we already know by name. It also keeps working
     * if the broad permission is ever taken away, since the `<queries>` block
     * still names each package explicitly.
     */
    private fun installedPackageNames(): Set<String> {
        val pm = ctx().packageManager
        return dev.dankyeeter.btdashboard.monitor.effects.VendorEqApps.known
            .map { it.packageName }
            .filter { name -> runCatching { pm.getPackageInfo(name, 0) }.isSuccess }
            .toSet()
    }

    private fun isScreenCurrentlyOn(): Boolean = runCatching {
        (ctx().getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
    }.getOrDefault(true)

    val engine: MonitorEngine
        get() = synchronized(lock) {
            _engine ?: MonitorEngine(
                repository = repository,
                eventSource = BluetoothBroadcastSource(ctx()),
                collector = LinkSampleCollector(
                    codecSource = codecSource,
                    dumpsysSource = ShellDumpsysLinkSource(shell),
                    qualityReportSource = qualityReportSource,
                ),
                screenOn = screenState(),
            ).also { _engine = it }
        }

    /** Starts the monitor if it is not already running. Idempotent. */
    fun ensureRunning() {
        engine.start(monitorScope)
    }
}
