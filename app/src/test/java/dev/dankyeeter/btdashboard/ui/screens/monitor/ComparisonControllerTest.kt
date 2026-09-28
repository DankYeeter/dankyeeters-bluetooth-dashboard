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
import org.junit.After
import org.junit.Test

/** The comparison's phases against a fed reading sequence (AD-038 S3-6 test cases). */
class ComparisonControllerTest {

    private var nowMs = 0L
    private var dropoutTotal = 0L
    private var book: ConditionBook = books("usb")
    private val pins = mutableListOf<String?>()
    private var family = CodecFamily.LDAC
    private var quality = 1000L

    /** The handle is process-wide; no test may leave a comparison behind for the next. */
    @After
    fun releaseHandle() = ActiveComparison.set(null)

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

    /** Playing on [family] at [quality] (pinned 990 unless a test moves it), the counter at [dropoutTotal]. */
    private fun reading() = LinkLiveSnapshot(
        timestampMs = nowMs,
        device = LiveDeviceSnapshot(address = "XX:XX:XX:XX:37:8F", isConnected = true, isPlaying = true),
        codec = LiveCodecSnapshot(family = family, sampleRateHz = 96_000, codecSpecific1 = quality),
        ldac = if (family == CodecFamily.LDAC) {
            LdacState.from(quality, 96_000, LdacStackState(qualityMode = if (quality == ABR) "ABR" else "HIGH", transmissionKbps = 990))
        } else {
            null
        },
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

    /** Chooses [measure] (A5 unless a test says otherwise) and pins; arm A is counting. */
    private fun TestScope.intoArmA(control: ComparisonController, measure: Measure = Measure.USB_CABLE_OFF) {
        control.feed()
        control.onMeasure(measure)
        control.onCompare()
        runCurrent()
        assertEquals(ComparisonPhase.ARM_A, control.ui.value.phase)
    }

    /** Chooses [measure], pins, and counts arm A to its end with five dropouts; stops in Instruct. */
    private fun TestScope.throughArmA(control: ComparisonController, measure: Measure = Measure.USB_CABLE_OFF) {
        intoArmA(control, measure)
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

    /** AD-046: 2.4 GHz is A2 not carried out, so arm B waits. */
    @Test
    fun `Wi-Fi still on 2_4 GHz before arm B does not start arm B`() = runTest {
        val control = controller()
        throughArmA(control, Measure.NO_2_4_GHZ_WIFI)

        book = books("usb") + (Condition.WIFI_BAND to ConditionValue.Read("2.4 GHz"))
        control.onCheck()

        assertEquals(ComparisonPhase.INSTRUCT, control.ui.value.phase)
        assertNull("arm B has not started", control.ui.value.running)
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
        assertNull("a failed pin holds no comparison", ActiveComparison.held.value)
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

    /** AD-040 part 2: an arm that saw ABR is no longer on the pinned link; today it started again. */
    @Test
    fun `arm A seeing ABR ends the comparison instead of counting again`() = runTest {
        val control = controller()
        intoArmA(control)
        repeat(10) { control.feed() }

        quality = ABR
        control.feed()

        val state = control.ui.value
        assertEquals(ComparisonPhase.CHOOSE, state.phase)
        assertEquals(ComparisonEnd.LinkChanged("A", RunEnd.QUALITY_CHANGED), state.ended)
        assertEquals(Measure.USB_CABLE_OFF, state.measure)
        assertNull("no new arm", state.running)
        assertNull(ActiveComparison.held.value)
    }

    /** Probe P5: twenty readings off LDAC were twenty restarts; now they are one end. */
    @Test
    fun `the codec leaving LDAC ends the comparison once`() = runTest {
        val control = controller()
        intoArmA(control)
        repeat(3) { control.feed() }

        family = CodecFamily.AAC
        control.feed()
        val ended = control.ui.value
        repeat(19) { control.feed() }

        assertEquals(ComparisonEnd.LinkChanged("A", RunEnd.CODEC_CHANGED), ended.ended)
        assertEquals("later readings change nothing", ended, control.ui.value)
    }

    @Test
    fun `a gap that comes back on another link ends the comparison`() = runTest {
        val control = controller()
        intoArmA(control)
        repeat(30) { control.feed() }

        quality = ABR
        control.feed(stepMs = GAP_MS)

        assertEquals(ComparisonEnd.LinkChanged("A", RunEnd.READING_GAP), control.ui.value.ended)
        assertNull(control.ui.value.running)
    }

    @Test
    fun `a link other than arm A's at the check ends the comparison instead of starting arm B`() = runTest {
        val control = controller()
        throughArmA(control)
        book = books("none")

        quality = ABR
        control.feed()
        control.onCheck()

        val state = control.ui.value
        assertEquals(ComparisonPhase.CHOOSE, state.phase)
        assertEquals(ComparisonEnd.LinkChanged("B", null), state.ended)
        assertNull("neither arm counts", state.armA)
        assertNull(state.running)
    }

    /** AD-040 part 3 through AD-039's handle: the way back reaches the comparison without the screen. */
    @Test
    fun `ending through the process-wide handle stops the comparison and keeps the measure`() = runTest {
        val control = controller()
        intoArmA(control)
        assertEquals(ComparisonPhase.ARM_A, ActiveComparison.held.value?.phase)

        ActiveComparison.end(ComparisonEnd.SettingsPutBack)

        val state = control.ui.value
        assertEquals(ComparisonPhase.CHOOSE, state.phase)
        assertEquals(ComparisonEnd.SettingsPutBack, state.ended)
        assertEquals(Measure.USB_CABLE_OFF, state.measure)
        assertNull(ActiveComparison.held.value)

        control.onMeasure(Measure.NO_DISCOVERY)
        assertNull("the line stands until a measure is tapped", control.ui.value.ended)
    }

    private companion object {
        const val ABR = 1003L
        const val STEP_MS = 1_000L
        const val GAP_MS = 130_000L
        const val MAX_READINGS = 2_000
    }
}
