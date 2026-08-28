package dev.dankyeeter.btdashboard.system.attach

import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.audio.eq.SystemEqualizer
import dev.dankyeeter.btdashboard.audio.eq.SystemEqualizerFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lifecycle of the effect instances themselves: every `DynamicsProcessing`
 * this app creates must end up either attached or closed, and never both nor
 * neither.
 *
 * ## Why this file exists
 *
 * On 2026-08-28 the owner measured `btif_a2dp_source` counting ~49 encoder
 * underflows per second, continuously, on an LDAC 96 kHz link with the system
 * EQ attached. Switching the EQ off stopped it instantly; switching it back on —
 * a **fresh** attach of the same chain, same music, same session — was clean.
 * So a long-lived chain starved the Bluetooth encoder and a fresh one did not,
 * and the toggle that proved it destroyed the state that caused it.
 *
 * The leading explanation is that the long-lived chain was not one effect but
 * several, stacked on the same session, each running a full multiband
 * correction at 96 kHz. This module had two paths that produce exactly that,
 * and both are pinned below. Neither is a hypothetical: they need two threads
 * to be inside the attachment code at once, and four different threads call
 * into it — the main thread through the session broadcast, the harvester's IO
 * scope through a harvest *and* separately through its 2.5 s settle check, and
 * the foreground service through the volume follower.
 *
 * The tests are deterministic. Rather than hammering two threads and hoping to
 * hit the window, each one pins the exact interleaving by pausing inside the
 * effect factory — the one place where a real attach genuinely does take
 * milliseconds — and letting the other thread run into it.
 */
class EqAttachmentLifecycleTest {

    private val settings = EqSettings.FLAT.copy(enabled = true)

    // ---- session strategy ----------------------------------------------------

    /**
     * The interleaving that leaks.
     *
     * `reattachAll` closes the old effect, then builds a replacement, then
     * stores it. A session broadcast (or a second harvest) for the same session
     * that lands between the close and the store finds the key absent, builds
     * its *own* effect and stores that — and `reattachAll`'s store then
     * overwrites the entry without closing what was there. The overwritten
     * effect is still registered in AudioFlinger on that session and nothing
     * holds a reference to it any more, so nothing can ever close it.
     */
    @Test
    fun `a session opening inside a re-attach cannot leave an unclosed effect`() {
        val factory = RecordingFactory()
        val strategy = SessionAttachmentStrategy(factory)
        strategy.activate(settings)
        assertTrue(strategy.onSessionOpened(SESSION))
        assertEquals(1, factory.created.size)

        val intruder = Thread { strategy.onSessionOpened(SESSION) }
        factory.onCreate = {
            // Fire once, from inside the rebuild, exactly where the map has no
            // entry for the session.
            factory.onCreate = null
            intruder.start()
            // Long enough that an unserialised strategy would have finished
            // creating and storing its own effect by now.
            intruder.join(INTRUSION_WINDOW_MS)
        }

        assertTrue(strategy.reattachAll())
        intruder.join(JOIN_TIMEOUT_MS)

        assertEquals(
            "an effect was created and then dropped without being closed: " +
                factory.created.map { "session=${it.sessionId} closed=${it.closed}" },
            1,
            factory.created.count { !it.closed },
        )
        assertEquals(
            "the surviving effect must be the one the strategy still holds",
            setOf(SESSION),
            (strategy.status as AttachmentStatus.ActiveSessions).sessionIds,
        )
    }

