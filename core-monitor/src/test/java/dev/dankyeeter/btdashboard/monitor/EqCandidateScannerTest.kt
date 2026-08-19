package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.effects.EqCandidate
import dev.dankyeeter.btdashboard.monitor.effects.EqCandidateRanking
import dev.dankyeeter.btdashboard.monitor.effects.EqCandidateScanner
import dev.dankyeeter.btdashboard.monitor.effects.EqEvidence
import dev.dankyeeter.btdashboard.monitor.effects.InstalledApp
import dev.dankyeeter.btdashboard.monitor.effects.InstalledAppReadResult
import dev.dankyeeter.btdashboard.monitor.effects.InstalledAppSource
import dev.dankyeeter.btdashboard.monitor.effects.PlayingAppMapper
import dev.dankyeeter.btdashboard.monitor.effects.PlayingAppsResult
import dev.dankyeeter.btdashboard.monitor.effects.PlayingAppsSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free coverage for the "which apps could have an EQ" check: the
 * tiering, the sort order, the uid→package mapping, the caching, and every
 * degraded path. `PackageManager` and `AudioManager` never appear here — the
 * scanner talks to seams, which is the whole reason they exist.
 */
class EqCandidateScannerTest {

    // ---- fakes --------------------------------------------------------------

    private class FakeAppSource(
        var result: InstalledAppReadResult,
        var throwOnRead: Throwable? = null,
    ) : InstalledAppSource {
        var reads = 0
            private set

        override suspend fun read(): InstalledAppReadResult {
            reads++
            throwOnRead?.let { throw it }
            return result
        }
    }

    private class FakePlayingApps(
        var result: PlayingAppsResult = PlayingAppsResult.Available(emptySet()),
    ) : PlayingAppsSource {
        var reads = 0
            private set

        override suspend fun playingPackages(): PlayingAppsResult {
            reads++
            return result
        }
    }

    private fun app(
        pkg: String,
        label: String = pkg,
        panel: Boolean = false,
        permission: Boolean = false,
        systemApp: Boolean = false,
    ) = InstalledApp(pkg, label, panel, permission, systemApp)

    private fun scanner(
        apps: FakeAppSource,
        playing: FakePlayingApps = FakePlayingApps(),
        own: String = "dev.dankyeeter.btdashboard",
        clock: () -> Long = { 0L },
    ) = EqCandidateScanner(apps, playing, own, clock)

    private fun available(vararg apps: InstalledApp, scanned: Int = apps.size) =
        InstalledAppReadResult.Available(apps.toList(), scanned)

    // ---- tiering ------------------------------------------------------------

    @Test
    fun `a declared effect panel is the strongest evidence`() = runTest {
        val source = FakeAppSource(available(app("com.pittvandewitt.wavelet", "Wavelet", panel = true)))
        val candidate = scanner(source).scan().candidates.single()

        assertEquals(EqEvidence.DECLARED_PANEL, candidate.primaryEvidence)
        assertEquals("provides a system equalizer panel", candidate.reason)
        assertFalse(candidate.isWeak)
    }

    @Test
    fun `a curated vendor package is tiered as a companion app and keeps its known name`() = runTest {
        // The launcher label is deliberately unhelpful here; the curated one wins.
        val source = FakeAppSource(available(app("com.naimaudio.naim.std", label = "naim.std")))
        val candidate = scanner(source).scan().candidates.single()

        assertEquals(EqEvidence.VENDOR_COMPANION, candidate.primaryEvidence)
        assertEquals("Focal & Co", candidate.appLabel)
        assertEquals("Focal", candidate.vendor)
        assertEquals("headphone companion app", candidate.reason)
    }

    @Test
    fun `MODIFY_AUDIO_SETTINGS alone is the weak tier`() = runTest {
        val source = FakeAppSource(available(app("com.example.player", permission = true)))
        val scan = scanner(source).scan()

        val candidate = scan.candidates.single()
        assertEquals(EqEvidence.AUDIO_EFFECT_PERMISSION, candidate.primaryEvidence)
        assertTrue(candidate.isWeak)
        assertTrue(scan.strong.isEmpty())
        assertEquals(listOf(candidate), scan.weak)
    }

