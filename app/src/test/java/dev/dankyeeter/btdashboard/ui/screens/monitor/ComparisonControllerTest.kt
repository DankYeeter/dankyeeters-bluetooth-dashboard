package dev.dankyeeter.btdashboard.ui.screens.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxStats
import dev.dankyeeter.btdashboard.monitor.link.live.LdacStackState
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveCodecSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveDeviceSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.RunEnd
import dev.dankyeeter.btdashboard.monitor.optimize.ARM_TARGET_MS
import dev.dankyeeter.btdashboard.monitor.optimize.ComparisonResult
import dev.dankyeeter.btdashboard.monitor.optimize.Condition
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionBook
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionValue
import dev.dankyeeter.btdashboard.monitor.optimize.Measure
import dev.dankyeeter.btdashboard.monitor.optimize.Verdict
import dev.dankyeeter.btdashboard.system.devices.CodecApplyOutcome
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The comparison's phases against a fed reading sequence (AD-038 S3-6 test cases). */
class ComparisonControllerTest {

    private var nowMs = 0L
    private var dropoutTotal = 0L
    private var book: ConditionBook = books("usb")
    private val pins = mutableListOf<String?>()

    private fun books(usb: String): ConditionBook = mapOf(
        Condition.WIFI_RADIO to ConditionValue.Read("on"),
        Condition.WIFI_SCAN_ALWAYS to ConditionValue.Read("off"),
        Condition.USB_POWER to ConditionValue.Read(usb),
        Condition.OTHER_ACL_LINKS to ConditionValue.Read("0"),
        Condition.DISCOVERY_SEEN to ConditionValue.Read("no"),
    )

    private fun TestScope.controller(outcome: CodecApplyOutcome = CodecApplyOutcome.Applied("LDAC 990")) =
        ComparisonController(
            scope = this,
            pin = { address -> pins += address; outcome },
            readBook = { _, _ -> book },
        )

    /** Pinned 990, playing, the counter at [dropoutTotal]. */
    private fun reading() = LinkLiveSnapshot(
        timestampMs = nowMs,
        device = LiveDeviceSnapshot(address = "XX:XX:XX:XX:37:8F", isConnected = true, isPlaying = true),
        codec = LiveCodecSnapshot(family = CodecFamily.LDAC, sampleRateHz = 96_000, codecSpecific1 = 1000L),
        ldac = LdacState.from(1000L, 96_000, LdacStackState(qualityMode = "HIGH", transmissionKbps = 990)),
        tx = A2dpTxStats(dropoutCount = dropoutTotal),
    )

    private fun ComparisonController.feed(stepMs: Long = STEP_MS) {
        nowMs += stepMs
        onReading(reading(), STEP_MS)
    }

    /** Feeds readings until the running arm has reached its target; [dropoutAt] adds one dropout at those readings. */
    private fun ComparisonController.feedUntilTarget(dropoutAt: Set<Int> = emptySet()) {
        var i = 0
        while (ui.value.running?.run?.end != RunEnd.TARGET_REACHED) {
            check(i < MAX_READINGS) { "the arm never reached its target" }
            if (i in dropoutAt) dropoutTotal++
            feed()
            i++
        }
    }

    /** Chooses A5, pins, and counts arm A to its end with five dropouts; stops in Instruct. */
    private fun TestScope.throughArmA(control: ComparisonController) {
        control.feed()
        control.onMeasure(Measure.USB_CABLE_OFF)
        control.onCompare()
        runCurrent()
        assertEquals(ComparisonPhase.ARM_A, control.ui.value.phase)
        control.feedUntilTarget(dropoutAt = setOf(100, 200, 300, 400, 500))

        // AK-T047-9: the target line stands for one reading before the phase changes.
        assertEquals(ComparisonPhase.ARM_A, control.ui.value.phase)
        assertNotNull(control.ui.value.armA)
        control.feed()
        assertEquals(ComparisonPhase.INSTRUCT, control.ui.value.phase)
    }