    /**
     * The invariant that matters most, stated as a race: **after `deactivate()`
     * nothing this strategy ever built is still open.**
     *
     * The failing interleaving is a teardown landing while an attach is inside
     * the factory. `deactivate` closes what is in the map — which is nothing
     * yet — and clears it; the attach then stores its brand-new effect into the
     * emptied map. That effect is attached to a real player, in a strategy that
     * is no longer in use, and the only code that would ever close it has
     * already run. It sits in AudioFlinger's chain for that session for the rest
     * of the process's life, which is precisely the accumulation the encoder
     * starvation is suspected of.
     */
    @Test
    fun `a teardown landing inside an attach leaves nothing open`() {
        val factory = RecordingFactory()
        val strategy = SessionAttachmentStrategy(factory)
        strategy.activate(settings)

        val intruder = Thread { strategy.deactivate() }
        factory.onCreate = {
            factory.onCreate = null
            intruder.start()
            intruder.join(INTRUSION_WINDOW_MS)
        }

        strategy.onSessionOpened(SESSION)
        intruder.join(JOIN_TIMEOUT_MS)

        assertEquals(AttachmentStatus.Inactive, strategy.status)
        assertEquals(
            "an effect outlived the teardown with nobody left to close it: " +
                factory.created.map { "session=${it.sessionId} closed=${it.closed}" },
            0,
            factory.created.count { !it.closed },
        )
    }

    /**
     * Nothing may build an effect while the strategy is not in use.
     *
     * An in-flight harvest or a session broadcast can land after `deactivate()`
     * has closed everything. An effect built then is attached to a real player,
     * switched off (the strategy is holding FLAT), and owned by nobody —
     * `deactivate()` has already been and gone, so it stays in AudioFlinger's
     * chain for the life of the process.
     */
    @Test
    fun `a session opening after deactivate creates nothing`() {
        val factory = RecordingFactory()
        val strategy = SessionAttachmentStrategy(factory)
        strategy.activate(settings)
        strategy.onSessionOpened(SESSION)
        strategy.deactivate()

        assertFalse("an inactive strategy must refuse, not attach", strategy.onSessionOpened(SESSION))
        assertEquals("no effect may be built after deactivate", 1, factory.created.size)
        assertTrue("everything must be closed", factory.created.all { it.closed })
        assertEquals(AttachmentStatus.Inactive, strategy.status)
    }

    /**
     * A dead effect under a session's key is not an attachment.
     *
     * `containsKey` used to be the whole check, so once an effect died — the
     * session went away and came back with the same id, which is what a player
     * reusing ids does — the session was reported as handled forever and the EQ
     * reached nothing on it.
     */
    @Test
    fun `a dead effect is replaced rather than reported as still attached`() {
        val factory = RecordingFactory()
        val strategy = SessionAttachmentStrategy(factory)
        strategy.activate(settings)
        assertTrue(strategy.onSessionOpened(SESSION))

        factory.created.single().alive = false
        assertTrue(strategy.onSessionOpened(SESSION))

        assertEquals("a replacement should have been built", 2, factory.created.size)
        assertTrue("the corpse must be closed, not dropped", factory.created.first().closed)
        assertEquals(1, factory.created.count { !it.closed })
    }

    /**
     * Pruning a dead effect must close it, not merely forget it.
     *
     * `isAlive` goes false for reasons other than `close()` — the underlying
     * effect marks itself dead the first time a framework call throws, and the
     * native effect is *not* released at that point. Dropping the last reference
     * then means nothing will ever ask for the release, and the instance stays
     * in AudioFlinger's chain for that session.
     */
    @Test
    fun `a dead effect is closed when it is pruned, not just forgotten`() {
        val factory = RecordingFactory()
        val strategy = SessionAttachmentStrategy(factory)
        strategy.activate(settings)
        assertTrue(strategy.onSessionOpened(SESSION))

        // As a framework call throwing does it: dead, but never released.
        factory.created.single().alive = false
        strategy.update(settings)

        assertEquals(AttachmentStatus.Unavailable::class, strategy.status::class)
        assertTrue(
            "a pruned effect must have been asked to release itself",
            factory.created.single().closed,
        )
    }

    @Test
    fun `a refused re-attach is reported rather than silently dropping the session`() {
        val factory = RecordingFactory()
        val strategy = SessionAttachmentStrategy(factory)
        strategy.activate(settings)
        assertTrue(strategy.onSessionOpened(SESSION))

        // AudioFlinger refusing while it still holds an internal effect on the
        // session — observed on the device right after a process was killed.
        factory.refuse = true
        assertFalse(
            "a session that came back attached to nothing must be reported",
            strategy.reattachAll(),
        )
        assertTrue("the old effect was closed on the way", factory.created.single().closed)
    }

