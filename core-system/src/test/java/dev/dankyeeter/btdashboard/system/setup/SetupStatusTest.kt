package dev.dankyeeter.btdashboard.system.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeEnvironment(
    private val satisfied: Set<SetupStep> = emptySet(),
) : SetupEnvironment {
    override fun isSatisfied(step: SetupStep): Boolean = step in satisfied
}

class SetupStatusTest {

    @Test
    fun `a fresh install has every step pending`() {
        val states = SetupStatus.evaluate(FakeEnvironment(), emptySet())

        assertEquals(SetupStep.entries.size, states.size)
        assertTrue(states.all { it.status == SetupStepStatus.PENDING })
        assertEquals("Setup incomplete: ${SetupStep.entries.size} steps left", SetupStatus.summary(states))
    }

    @Test
    fun `a fully satisfied setup says nothing at all`() {
        val states = SetupStatus.evaluate(FakeEnvironment(SetupStep.entries.toSet()), emptySet())

        assertTrue(states.all { it.status == SetupStepStatus.DONE })
        assertNull(SetupStatus.summary(states))
    }

    @Test
    fun `skipped steps stop counting towards the badge`() {
        val skipped = SetupStep.entries.filter { it.optional }.map { it.id }.toSet()
        val states = SetupStatus.evaluate(FakeEnvironment(), skipped)

        assertEquals(
            SetupStep.entries.filterNot { it.optional },
            SetupStatus.outstanding(states).map { it.step },
        )
    }

    @Test
    fun `granting a previously skipped step clears it`() {
        val states = SetupStatus.evaluate(
            FakeEnvironment(satisfied = setOf(SetupStep.MICROPHONE)),
            skipped = setOf(SetupStep.MICROPHONE.id),
        )

        assertEquals(
            SetupStepStatus.DONE,
            states.single { it.step == SetupStep.MICROPHONE }.status,
        )
    }

    /**
     * The other half of the skip rule: an unmet step nobody skipped stays on
     * the badge. This used to also cover a BLOCKED status for steps that could
     * not be completed on this device — no environment ever reported one, so
     * the status was unreachable and went away with it.
     */
    @Test
    fun `an unmet step that was not skipped keeps counting`() {
        val states = SetupStatus.evaluate(FakeEnvironment(), emptySet())

        assertEquals(SetupStepStatus.PENDING, states.single { it.step == SetupStep.HELPER }.status)
        assertTrue(SetupStatus.outstanding(states).any { it.step == SetupStep.HELPER })
    }

    /**
     * Skipping is for optional steps; it must not be a way past the gate.
     * phase() reads the environment, not the skip list - there is no argument
     * a skip could be passed through - so a skipped required step only changes
     * its badge status.
     */
    @Test
    fun `a skipped required step is only marked skipped`() {
        val states = SetupStatus.evaluate(FakeEnvironment(), setOf(SetupStep.BLUETOOTH.id))

        assertEquals(SetupStepStatus.SKIPPED, states.single { it.step == SetupStep.BLUETOOTH }.status)
    }

    @Test
    fun `step ids are unique so skips cannot collide`() {
        assertEquals(SetupStep.entries.size, SetupStep.entries.map { it.id }.toSet().size)
    }

    // ---- the three faces of the app ------------------------------------

    private val permissions = SetupStep.entries.filter { it.need != SetupNeed.ACTIVATION }

    @Test
    fun `a fresh install gets the whole setup`() {
        assertEquals(SetupPhase.FULL_SETUP, SetupStatus.phase(FakeEnvironment()))
    }

    @Test
    fun `permissions granted and no helper is the Activate button, not the process`() {
        val phase = SetupStatus.phase(FakeEnvironment(satisfied = permissions.toSet()))

        assertEquals(SetupPhase.ACTIVATION_ONLY, phase)
    }

    @Test
    fun `everything in place shows no setup at all`() {
        val phase = SetupStatus.phase(FakeEnvironment(satisfied = SetupStep.entries.toSet()))

        assertEquals(SetupPhase.READY, phase)
    }

    @Test
    fun `a missing optional step does not open the process`() {
        val withoutOptional = SetupStep.entries.filterNot { it.optional }.toSet()

        assertEquals(SetupPhase.READY, SetupStatus.phase(FakeEnvironment(satisfied = withoutOptional)))
    }

    /**
     * The case Android creates on its own: it revokes the permissions of unused
     * apps. A remembered "setup done" would carry on claiming otherwise, and
     * the permission it would be lying about is the one the pairing code is
     * typed into.
     */
    @Test
    fun `a revoked permission reopens the process even with a helper running`() {
        val allButNotifications = SetupStep.entries.toSet() - SetupStep.NOTIFICATIONS

        assertEquals(SetupPhase.FULL_SETUP, SetupStatus.phase(FakeEnvironment(satisfied = allButNotifications)))
    }

    @Test
    fun `notifications are required, because the pairing code is typed into one`() {
        assertEquals(SetupNeed.REQUIRED, SetupStep.NOTIFICATIONS.need)
    }
}