    @Test
    fun `an app with several signals keeps all of them, strongest first`() = runTest {
        val source = FakeAppSource(
            available(app("com.naimaudio.naim.std", panel = true, permission = true)),
        )
        val candidate = scanner(source).scan().candidates.single()

        assertEquals(
            listOf(
                EqEvidence.DECLARED_PANEL,
                EqEvidence.VENDOR_COMPANION,
                EqEvidence.AUDIO_EFFECT_PERMISSION,
            ),
            candidate.evidence,
        )
        assertEquals(
            "provides a system equalizer panel · headphone companion app · can attach audio effects",
            candidate.reason,
        )
        assertFalse("several signals is never the weak tier", candidate.isWeak)
    }

    @Test
    fun `apps with no signal at all are not listed`() = runTest {
        val source = FakeAppSource(available(app("com.example.notes"), scanned = 300))
        val scan = scanner(source).scan()

        assertTrue(scan.isEmpty)
        assertTrue(scan.available)
        assertEquals(300, scan.scannedPackages)
    }

    @Test
    fun `our own package never lists itself`() = runTest {
        val source = FakeAppSource(
            available(app("dev.dankyeeter.btdashboard", panel = true, permission = true)),
        )
        assertTrue(scanner(source).scan().isEmpty)
    }

    @Test
    fun `stock system apps are dropped from the weak tier but never from a real one`() = runTest {
        val source = FakeAppSource(
            available(
                app("com.android.systemui", permission = true, systemApp = true),
                app("com.oem.audioeffects", panel = true, permission = true, systemApp = true),
            ),
        )
        val packages = scanner(source).scan().candidates.map { it.packageName }

        assertEquals(listOf("com.oem.audioeffects"), packages)
    }

    // ---- sorting ------------------------------------------------------------

    @Test
    fun `sort is playing-now, then declared, vendor, capable`() = runTest {
        val source = FakeAppSource(
            available(
                app("com.example.capable", "Capable", permission = true),
                app("com.naimaudio.naim.std", "Focal & Co"),
                app("com.pittvandewitt.wavelet", "Wavelet", panel = true),
                app("com.example.player", "Player", permission = true),
            ),
        )
        val playing = FakePlayingApps(PlayingAppsResult.Available(setOf("com.example.player")))

        val order = scanner(source, playing).scan().candidates.map { it.packageName }
        assertEquals(
            listOf(
                // playing now beats every tier, which is the user's own framing
                "com.example.player",
                "com.pittvandewitt.wavelet",
                "com.naimaudio.naim.std",
                "com.example.capable",
            ),
            order,
        )
    }

    @Test
    fun `ties break on label so the list does not reshuffle between scans`() {
        fun c(pkg: String, label: String) =
            checkNotNull(EqCandidate.of(pkg, label, listOf(EqEvidence.DECLARED_PANEL)))

        val sorted = EqCandidateRanking.sort(
            listOf(c("b", "Zeta"), c("a", "alpha"), c("c", "Beta")),
        )
        assertEquals(listOf("alpha", "Beta", "Zeta"), sorted.map { it.appLabel })
    }

    @Test
    fun `an empty evidence list produces no candidate at all`() {
        assertNull(EqCandidate.of("com.example", "Example", emptyList()))
        assertNotNull(EqCandidate.of("com.example", "Example", listOf(EqEvidence.DECLARED_PANEL)))
    }

    @Test
    fun `a blank label falls back to the package name`() {
        val candidate = checkNotNull(
            EqCandidate.of("com.example", "   ", listOf(EqEvidence.DECLARED_PANEL)),
        )
        assertEquals("com.example", candidate.appLabel)
    }

    // ---- uid to package -----------------------------------------------------

    @Test
    fun `uids map to every package that shares them`() {
        val packages = PlayingAppMapper.packagesFor(listOf(10123, 10123, 1000)) { uid ->
            when (uid) {
                10123 -> listOf("com.example.player")
                1000 -> listOf("com.android.systemui", "com.android.settings")
                else -> emptyList()
            }
        }
        assertEquals(
            setOf("com.example.player", "com.android.systemui", "com.android.settings"),
            packages,
        )
    }

    @Test
    fun `an unknown or throwing uid is skipped, not fatal`() {
        val packages = PlayingAppMapper.packagesFor(listOf(1, 2, 3)) { uid ->
            when (uid) {
                1 -> throw SecurityException("uid not visible")
                2 -> emptyList()
                else -> listOf("com.example.ok", "")
            }
        }
        assertEquals(setOf("com.example.ok"), packages)
    }

    // ---- caching and cost ---------------------------------------------------

