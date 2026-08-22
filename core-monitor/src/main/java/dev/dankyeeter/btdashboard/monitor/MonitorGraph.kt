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
import dev.dankyeeter.btdashboard.monitor.dumpsys.CachedDumpsysLinkSource
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysLinkSource
import dev.dankyeeter.btdashboard.monitor.dumpsys.ShellDumpsysLinkSource
import dev.dankyeeter.btdashboard.monitor.effects.AudioManagerPlayingAppsSource
import dev.dankyeeter.btdashboard.monitor.effects.AudioPlaybackWatcher
import dev.dankyeeter.btdashboard.monitor.effects.EqCandidateScanner
import dev.dankyeeter.btdashboard.monitor.effects.ForeignEqScanner
import dev.dankyeeter.btdashboard.monitor.effects.PackageManagerAppSource
import dev.dankyeeter.btdashboard.monitor.effects.ShellProcessResolver
import dev.dankyeeter.btdashboard.monitor.link.BluetoothBroadcastSource
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.link.QualityReportSource
import dev.dankyeeter.btdashboard.monitor.link.ReflectiveQualityReportSource
import dev.dankyeeter.btdashboard.monitor.sampling.LinkSampleCollector
import dev.dankyeeter.btdashboard.monitor.sampling.MonitorEngine
import dev.dankyeeter.btdashboard.monitor.shell.ShellResult
import dev.dankyeeter.btdashboard.monitor.shell.ShellRunner
import dev.dankyeeter.btdashboard.monitor.shell.UnavailableShellRunner
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
    private var _dumpsys: CachedDumpsysLinkSource? = null

    /**
     * Whether a screen showing link data is in the foreground. Only the
     * sampler reads it, and only to decide whether idle polling is worth
     * anything; the event sources are unaffected and stay armed either way.
     */
    private val _uiVisible = MutableStateFlow(false)

    @Volatile
    private var installedShell: ShellRunner? = null

    /**
     * Replaces the shell identity provider.
     *
     * The app installs its own privileged helper here at startup; without it
     * everything shell-based degrades to "cannot check". This module cannot
     * reach into :app to choose, and should not — every consumer only ever
     * sees [ShellRunner].
     */
    fun installShellRunner(runner: ShellRunner) {
        installedShell = runner
    }

    /**
     * A stable object that resolves the shell identity on **every call**.
     *
     * This indirection is the whole point, and removing it reintroduces a bug
     * that is invisible in testing: the consumers below — `codecSource`,
     * `foreignEqScanner`, `engine` — are built once and cached, so whatever
     * [ShellRunner] they are handed at construction is the one they keep for
     * the life of the process. Returning the *currently best* runner from a
     * getter therefore froze the answer at first access.
     *
     * That is exactly backwards for the case that matters. The privileged
     * helper cannot be running at app start: it dies on reboot and is started
     * afterwards over ADB. So the first access always found nothing, and the
     * helper — once it did connect — was never picked up at all.
     *
     * Handing out this delegate instead means the decision is made per command,
     * not once. It is safe to capture precisely because it holds no choice of
     * its own.
     */
    val shell: ShellRunner = object : ShellRunner {

        override val isAvailable: Boolean
            get() = current().isAvailable

        override suspend fun run(command: List<String>): ShellResult = current().run(command)

        private fun current(): ShellRunner =
            installedShell?.takeIf { it.isAvailable } ?: UnavailableShellRunner
    }

    /**
     * The monitor outlives any single screen: a ViewModel scope would stop
     * sampling the moment the user leaves the Monitor tab. This is a plain
     * app-lifetime scope — the sampler idles to zero work by itself whenever
     * nothing is playing and no screen is showing the numbers (see
     * SamplingPolicy and [setUiVisible]).
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
     * The one `dumpsys bluetooth_manager` reader in the process.
     *
     * Sharing it is the point, not a convenience: the collector and the codec
     * source both need the dump within microseconds of each other, and every
     * separately constructed `ShellDumpsysLinkSource` used to mean another
     * exec-plus-parse. See [CachedDumpsysLinkSource] for what one costs.
     */
    val dumpsysSource: DumpsysLinkSource
        get() = synchronized(lock) { cachedDumpsys() }

    private fun cachedDumpsys(): CachedDumpsysLinkSource =
        _dumpsys ?: CachedDumpsysLinkSource(ShellDumpsysLinkSource(shell)).also { _dumpsys = it }

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
                    dumpsys = cachedDumpsys(),
                ).also { _codecSource = it }
            }
        }

    val qualityReportSource: QualityReportSource
        get() = synchronized(lock) {
            _bqr ?: ReflectiveQualityReportSource(ctx()).also { _bqr = it }
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
                    dumpsysSource = cachedDumpsys(),
                    qualityReportSource = qualityReportSource,
                ),
                screenOn = screenState(),
                uiVisible = _uiVisible,
            ).also { _engine = it }
        }

    /**
     * Which source the collector would use right now. Used before the first
     * sample exists, so a cold screen does not claim there is no source.
     */
    fun collectorSource(): LinkDataSource = when {
        qualityReportSource.availability.value.isActive -> LinkDataSource.QUALITY_REPORT
        dumpsysSource.isAvailable -> LinkDataSource.DUMPSYS
        codecSource.isProfileAvailable -> LinkDataSource.CODEC_API
        else -> LinkDataSource.NONE
    }

    /** Starts the monitor if it is not already running. Idempotent. */
    fun ensureRunning() {
        engine.start(monitorScope)
    }

    /**
     * Told by a screen that displays link data whether it is on screen.
     *
     * This is the whole difference between "the phone is awake" and "someone
     * is looking at the numbers". Without it the sampler polled every 60 s for
     * as long as the display was on — around 200 full runs a day, each one a
     * codec query plus a `dumpsys` through the helper, with nothing playing and
     * no screen to draw the result on.
     *
     * Deliberately not tied to process lifetime: `ensureRunning()` is called
     * from `Application.onCreate` and the process is kept alive by the EQ
     * service, so the app being *alive* proves nothing about anyone watching.
     */
    fun setUiVisible(visible: Boolean) {
        _uiVisible.value = visible
    }
}
