package dev.dankyeeter.btdashboard.system.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeEnvironment(
    private val satisfied: Set<SetupStep> = emptySet(),
    private val unreachable: Set<SetupStep> = emptySet(),
) : SetupEnvironment {
    override fun isSatisfied(step: SetupStep): Boolean = step in satisfied
    override fun isReachable(step: SetupStep): Boolean = step !in unreachable
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
        assertFalse(SetupStatus.hasUnmetRequirements(states))
    }

    @Test
    fun `skipped steps stop counting towards the badge`() {
        val skipped = SetupStep.entries.filter { it.optional }.map { it.id }.toSet()
        val states = SetupStatus.evaluate(FakeEnvironment(), skipped)

        assertEquals(listOf(SetupStep.BLUETOOTH), SetupStatus.outstanding(states).map { it.step })
        assertEquals("Setup incomplete: 1 step left", SetupStatus.summary(states))
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

    @Test
    fun `an unreachable step is blocked, not silently pending`() {
        val states = SetupStatus.evaluate(FakeEnvironment(unreachable = setOf(SetupStep.SHELL_ACCESS)), emptySet())

        assertEquals(SetupStepStatus.BLOCKED, states.single { it.step == SetupStep.SHELL_ACCESS }.status)
        // Blocked still counts as outstanding — the user should know about it.
        assertTrue(SetupStatus.outstanding(states).any { it.step == SetupStep.SHELL_ACCESS })
    }

    @Test
    fun `skipping cannot clear a required step`() {
        val states = SetupStatus.evaluate(FakeEnvironment(), setOf(SetupStep.BLUETOOTH.id))

        assertEquals(SetupStepStatus.SKIPPED, states.single { it.step == SetupStep.BLUETOOTH }.status)
        assertTrue(SetupStatus.hasUnmetRequirements(states))
    }

    @Test
    fun `step ids are unique so skips cannot collide`() {
        assertEquals(SetupStep.entries.size, SetupStep.entries.map { it.id }.toSet().size)
    }
}
