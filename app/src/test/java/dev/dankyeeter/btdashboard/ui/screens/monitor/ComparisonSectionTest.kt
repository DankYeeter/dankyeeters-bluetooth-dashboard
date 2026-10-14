package dev.dankyeeter.btdashboard.ui.screens.monitor

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.test.performClick
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
import dev.dankyeeter.btdashboard.system.devices.CodecApplyOutcome
import dev.dankyeeter.btdashboard.ui.theme.BtDashboardTheme
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        Condition.WIFI_BAND to ConditionValue.Read("5 GHz"),
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
    private var shown by mutableStateOf(snapshot)
    private var keptOffScreen by mutableStateOf(true)

    /** The handle is process-wide; no test may leave a comparison behind for the next. */
    @After
    fun releaseHandle() = ActiveComparison.set(null)

    private fun show(initial: ComparisonUi) {
        state = initial
        composeRule.setContent {
            BtDashboardTheme {
                ComparisonSection(
                    shown,
                    state,
                    {},
                    {},
                    {},
                    {},
                    restoreButton = { Text(RESTORE) },
                    keptOffScreen = keptOffScreen,
                )
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
            "Arm A: 990 kbps, LDAC.",
            "Arm B: 990 kbps, LDAC.",
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

    /** DR-008 at its sibling: an unanswered pin may have landed, so no "Could not pin" frame. */
    @Test
    fun `an unanswered pin is reported as unknown, not as not pinned`() {
        show(
            ComparisonUi(
                ComparisonPhase.PIN_AND_CHECK,
                Measure.USB_CABLE_OFF,
                pinFailure = "the privileged helper did not answer within 15 s — whether the change took effect is unknown",
            ),
        )

        composeRule.onNodeWithText(
            "The privileged helper did not answer within 15 s — whether the change took effect is unknown. " +
                "Nothing was measured.",
        ).assertExists()
    }

    /** AK-T047-8: the read-back words per bound condition; Wi-Fi "on" is never a contradiction (AD-035). */
    @Test
    fun `the read-back words follow the condition`() {
        fun words(measure: Measure, condition: Condition, value: ConditionValue) =
            readBackSentence(measure, book + (condition to value))
        val unreadable = ConditionValue.Unreadable("battery state not read")

        assertEquals(
            "Confirmed: Wi-Fi is off on this phone.",
            words(Measure.NO_2_4_GHZ_WIFI, Condition.WIFI_BAND, ConditionValue.Read("off")),
        )
        assertEquals(
            "Contradicts what this run needs: Wi-Fi here is on a 2.4 GHz network.",
            words(Measure.NO_2_4_GHZ_WIFI, Condition.WIFI_BAND, ConditionValue.Read("2.4 GHz")),
        )
        assertEquals(
            "Not checkable: whether 5 GHz Wi-Fi affects this pairing has not been measured.",
            words(Measure.NO_2_4_GHZ_WIFI, Condition.WIFI_BAND, ConditionValue.Read("5 GHz")),
        )
        assertEquals(
            "Not checkable: whether 6 GHz Wi-Fi affects this pairing has not been measured.",
            words(Measure.NO_2_4_GHZ_WIFI, Condition.WIFI_BAND, ConditionValue.Read("6 GHz")),
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
            Measure.NO_2_4_GHZ_WIFI to Condition.WIFI_BAND,
            Measure.NO_SECOND_DEVICE to Condition.OTHER_ACL_LINKS,
            Measure.USB_CABLE_OFF to Condition.USB_POWER,
        ).forEach { (measure, condition) ->
            assertEquals("Not checkable: battery state not read.", words(measure, condition, unreadable))
        }
        listOf(Measure.NO_DISCOVERY, Measure.BODY_OUT_OF_PATH, Measure.SINK_ALLOWS_LDAC).forEach {
            assertEquals("Not checkable from this phone.", readBackSentence(it, book))
        }
    }

    /** AK-T054-1: while an arm counts it says it keeps counting off the screen — or, refused, that it cannot. */
    @Test
    fun `an arm announces that it keeps counting off the screen`() {
        val running = RunningArm(ObservationRun.startedAfter(0L, ARM_TARGET_MS), book, null)
        show(ComparisonUi(ComparisonPhase.ARM_A, Measure.USB_CABLE_OFF, running = running))
        assertEquals(
            "This keeps counting if you leave this screen, switch apps, or the screen turns off — only stopping " +
                "the app's process ends it early. Stop it here or from the notification.",
            ARM_KEEPS_COUNTING,
        )
        composeRule.onNodeWithText(ARM_KEEPS_COUNTING).assertExists()

        state = ComparisonUi(ComparisonPhase.ARM_B, Measure.USB_CABLE_OFF, armA = arm, running = running)
        composeRule.onNodeWithText(ARM_KEEPS_COUNTING).assertExists()

        keptOffScreen = false
        composeRule.onNodeWithText(ARM_NEEDS_THIS_SCREEN).assertExists()
        composeRule.onNodeWithText(ARM_KEEPS_COUNTING).assertDoesNotExist()

        // Between the arms the service holds nothing in the foreground (B1): neither line.
        state = ComparisonUi(ComparisonPhase.INSTRUCT, Measure.USB_CABLE_OFF, armA = arm)
        composeRule.onNodeWithText(ARM_NEEDS_THIS_SCREEN).assertDoesNotExist()
    }

    /** AD-045: a page button only in the instruction, only for a measure with a page — never on the catalog. */
    @Test
    fun `the instruction offers the chosen measure's page, and only where one exists`() {
        fun jumps() = allTexts().filter { it.startsWith("Open ") }
        show(ComparisonUi(measure = Measure.NO_2_4_GHZ_WIFI))
        assertEquals(emptyList<String>(), jumps())

        state = ComparisonUi(ComparisonPhase.INSTRUCT, Measure.NO_2_4_GHZ_WIFI, armA = arm)
        assertEquals(listOf("Open Wi-Fi settings"), jumps())

        state = ComparisonUi(ComparisonPhase.INSTRUCT, Measure.USB_CABLE_OFF, armA = arm)
        assertEquals(emptyList<String>(), jumps())
    }

    /** AK-T054-3: the process sentence, character for character. */
    @Test
    fun `the process sentence says an arm survives everything but the process`() {
        assertEquals(
            "A comparison does not survive the app's process ending — Arm A and Arm B live only in this app's " +
                "memory. Once an arm has started, leaving this screen, switching apps, or the screen turning off " +
                "does not pause or restart it; only the process ending does, and then the comparison starts over.",
            PROCESS_END,
        )
    }

    /** AK-T054-5, rendered: the way back and a codec change each leave their line; the user's own cancel none. */
    @Test
    fun `a comparison stopped without a result says why on the catalog`() {
        show(ComparisonUi(measure = Measure.USB_CABLE_OFF, ended = ComparisonEnd.SettingsPutBack))
        composeRule.onNodeWithText(
            "Comparing USB cable unplugged was stopped when settings were put back — no result, and neither arm counts.",
        ).assertExists()

        state = ComparisonUi(measure = Measure.USB_CABLE_OFF, ended = ComparisonEnd.LinkChanged("A", RunEnd.CODEC_CHANGED))
        composeRule.onNodeWithText(
            "Comparing USB cable unplugged was stopped because the codec left LDAC — no result, and neither arm counts.",
        ).assertExists()

        assertNull(endedSentence(ComparisonEnd.Cancelled, Measure.USB_CABLE_OFF))
        val linkEnds = listOf(null) + RunEnd.entries
        val sentences = linkEnds.map { endedSentence(ComparisonEnd.LinkChanged("B", it), Measure.USB_CABLE_OFF) }
        assertTrue(sentences.all { it != null && it.endsWith("— no result, and neither arm counts.") })
        assertFalse(
            "only the codec leaving says so",
            endedSentence(ComparisonEnd.LinkChanged("A", RunEnd.QUALITY_CHANGED), Measure.USB_CABLE_OFF)!!
                .contains("left LDAC"),
        )
    }

    /** AK-T054-2 (director's option A): "Cancel" is the notification's "Stop" — same state, no write. */
    @Test
    fun `Cancel on the screen ends the comparison exactly as Stop in the notification does`() {
        val pinScope = TestScope()
        var pins = 0
        fun inArmA(): ComparisonController {
            val control = ComparisonController(
                scope = pinScope,
                pin = {
                    pins++
                    CodecApplyOutcome.Applied("LDAC 990")
                },
                // Still on USB power, so the measure has something to change.
                readBook = { _, _ -> book + (Condition.USB_POWER to ConditionValue.Read("usb")) },
            )
            control.onReading(snapshot, 1_000L)
            control.onMeasure(Measure.USB_CABLE_OFF)
            control.onCompare()
            pinScope.runCurrent()
            check(control.ui.value.phase == ComparisonPhase.ARM_A)
            return control
        }

        val tapped = inArmA()
        composeRule.setContent {
            BtDashboardTheme {
                val ui by tapped.ui.collectAsState()
                ComparisonSection(snapshot, ui, {}, {}, {}, tapped::onReset, restoreButton = {}, keptOffScreen = true)
            }
        }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()

        val stopped = inArmA()
        ActiveComparison.end(ComparisonEnd.Cancelled) // MeasurementService's Stop, verbatim

        assertEquals(ComparisonUi(measure = Measure.USB_CABLE_OFF, ended = ComparisonEnd.Cancelled), tapped.ui.value)
        assertEquals(stopped.ui.value, tapped.ui.value)
        assertEquals("each comparison's own pin, and nothing after it", 2, pins)
    }

    /** AK-T054-6: the counted arm B's read-back stands in the frame, right after the cadence. */
    @Test
    fun `the frame carries the read-back of the arm that counted`() {
        fun readBackOf(measure: Measure, armBStart: ConditionBook, verification: Verification): List<String> {
            val armB = arm.copy(start = armBStart)
            state = ComparisonUi(
                phase = ComparisonPhase.RESULT,
                measure = measure,
                armA = arm,
                armB = armB,
                result = ComparisonResult.Compared(
                    arm, armB, measure, Verdict.WITHIN_DETECTION_LIMIT, 0.5, verification, emptyList(),
                ),
            )
            composeRule.waitForIdle()
            val texts = allTexts()
            return texts.subList(texts.indexOf("Read every 2 s.") + 1, texts.indexOf("Read every 2 s.") + 2)
        }
        show(ComparisonUi())

        val battery = book + (Condition.USB_POWER to ConditionValue.Read("none"))
        assertEquals(
            listOf("Confirmed: the phone is running on battery."),
            readBackOf(Measure.USB_CABLE_OFF, battery, Verification.VERIFIED),
        )
        assertEquals(
            listOf("Not checkable: whether 5 GHz Wi-Fi affects this pairing has not been measured."),
            readBackOf(Measure.NO_2_4_GHZ_WIFI, book, Verification.NOT_VERIFIABLE),
        )
        assertEquals(
            listOf("Not checkable from this phone."),
            readBackOf(Measure.BODY_OUT_OF_PATH, book, Verification.NOT_VERIFIABLE),
        )
        val unread = book + (Condition.USB_POWER to ConditionValue.Unreadable("battery state not read"))
        assertEquals(
            listOf("Not checkable: battery state not read."),
            readBackOf(Measure.USB_CABLE_OFF, unread, Verification.NOT_VERIFIABLE),
        )

        state = result(ComparisonResult.NotComparableResult(listOf(NotComparable.CadenceDiffers)))
        composeRule.waitForIdle()
        assertTrue("no verification without a comparison", allTexts().none { it.startsWith("Confirmed:") })
    }

    /** AK-T054-7: each arm its own step and codec; adaptive is never called pinned. */
    @Test
    fun `each arm names its own step and codec`() {
        val adaptive = link.copy(mode = LdacQualityMode.ADAPTIVE, adaptive = true)
        val armB = arm.copy(run = arm.run.copy(link = adaptive))
        show(ComparisonUi(ComparisonPhase.RESULT, Measure.USB_CABLE_OFF, armA = arm, armB = armB))

        composeRule.onNodeWithText("Arm A: 990 kbps, LDAC.").assertExists()
        composeRule.onNodeWithText("Arm B: not pinned (ABR), LDAC.").assertExists()
        assertTrue(allTexts().none { it.startsWith("Both pinned") })
    }

    /** AK-T054-8: the pairing facts only for the pairing that was measured, otherwise one line saying so. */
    @Test
    fun `pairing facts stand only for the measured pairing`() {
        val facts = listOf("3 Mbps EDR: yes — read on this pairing.", "Effective MTU: 883 — read on this pairing.")
        val changed = "3 Mbps EDR and effective MTU are not shown — the connected pairing has changed since this " +
            "comparison ran."
        show(result(compared(Verdict.WITHIN_DETECTION_LIMIT)))
        facts.forEach { composeRule.onNodeWithText(it).assertExists() }
        composeRule.onNodeWithText(changed).assertDoesNotExist()

        shown = snapshot.copy(device = snapshot.device?.copy(address = "00:11:22:33:44:55"))
        composeRule.onNodeWithText(changed).assertExists()
        facts.forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
    }

    private companion object {
        const val RAW_MAC = "AC:DE:48:00:37:8F"
        const val RESTORE = "restore slot"
        val RAW_ADDRESS = Regex("""(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}""")
    }
}
