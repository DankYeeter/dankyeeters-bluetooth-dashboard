package dev.dankyeeter.btdashboard.system.attach

import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.audio.eq.SystemEqualizer
import dev.dankyeeter.btdashboard.audio.eq.SystemEqualizerFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fallback that exists because an attached equaliser can still be silent.
 *
 * Measured over Bluetooth on a Pixel with an 18 dB cut: on the output mix the
 * sound moved by 0,2 dB on every band, on a track's own session by 6 to 9 dB,
 * against a measured noise floor of about 3 dB. The global attach nevertheless
 * *succeeds* - it reports itself active, its gains read back correctly, and it
 * does nothing. So the controller cannot learn this from the strategy's own
 * status; it has to ask beforehand.
 *
 * Spatial Audio was the first suspect and the wrong one: the A2DP route runs
 * through a SPATIALIZER output thread, which looked conclusive, but switching
 * Spatial Audio off changed nothing over three further runs. The rule that
 * matches the measurements is about the **route**, not the spatializer - hence
 * [OutputMixReachGate] and this test's name.
 *
 * These cases are pinned because the failure they prevent is invisible: the app
 * would look attached and correct nothing.
 */
class EqControllerReachTest {

    private val settings = EqSettings.FLAT.copy(enabled = true)

    @Test
    fun `uses the global mix when it reaches the output`() {
        val global = FakeGlobal(AttachmentStatus.ActiveGlobal(sessionId = 0))
        val controller = controller(global, globalReaches = true)

        controller.apply(settings)

        assertTrue("global should have been used", global.activated)
        assertEquals(AttachmentStatus.ActiveGlobal(0), controller.status.value)
    }

    @Test
    fun `falls back to session mode when the output mix cannot be heard`() {
        // The point of the test: global would have reported success if asked.
        val global = FakeGlobal(AttachmentStatus.ActiveGlobal(sessionId = 0))
        val controller = controller(global, globalReaches = false)

        controller.apply(settings)

        assertFalse("global must not be attached when it cannot be heard", global.activated)
        assertTrue(
            "session mode should be active, got ${controller.status.value}",
            controller.status.value is AttachmentStatus.Unavailable ||
                controller.status.value is AttachmentStatus.ActiveSessions,
        )
    }

    @Test
    fun `switches the manifest receiver on only in session mode`() {
        val toggles = mutableListOf<Boolean>()

        controller(FakeGlobal(AttachmentStatus.ActiveGlobal(0)), true, toggles::add).apply(settings)
        assertEquals("global mode must not arm the receiver", listOf(false), toggles)

        toggles.clear()
        controller(FakeGlobal(AttachmentStatus.ActiveGlobal(0)), false, toggles::add).apply(settings)
        assertEquals("session mode needs the receiver", listOf(true), toggles)
    }

    @Test
    fun `re-asks on every apply, so unplugging Bluetooth restores global reach`() {
        var reaches = false
        val global = FakeGlobal(AttachmentStatus.ActiveGlobal(0))
        val controller = controller(global, globalReachesProvider = { reaches })

        controller.apply(settings)
        assertFalse(global.activated)

        // Route moved back to the speaker, where the output mix demonstrably
        // works. A gate consulted only once would leave the user in session
        // mode forever.
        reaches = true
        controller.apply(settings)
        assertTrue("global reach must be reclaimed once it works again", global.activated)
    }

    @Test
    fun `connecting Bluetooth moves a live global attach into session mode`() {
        var reaches = true
        val global = FakeGlobal(AttachmentStatus.ActiveGlobal(0))
        val controller = controller(global, globalReachesProvider = { reaches })

        controller.apply(settings)
        assertTrue("should start out global on the speaker", global.activated)

        // The connect path calls ensureAttached, not apply. The global effect is
        // still alive at this point - it just stopped being audible, which no
        // status field reports.
        reaches = false
        controller.ensureAttached()

        assertFalse("a silent global attach must not be left in place", global.activated)
    }

    @Test
    fun `disconnecting Bluetooth wins the global reach back`() {
        var reaches = false
        val global = FakeGlobal(AttachmentStatus.ActiveGlobal(0))
        val controller = controller(global, globalReachesProvider = { reaches })

        controller.apply(settings)
        assertFalse(global.activated)

        reaches = true
        controller.ensureAttached()

        assertTrue("session mode must not outlive the reason for it", global.activated)
    }

    @Test
    fun `harvesting only starts once the session strategy carries real settings`() {
        val order = mutableListOf<String>()
        val session = SessionAttachmentStrategy(NoEqualizers)
        val controller = EqController(
            global = FakeGlobal(AttachmentStatus.ActiveGlobal(0)),
            session = session,
            globalAttachReachesOutput = { false },
            setSessionReceiverEnabled = {},
            setSessionHarvestEnabled = { if (it) order += "harvest" },
        )

        controller.apply(settings.copy(enabled = true))

        // The harvester can attach to a player in milliseconds. Started before
        // activate(), it applies EqSettings.FLAT - the effect lands on the
        // track switched off, and the status still says everything is fine.
        assertEquals(listOf("harvest"), order)
        assertTrue(
            "activate must have run before harvesting began",
            session.status !is AttachmentStatus.Inactive,
        )
    }

    @Test
    fun `a failed attach is not remembered as done`() {
        val session = SessionAttachmentStrategy(RefusingFactory)
        val controller = EqController(
            global = FakeGlobal(AttachmentStatus.ActiveGlobal(0)),
            session = session,
            globalAttachReachesOutput = { false },
        )
        controller.apply(settings.copy(enabled = true))

        // AudioFlinger refuses to create an effect while it still holds an
        // internal one on that session - seen on the device straight after a
        // previous process was killed. Reporting success there would retire the
        // session as handled and leave the EQ inert for the rest of the track.
        assertFalse(
            "a refused attach must ask to be retried",
            controller.onHarvestedSessions(setOf(8009)),
        )
    }

    private fun controller(
        global: EqAttachmentStrategy,
        globalReaches: Boolean = true,
        onReceiverToggle: (Boolean) -> Unit = {},
    ) = controller(global, { globalReaches }, onReceiverToggle)

    private fun controller(
        global: EqAttachmentStrategy,
        globalReachesProvider: () -> Boolean,
        onReceiverToggle: (Boolean) -> Unit = {},
    ) = EqController(
        global = global,
        session = SessionAttachmentStrategy(NoEqualizers),
        globalAttachReachesOutput = globalReachesProvider,
        setSessionReceiverEnabled = onReceiverToggle,
    )

    private class FakeGlobal(private val onActivate: AttachmentStatus) : EqAttachmentStrategy {
        var activated = false
            private set

        override val kind = AttachmentKind.GLOBAL
        override var status: AttachmentStatus = AttachmentStatus.Inactive
            private set

        override fun activate(settings: EqSettings): AttachmentStatus {
            activated = true
            status = onActivate
            return onActivate
        }

        override fun update(settings: EqSettings) = Unit

        override fun deactivate() {
            activated = false
            status = AttachmentStatus.Inactive
        }
    }

    /** No session ever announces itself, which is the uninteresting half here. */
    private object NoEqualizers : SystemEqualizerFactory {
        override fun create(sessionId: Int): SystemEqualizer? = null
    }

    /** Stands in for AudioFlinger turning the effect down. */
    private object RefusingFactory : SystemEqualizerFactory {
        override fun create(sessionId: Int): SystemEqualizer? = null
    }
}
