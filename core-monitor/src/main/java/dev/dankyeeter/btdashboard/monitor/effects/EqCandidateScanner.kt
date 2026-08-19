package dev.dankyeeter.btdashboard.monitor.effects

/** One installed app, reduced to the two facts the tiering needs. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    /** Has an activity for `DISPLAY_AUDIO_EFFECT_CONTROL_PANEL`. */
    val declaresEffectPanel: Boolean,
    /** Requests `MODIFY_AUDIO_SETTINGS`. */
    val requestsAudioSettings: Boolean,
    /** Pre-installed and never updated by the user. Only used to mute the weak tier. */
    val isUntouchedSystemApp: Boolean = false,
)

/** Reading the package list either works or explains itself. Never a silent empty list. */
sealed interface InstalledAppReadResult {
    data class Available(val apps: List<InstalledApp>, val scannedPackages: Int) : InstalledAppReadResult
    data class Unavailable(val reason: String) : InstalledAppReadResult
}

/** Same shape for the playback overlay. */
sealed interface PlayingAppsResult {
    data class Available(val packages: Set<String>) : PlayingAppsResult
    data class Unavailable(val reason: String) : PlayingAppsResult
}

/**
 * The expensive half: one pass over every installed package.
 *
 * A seam, so the tiering is unit-testable without a device — `PackageManager`
 * is not mocked in plain JVM tests and Robolectric does not model intent
 * filters of third-party apps anyway.
 */
interface InstalledAppSource {
    suspend fun read(): InstalledAppReadResult
}

/** The cheap half: who is producing audio right now. */
interface PlayingAppsSource {
    suspend fun playingPackages(): PlayingAppsResult
}

/**
 * Maps the uids reported by the audio framework onto package names.
 *
 * Its own object because a uid is not a package: a shared-uid pair of apps
 * resolves to several packages, and an unknown uid resolves to none. Both are
 * normal and neither may throw.
 */
object PlayingAppMapper {

    fun packagesFor(uids: Collection<Int>, packagesForUid: (Int) -> List<String>): Set<String> =
        uids.toSet()
            .flatMap { uid -> runCatching { packagesForUid(uid) }.getOrDefault(emptyList()) }
            .filter { it.isNotBlank() }
            .toSet()
}

/**
 * Tiers every installed app by how likely it is to carry its own equaliser, and
 * marks the ones currently playing.
 *
 * ### Cost, and why it is cached
 *
 * The package pass costs one `getInstalledPackages(GET_PERMISSIONS)` plus one
 * `queryIntentActivities` — on a normally loaded phone a few hundred packages
 * and a few hundred milliseconds, all of it on a background dispatcher. That is
 * far too expensive to hang off a dashboard refresh or a timer, so:
 *
 *  - the pass runs **only** when the user opens the section or taps "check",
 *  - its result is cached for the life of the process,
 *  - the cache is dropped only on a real reason — a package was added, removed
 *    or replaced (see `MonitorGraph`), which is an event, not a poll.
 *
 * The cache is deliberately in memory rather than on disk. The invalidating
 * broadcasts (`ACTION_PACKAGE_ADDED`/`REMOVED`/`REPLACED`) cannot be received
 * by a manifest receiver on modern Android, so they only reach us while the
 * process is alive. A disk cache would therefore survive exactly the window in
 * which we are blind to changes — it would save one scan per cold start and buy
 * a stale list in exchange. Not a good trade.
 *
 * The playing-now overlay is re-read on every [scan] because it is a single
 * cheap synchronous call and its answer changes by the second.
 */
class EqCandidateScanner(
    private val apps: InstalledAppSource,
    private val playing: PlayingAppsSource,
    private val ownPackage: String,
    /** Monotonic milliseconds; injectable so the duration is testable. */
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    private data class CachedPass(val apps: List<InstalledApp>, val scanned: Int, val durationMs: Long)

    @Volatile
    private var cache: CachedPass? = null

    /** Called from the package-change receiver. Next [scan] pays for a fresh pass. */
    fun invalidate() {
        cache = null
    }

    val hasCachedPass: Boolean get() = cache != null

    /**
     * @param refresh forces a new package pass even when a cached one exists.
     *   Wired to the explicit "check again" button, never to anything periodic.
     */
    suspend fun scan(refresh: Boolean = false): EqCandidateScan {
        if (refresh) cache = null

        val reused = cache
        val pass = reused ?: runPass()
            ?: return EqCandidateScan(
                available = false,
                unavailableReason = unavailableReason
                    ?: "Cannot check which apps might have an equalizer.",
            )

        val playbackResult = runCatching { playing.playingPackages() }.getOrElse {
            PlayingAppsResult.Unavailable("the audio framework refused the player list")
        }
        val playingPackages = (playbackResult as? PlayingAppsResult.Available)?.packages.orEmpty()

        return EqCandidateScan(
            candidates = EqCandidateRanking.sort(pass.apps.mapNotNull { toCandidate(it, playingPackages) }),
            scannedPackages = pass.scanned,
            durationMs = pass.durationMs,
            fromCache = reused != null,
            playbackKnown = playbackResult is PlayingAppsResult.Available,
            playbackNote = (playbackResult as? PlayingAppsResult.Unavailable)?.reason,
        )
    }

    private var unavailableReason: String? = null

    /** The one expensive call. Returns null (with [unavailableReason] set) when it cannot run. */
    private suspend fun runPass(): CachedPass? {
        val started = elapsedMs()
        val read = runCatching { apps.read() }.getOrElse {
            InstalledAppReadResult.Unavailable(
                "the installed-app list could not be read (${it.javaClass.simpleName})",
            )
        }
        return when (read) {
            is InstalledAppReadResult.Unavailable -> {
                unavailableReason =
                    "Cannot check which apps might have an equalizer — ${read.reason}."
                null
            }

            is InstalledAppReadResult.Available -> CachedPass(
                apps = read.apps,
                scanned = read.scannedPackages,
                durationMs = (elapsedMs() - started).coerceAtLeast(0),
            ).also {
                cache = it
                unavailableReason = null
            }
        }
    }

    private fun toCandidate(app: InstalledApp, playingPackages: Set<String>): EqCandidate? {
        if (app.packageName == ownPackage) return null

        val vendor = VendorEqApps.byPackage(app.packageName)
        val evidence = buildList {
            if (app.declaresEffectPanel) add(EqEvidence.DECLARED_PANEL)
            if (vendor != null) add(EqEvidence.VENDOR_COMPANION)
            if (app.requestsAudioSettings) add(EqEvidence.AUDIO_EFFECT_PERMISSION)
        }

        // A stock system component holding MODIFY_AUDIO_SETTINGS and nothing
        // else is the system doing system things (SystemUI, the dialer, the
        // camera shutter). Listing sixty of those would bury the three entries
        // that matter. Only the weakest tier is filtered this way — a ROM's own
        // equalizer is a system app too, but it lands in DECLARED_PANEL and is
        // never dropped here.
        val weakOnly = evidence.singleOrNull() == EqEvidence.AUDIO_EFFECT_PERMISSION
        if (weakOnly && app.isUntouchedSystemApp) return null

        return EqCandidate.of(
            packageName = app.packageName,
            // The curated label beats whatever the launcher label happens to
            // be; it is the name the user knows the headphone app by.
            appLabel = vendor?.appLabel ?: app.label,
            evidence = evidence,
            playingNow = app.packageName in playingPackages,
            vendor = vendor?.vendor,
        )
    }
}