    // ---- global strategy -----------------------------------------------------

    /**
     * Two `activate` calls in flight at once must produce one effect.
     *
     * The class already documents that a *sequential* second activate reuses the
     * live attachment, because stacking effects on the output mix applies the
     * correction curve twice — a wrong-audio bug for this app. Concurrently that
     * rule did not hold: the field was read, found empty, and written by both.
     * The foreground service applying settings while the Bluetooth connect
     * watcher calls `ensureAttached` is exactly that pair.
     */
    @Test
    fun `two concurrent activates leave one global effect`() {
        val factory = RecordingFactory()
        val strategy = GlobalAttachmentStrategy(factory)

        val intruder = Thread { strategy.activate(settings) }
        factory.onCreate = {
            factory.onCreate = null
            intruder.start()
            intruder.join(INTRUSION_WINDOW_MS)
        }

        strategy.activate(settings)
        intruder.join(JOIN_TIMEOUT_MS)

        assertEquals(
            "a second DynamicsProcessing on the output mix doubles the curve: " +
                factory.created.map { "session=${it.sessionId} closed=${it.closed}" },
            1,
            factory.created.size,
        )
        assertEquals(
            AttachmentStatus.ActiveGlobal(GlobalAttachmentStrategy.GLOBAL_SESSION_ID),
            strategy.status,
        )
    }

    // ---- controller transitions ----------------------------------------------

    /**
     * A harvest that completes during the switch into global mode must attach
     * nothing.
     *
     * The teardown order used to be: close every session effect, *then* move
     * `active` onto the global strategy. A harvest landing between those two
     * lines still saw the session strategy as the owner and built effects on
     * the players — after the only code that would ever close them had run.
     */
    @Test
    fun `a harvest landing during the switch to global attaches nothing`() {
        val factory = RecordingFactory()
        val session = SessionAttachmentStrategy(factory)
        var reaches = false
        val controller = EqController(
            global = FakeGlobal(AttachmentStatus.ActiveGlobal(0)),
            session = session,
            globalAttachReachesOutput = { reaches },
        )

        controller.apply(settings)
        assertTrue(controller.onHarvestedSessions(setOf(SESSION)))
        assertEquals(1, factory.created.size)

        // Bluetooth unplugged: the output mix is audible again, so the
        // controller takes the wider mode back and drops session mode.
        reaches = true
        controller.apply(settings)

        assertTrue(
            "the harvest must be refused once the session strategy is retired",
            controller.onHarvestedSessions(setOf(SESSION)),
        )
        assertEquals("no effect may be created after the switch", 1, factory.created.size)
        assertTrue("and the one from session mode must be closed", factory.created.all { it.closed })
    }

    @Test
    fun `a harvest landing after deactivate attaches nothing`() {
        val factory = RecordingFactory()
        val session = SessionAttachmentStrategy(factory)
        val controller = EqController(
            global = FakeGlobal(AttachmentStatus.ActiveGlobal(0)),
            session = session,
            globalAttachReachesOutput = { false },
        )

        controller.apply(settings)
        controller.onHarvestedSessions(setOf(SESSION))
        controller.deactivate()

        assertTrue(controller.onHarvestedSessions(setOf(SESSION, 9001)))
        assertEquals(1, factory.created.size)
        assertTrue(factory.created.all { it.closed })
        assertEquals(AttachmentStatus.Inactive, controller.status.value)
    }

    /**
     * The session strategy must carry real settings before *anything* can reach
     * it — the harvester and the manifest receiver alike.
     *
     * An effect built while the strategy still holds `EqSettings.FLAT` lands on
     * the track switched off and stays that way, with the status reporting
     * success. That was already known for the harvester and fixed there; the
     * receiver had the identical window and was simply never blamed for it,
     * because it only opens when a *different* player announces itself in the
     * same instant.
     */
    @Test
    fun `neither the receiver nor the harvester is armed before activate`() {
        val order = mutableListOf<String>()
        val factory = RecordingFactory()
        val session = SessionAttachmentStrategy(factory)
        val controller = EqController(
            global = FakeGlobal(AttachmentStatus.ActiveGlobal(0)),
            session = session,
            globalAttachReachesOutput = { false },
            setSessionReceiverEnabled = { if (it) order += "receiver" },
            setSessionHarvestEnabled = { if (it) order += "harvest" },
        )

        controller.apply(settings)

        assertEquals(listOf("receiver", "harvest"), order)
        assertTrue(
            "activate must have run before either was armed",
            session.status !is AttachmentStatus.Inactive,
        )
    }