    @Test
    fun `five dropouts before and none after with USB off read back is fewer detected`() = runTest {
        val control = controller()
        throughArmA(control)

        book = books("none")
        control.onCheck()
        assertEquals(ComparisonPhase.ARM_B, control.ui.value.phase)
        assertEquals(control.ui.value.armA?.run?.observedMs, control.ui.value.running?.run?.targetMs)
        control.feedUntilTarget()
        control.feed()

        val state = control.ui.value
        assertEquals(ComparisonPhase.RESULT, state.phase)
        assertEquals(5L, state.armA?.run?.dropouts)
        assertEquals(0L, state.armB?.run?.dropouts)
        assertEquals(Verdict.FEWER_DETECTED, (state.result as ComparisonResult.Compared).verdict)
        assertEquals("the pin was asked once, for the shown device", listOf("XX:XX:XX:XX:37:8F"), pins)
    }

    @Test
    fun `USB still read as usb before arm B does not start arm B`() = runTest {
        val control = controller()
        throughArmA(control)
        val armA = control.ui.value.armA

        control.onCheck()
        control.feed()

        val state = control.ui.value
        assertEquals(ComparisonPhase.INSTRUCT, state.phase)
        assertNull("arm B has not started", state.running)
        assertEquals(ConditionValue.Read("usb"), state.readBack?.get(Condition.USB_POWER))
        assertSame(armA, state.armA)
    }

    @Test
    fun `a pin not observed starts no arm`() = runTest {
        val control = controller(CodecApplyOutcome.NotObserved("LDAC 660", "waited 3 s"))
        control.feed()
        control.onMeasure(Measure.USB_CABLE_OFF)
        control.onCompare()
        runCurrent()
        repeat(10) { control.feed() }

        val state = control.ui.value
        assertEquals(ComparisonPhase.PIN_AND_CHECK, state.phase)
        assertNull(state.running)
        assertEquals("the link still reads LDAC 660: waited 3 s", state.pinFailure)
    }

    @Test
    fun `arm B ending on a reading gap is counted again and arm A stays`() = runTest {
        val control = controller()
        throughArmA(control)
        val armA = control.ui.value.armA
        book = books("none")
        control.onCheck()
        repeat(60) { control.feed() }

        control.feed(stepMs = GAP_MS)

        val state = control.ui.value
        assertEquals(ComparisonPhase.ARM_B, state.phase)
        assertSame(armA, state.armA)
        assertEquals(RunEnd.READING_GAP, state.restart?.end)
        assertEquals("B", state.restart?.armName)
        assertEquals(0L, state.running?.run?.observedMs)
        assertEquals(armA?.run?.observedMs, state.running?.run?.targetMs)
    }

    @Test
    fun `a measure already in place starts nothing and pins nothing`() = runTest {
        book = books("none")
        val control = controller()
        control.feed()
        control.onMeasure(Measure.USB_CABLE_OFF)
        control.onCompare()
        runCurrent()

        val state = control.ui.value
        assertEquals(ComparisonPhase.CHOOSE, state.phase)
        assertEquals(Measure.USB_CABLE_OFF, state.alreadyApplies)
        assertTrue(pins.isEmpty())
    }

    @Test
    fun `arm A ending early is counted again toward the full target`() = runTest {
        val control = controller()
        control.feed()
        control.onMeasure(Measure.NO_DISCOVERY)
        control.onCompare()
        runCurrent()
        repeat(30) { control.feed() }

        control.feed(stepMs = GAP_MS)

        val state = control.ui.value
        assertEquals(ComparisonPhase.ARM_A, state.phase)
        assertEquals(ArmRestart("A", RunEnd.READING_GAP, 29 * STEP_MS), state.restart)
        assertEquals(ARM_TARGET_MS, state.running?.run?.targetMs)
        assertNull(state.armA)
    }

    private companion object {
        const val STEP_MS = 1_000L
        const val GAP_MS = 130_000L
        const val MAX_READINGS = 2_000
    }
}
