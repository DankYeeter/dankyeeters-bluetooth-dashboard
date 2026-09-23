package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.live.LdacQualityMode
import dev.dankyeeter.btdashboard.monitor.link.live.LdacStackState
import dev.dankyeeter.btdashboard.monitor.link.live.LdacState
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveCodecSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.LiveDeviceSnapshot
import dev.dankyeeter.btdashboard.monitor.link.live.ObservationRun
import dev.dankyeeter.btdashboard.monitor.link.live.PairingFacts
import dev.dankyeeter.btdashboard.monitor.link.live.RunEnd
import dev.dankyeeter.btdashboard.monitor.link.live.RunLink
import dev.dankyeeter.btdashboard.monitor.optimize.ARM_TARGET_MS
import dev.dankyeeter.btdashboard.monitor.optimize.Arm
import dev.dankyeeter.btdashboard.monitor.optimize.ComparisonResult
import dev.dankyeeter.btdashboard.monitor.optimize.Condition
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionBook
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionValue
import dev.dankyeeter.btdashboard.monitor.optimize.Fallback
import dev.dankyeeter.btdashboard.monitor.optimize.Measure
import dev.dankyeeter.btdashboard.monitor.optimize.NotComparable
import dev.dankyeeter.btdashboard.monitor.optimize.Verdict
import dev.dankyeeter.btdashboard.monitor.optimize.Verification
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the comparison section puts on screen (`UI_SPEC.md` T-047 S3-6, AK-T047-6..12, -16). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComparisonSectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val snapshot = LinkLiveSnapshot(
        timestampMs = 1_000L,
        device = LiveDeviceSnapshot(address = RAW_MAC, isConnected = true, isPlaying = true),
        codec = LiveCodecSnapshot(family = CodecFamily.LDAC, sampleRateHz = 96_000, codecSpecific1 = 1000L),
        ldac = LdacState.from(
            1000L,
            96_000,
            LdacStackState(qualityMode = "HIGH", transmissionKbps = 990, effectiveMtu = 883),
        ),
        pairing = PairingFacts(edr = true, threeMbps = true, otherAclLinks = 0, discovering = false),
    )

    private val book: ConditionBook = mapOf(
        Condition.WIFI_RADIO to ConditionValue.Read("on"),
        Condition.WIFI_SCAN_ALWAYS to ConditionValue.Unreadable("not set on this phone"),
        Condition.USB_POWER to ConditionValue.Read("none"),
        Condition.OTHER_ACL_LINKS to ConditionValue.Read("0"),
        Condition.DISCOVERY_SEEN to ConditionValue.Read("no"),
    )

    private val link = RunLink(RAW_MAC, CodecFamily.LDAC, LdacQualityMode.HIGH_QUALITY, false, 96_000)
    private val arm = Arm(
        ObservationRun(observedMs = ARM_TARGET_MS, end = RunEnd.TARGET_REACHED, cadencesMs = setOf(2_000L), link = link),
        book,
        book,
    )

    private fun result(result: ComparisonResult) = ComparisonUi(
        phase = ComparisonPhase.RESULT,
        measure = Measure.USB_CABLE_OFF,
        armA = arm,
        armB = arm,
        result = result,
    )

    private fun compared(verdict: Verdict) = ComparisonResult.Compared(
        before = arm,
        after = arm,
        measure = Measure.USB_CABLE_OFF,
        verdict = verdict,
        pValue = 0.03125,
        verification = Verification.VERIFIED,
        notChecked = listOf(Condition.WIFI_SCAN_ALWAYS),
    )

    private var state by mutableStateOf(ComparisonUi())

    private fun show(initial: ComparisonUi) {
        state = initial
        composeRule.setContent {
            BtDashboardTheme {
                ComparisonSection(snapshot, state, {}, {}, {}, {}, restoreButton = { Text(RESTORE) })
            }
        }
    }

    /** Every text on screen, merged and unmerged alike. */
    private fun allTexts(): List<String> {
        fun SemanticsNode.walk(): List<SemanticsNode> = listOf(this) + children.flatMap { it.walk() }
        return composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().walk()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
    }

    /** AK-T047-6: six selectable measures, four workarounds that are not, under their own heading. */
    @Test
    fun `the catalog offers six measures and four workarounds nobody can choose`() {
        show(ComparisonUi())

        assertEquals(Measure.entries.size, composeRule.onAllNodes(isSelectable()).fetchSemanticsNodes().size)
        assertEquals(6, Measure.entries.size)
        assertEquals(4, Fallback.entries.size)
        composeRule.onNodeWithText("Workarounds").assertExists()
        Fallback.entries.forEach { fallback ->
            composeRule.onNode(hasText(fallback.displayName) and isSelectable()).assertDoesNotExist()
            composeRule.onNodeWithText(fallback.displayName).assertExists()
        }
        composeRule.onNodeWithText(RESTORE).assertDoesNotExist()
    }

    /** AK-T047-9, rendered: the progress line, the target line, and an early end's line. */
    @Test
    fun `an arm says how much of fifteen minutes it observed`() {
        val running = RunningArm(ObservationRun.startedAfter(0L, ARM_TARGET_MS).copy(observedMs = 7 * 60_000L), book, null)
        show(ComparisonUi(phase = ComparisonPhase.ARM_A, measure = Measure.NO_DISCOVERY, running = running))
        composeRule.onNodeWithText("7 min of 15 min observed.").assertExists()
        composeRule.onNodeWithText(RESTORE).assertExists()

        state = state.copy(
            running = running.copy(run = running.run.copy(observedMs = ARM_TARGET_MS, end = RunEnd.TARGET_REACHED)),
        )
        composeRule.onNodeWithText("Run reached its set length. 15 min observed.").assertExists()

        state = state.copy(running = running, restart = ArmRestart("A", RunEnd.READING_GAP, 180_000L))
        composeRule.onNodeWithText(
            "Arm A ended before 15 minutes (Run ended after 2 min without a reading. 3 min observed). " +
                "Starting Arm A again.",
        ).assertExists()
    }

    /** AK-T047-10: eleven cases, eleven different sentences, none claiming more than its verdict. */
    @Test
    fun `each verdict and each reason has its own sentence`() {
        val reasons = listOf(
            NotComparable.ArmAIncomplete,
            NotComparable.ArmBIncomplete,
            NotComparable.LinkDiffers,
            NotComparable.CadenceDiffers,
            NotComparable.DropoutsUncounted,
            NotComparable.ConditionChanged(Condition.USB_POWER, NotComparable.Where.BETWEEN_ARMS),
            NotComparable.MeasureNotInEffect(Condition.USB_POWER),
        )
        val cases = Verdict.entries.map { verdictSentence(it, 0.03125) to compared(it) } +
            reasons.map { notComparableSentence(it) to ComparisonResult.NotComparableResult(listOf(it)) }
        assertEquals(11, cases.map { it.first }.distinct().size)

        show(result(cases.first().second))
        cases.forEach { (sentence, case) ->
            state = result(case)
            composeRule.onNode(hasText(sentence) and hasAnyAncestor(hasTestTag(RESULT_TAG))).assertExists()
        }
        assertEquals(
            "USB power was different between the two arms, so the comparison does not hold.",
            notComparableSentence(reasons[5]),
        )
        assertTrue(verdictSentence(Verdict.FEWER_DETECTED, 0.03125).endsWith("(p ≈ 0.0313)."))
        assertFalse(verdictSentence(Verdict.NO_BASELINE_EVENTS, 0.03125).contains("p ≈"))
    }

    /** AK-T047-11 and -12: the frame, the AK-16 block and the process sentence sit with the verdict. */
    @Test
    fun `the frame stands in the same composable as the verdict`() {
        show(result(ComparisonResult.NotComparableResult(listOf(NotComparable.LinkDiffers, NotComparable.CadenceDiffers))))

        val inResult = listOf(
            notComparableSentence(NotComparable.LinkDiffers),
            notComparableSentence(NotComparable.CadenceDiffers),
            "Arm A ran for 15 min, Arm B for 15 min.",
            "Both pinned to 990 kbps, LDAC.",
            "Read every 2 s.",
            "Wi-Fi scanning: not checkable at the start of Arm A, not checkable at the end. " +
                "not checkable at the start of Arm B, not checkable at the end.",
            "Not checked in this run: Wi-Fi scanning.",
            "This is a relative result about these exact 15 minutes on each side, not a statement about " +
                "everyday quality.",
            "At 15 minutes each, a difference is only detectable if Arm A counted at least 5 incidents — fewer " +
                "than that, and even a drop to zero afterwards would not be distinguishable from chance.",
            "3 Mbps EDR: yes — read on this pairing.",
            "Effective MTU: 883 — read on this pairing.",
            "Packet type and retransmission rate: not readable without BQR.",
            PROCESS_END,
        )
        inResult.forEach { text ->
            composeRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(RESULT_TAG))).assertExists()
        }

        state = result(compared(Verdict.WITHIN_DETECTION_LIMIT))
        composeRule.onNode(hasText(PROCESS_END) and hasAnyAncestor(hasTestTag(RESULT_TAG))).assertExists()
        composeRule.onNodeWithText(RESTORE).assertExists()
    }

    /** AK-T047-16 (M18): no phase puts a raw address on screen, not even one quoted back by the helper. */
    @Test
    fun `no phase shows a raw Bluetooth address`() {
        val leaky = book + (Condition.USB_POWER to ConditionValue.Unreadable("read failed for $RAW_MAC"))
        val running = RunningArm(ObservationRun.startedAfter(0L, ARM_TARGET_MS), leaky, null)
        val phases = listOf(
            ComparisonUi(measure = Measure.USB_CABLE_OFF, alreadyApplies = Measure.USB_CABLE_OFF),
            ComparisonUi(ComparisonPhase.PIN_AND_CHECK, Measure.USB_CABLE_OFF, pinFailure = "$RAW_MAC is not a Bluetooth address"),
            ComparisonUi(ComparisonPhase.ARM_A, Measure.USB_CABLE_OFF, running = running),
            ComparisonUi(ComparisonPhase.INSTRUCT, Measure.USB_CABLE_OFF, armA = arm, readBack = leaky),
            ComparisonUi(ComparisonPhase.ARM_B, Measure.USB_CABLE_OFF, armA = arm, running = running, readBack = leaky),
            result(compared(Verdict.FEWER_DETECTED)),
        )
        show(phases.first())
        phases.forEach { phase ->
            state = phase
            composeRule.waitForIdle()
            val texts = allTexts()
            assertTrue("nothing rendered for ${phase.phase}", texts.isNotEmpty())
            texts.forEach { assertFalse("${phase.phase} leaked: $it", RAW_ADDRESS.containsMatchIn(it)) }
        }
    }

    /** AK-T047-8: the read-back words per bound condition; Wi-Fi "on" is never a contradiction (AD-035). */
    @Test
    fun `the read-back words follow the condition`() {
        fun words(measure: Measure, condition: Condition, value: ConditionValue) =
            readBackSentence(measure, book + (condition to value))
        val unreadable = ConditionValue.Unreadable("battery state not read")

        assertEquals(
            "Confirmed: Wi-Fi is off on this phone.",
            words(Measure.NO_2_4_GHZ_WIFI, Condition.WIFI_RADIO, ConditionValue.Read("off")),
        )
        assertEquals(
            "Not checkable: Wi-Fi being on here does not say which band it used.",
            words(Measure.NO_2_4_GHZ_WIFI, Condition.WIFI_RADIO, ConditionValue.Read("on")),
        )
        assertEquals(
            "Confirmed: no other Bluetooth device is connected.",
            words(Measure.NO_SECOND_DEVICE, Condition.OTHER_ACL_LINKS, ConditionValue.Read("0")),
        )
        assertEquals(
            "Contradicts what this run needs: 2 other Bluetooth device(s) are still connected.",
            words(Measure.NO_SECOND_DEVICE, Condition.OTHER_ACL_LINKS, ConditionValue.Read("2")),
        )
        assertEquals(
            "Confirmed: the phone is running on battery.",
            words(Measure.USB_CABLE_OFF, Condition.USB_POWER, ConditionValue.Read("none")),
        )
        listOf("usb", "other").forEach {
            assertEquals(
                "Contradicts what this run needs: the phone is still receiving power.",
                words(Measure.USB_CABLE_OFF, Condition.USB_POWER, ConditionValue.Read(it)),
            )
        }
        listOf(
            Measure.NO_2_4_GHZ_WIFI to Condition.WIFI_RADIO,
            Measure.NO_SECOND_DEVICE to Condition.OTHER_ACL_LINKS,
            Measure.USB_CABLE_OFF to Condition.USB_POWER,
        ).forEach { (measure, condition) ->
            assertEquals("Not checkable: battery state not read.", words(measure, condition, unreadable))
        }
        listOf(Measure.NO_DISCOVERY, Measure.BODY_OUT_OF_PATH, Measure.SINK_ALLOWS_LDAC).forEach {
            assertEquals("Not checkable from this phone.", readBackSentence(it, book))
        }
    }

    private companion object {
        const val RAW_MAC = "AC:DE:48:00:37:8F"
        const val RESTORE = "restore slot"
        val RAW_ADDRESS = Regex("""(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}""")
    }
}