    /**
     * The repair reports whether it worked, so a session it could not rebuild is
     * offered again.
     *
     * `reattachAll` closes each effect before building its replacement, so a
     * refused rebuild leaves that session attached to nothing. The harvester
     * remembers what it last reported; without this answer it would compare the
     * next harvest equal, return early, and leave the EQ off that player until
     * the set of players happened to change.
     */
    @Test
    fun `reasserting reports a refused rebuild instead of claiming success`() {
        val factory = RecordingFactory()
        val session = SessionAttachmentStrategy(factory)
        val controller = EqController(
            global = FakeGlobal(AttachmentStatus.ActiveGlobal(0)),
            session = session,
            globalAttachReachesOutput = { false },
        )
        controller.apply(settings)
        controller.onHarvestedSessions(setOf(SESSION))

        assertTrue("a clean rebuild reports success", controller.reassertCurrentSettings())

        factory.refuse = true
        assertFalse(
            "a rebuild AudioFlinger refused must not be reported as done",
            controller.reassertCurrentSettings(),
        )
    }

    // ---- doubles -------------------------------------------------------------

    /**
     * Records every effect it ever built and whether it was closed, which is the
     * whole invariant this file is about.
     *
     * [onCreate] runs *before* the effect is built, so a test can drive another
     * thread into the exact gap a real attach leaves open: creating a
     * `DynamicsProcessing` is a binder round-trip into audioserver and takes
     * milliseconds on the device.
     */
    private class RecordingFactory : SystemEqualizerFactory {
        val created = mutableListOf<FakeEqualizer>()

        /** When true, stands in for AudioFlinger refusing the effect. */
        @Volatile
        var refuse = false

        @Volatile
        var onCreate: ((Int) -> Unit)? = null

        override fun create(sessionId: Int): SystemEqualizer? {
            onCreate?.invoke(sessionId)
            if (refuse) return null
            return FakeEqualizer(sessionId).also { synchronized(created) { created += it } }
        }
    }

    private class FakeEqualizer(override val sessionId: Int) : SystemEqualizer {
        @Volatile var alive = true
        @Volatile var closed = false
            private set

        override val isAlive: Boolean get() = alive && !closed

        override fun apply(settings: EqSettings) = Unit
        override fun setBandGain(ear: Ear, bandIndex: Int, gainDb: Float) = Unit
        override fun setPreGain(db: Float) = Unit
        override fun setEnabled(enabled: Boolean) = Unit

        override fun close() {
            closed = true
        }
    }

    private class FakeGlobal(private val onActivate: AttachmentStatus) : EqAttachmentStrategy {
        override val kind = AttachmentKind.GLOBAL
        override var status: AttachmentStatus = AttachmentStatus.Inactive
            private set

        override fun activate(settings: EqSettings): AttachmentStatus {
            status = onActivate
            return onActivate
        }

        override fun update(settings: EqSettings) = Unit

        override fun deactivate() {
            status = AttachmentStatus.Inactive
        }
    }

    private companion object {
        /** A harvested Tidal session id, as the device printed it. */
        const val SESSION = 8009

        /**
         * How long the thread inside the factory waits for the intruder.
         *
         * Long enough that an unserialised strategy would certainly have
         * finished its own create-and-store in the window, so a failure is a
         * real leak rather than a scheduling accident; short enough that four
         * tests paying it stay quick.
         */
        const val INTRUSION_WINDOW_MS = 400L

        /** Generous, because it is only ever reached after the lock is free. */
        const val JOIN_TIMEOUT_MS = 5_000L
    }
}