    @Test
    fun `the package pass runs once and is reused, the playing check every time`() = runTest {
        val source = FakeAppSource(available(app("com.example.player", permission = true)))
        val playing = FakePlayingApps()
        val scanner = scanner(source, playing)

        val first = scanner.scan()
        val second = scanner.scan()

        assertEquals("package list read exactly once", 1, source.reads)
        assertEquals("playing check is cheap and re-read", 2, playing.reads)
        assertFalse(first.fromCache)
        assertTrue(second.fromCache)
    }

    @Test
    fun `invalidation and explicit refresh both force a new pass`() = runTest {
        val source = FakeAppSource(available(app("com.example.player", permission = true)))
        val scanner = scanner(source)

        scanner.scan()
        scanner.invalidate()
        assertFalse(scanner.hasCachedPass)
        scanner.scan()
        assertEquals(2, source.reads)

        scanner.scan(refresh = true)
        assertEquals(3, source.reads)
    }

    @Test
    fun `a cached pass picks up a newly playing app without re-reading packages`() = runTest {
        val source = FakeAppSource(available(app("com.example.player", permission = true)))
        val playing = FakePlayingApps()
        val scanner = scanner(source, playing)

        assertFalse(scanner.scan().candidates.single().playingNow)

        playing.result = PlayingAppsResult.Available(setOf("com.example.player"))
        assertTrue(scanner.scan().candidates.single().playingNow)
        assertEquals(1, source.reads)
    }

    @Test
    fun `the cost of the pass is measured and reported`() = runTest {
        val source = FakeAppSource(available(app("com.example.player", permission = true), scanned = 412))
        var now = 100L
        val scanner = scanner(source, clock = { now.also { now += 37 } })

        val scan = scanner.scan()
        assertEquals(412, scan.scannedPackages)
        assertEquals(37, scan.durationMs)
        // The reused scan reports the same measured cost, not a fresh zero.
        assertEquals(37, scanner.scan().durationMs)
    }

    // ---- degraded paths -----------------------------------------------------

    @Test
    fun `an unreadable package list reports cannot-check, never an all-clear`() = runTest {
        val source = FakeAppSource(InstalledAppReadResult.Unavailable("QUERY_ALL_PACKAGES revoked"))
        val scan = scanner(source).scan()

        assertFalse(scan.available)
        assertTrue(scan.isEmpty)
        assertTrue(
            "the reason has to reach the user",
            scan.unavailableReason.orEmpty().contains("QUERY_ALL_PACKAGES revoked"),
        )
        assertTrue(scan.unavailableReason.orEmpty().startsWith("Cannot check"))
    }

    @Test
    fun `a package manager that throws degrades instead of crashing`() = runTest {
        val source = FakeAppSource(
            available(app("com.example.player", permission = true)),
            throwOnRead = SecurityException("dead system process"),
        )
        val scan = scanner(source).scan()

        assertFalse(scan.available)
        assertTrue(scan.unavailableReason.orEmpty().contains("SecurityException"))
    }

    @Test
    fun `a failed pass is not cached, so the next attempt tries again`() = runTest {
        val source = FakeAppSource(InstalledAppReadResult.Unavailable("nope"))
        val scanner = scanner(source)

        assertFalse(scanner.scan().available)
        assertFalse(scanner.hasCachedPass)

        source.result = available(app("com.example.player", permission = true))
        assertTrue(scanner.scan().available)
        assertEquals(2, source.reads)
    }

    @Test
    fun `an unavailable playback probe still lists apps but admits it cannot say what plays`() =
        runTest {
            val source = FakeAppSource(available(app("com.pittvandewitt.wavelet", panel = true)))
            val playing = FakePlayingApps(PlayingAppsResult.Unavailable("needs Android 8"))
            val scan = scanner(source, playing).scan()

            assertTrue(scan.available)
            assertEquals(1, scan.candidates.size)
            assertFalse(scan.candidates.single().playingNow)
            assertFalse(scan.playbackKnown)
            assertEquals("needs Android 8", scan.playbackNote)
        }

    @Test
    fun `a playback probe that throws is treated as unknown, not as silence`() = runTest {
        val source = FakeAppSource(available(app("com.pittvandewitt.wavelet", panel = true)))
        val throwing = object : PlayingAppsSource {
            override suspend fun playingPackages(): PlayingAppsResult =
                throw IllegalStateException("audio service died")
        }
        val scan = EqCandidateScanner(source, throwing, "dev.dankyeeter.btdashboard").scan()

        assertTrue(scan.available)
        assertFalse(scan.playbackKnown)
        assertNotNull(scan.playbackNote)
    }
}
