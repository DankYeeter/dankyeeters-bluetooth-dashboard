package dev.dankyeeter.btdashboard.hearing

import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.tone.ToneEar
import dev.dankyeeter.btdashboard.audio.tone.ToneGenerator
import dev.dankyeeter.btdashboard.hearing.protocol.ProtocolConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Drives the controller with a fake tone generator on virtual time. The fake
 * plays the role of the listener: whenever a tone is gated on at or above the
 * simulated threshold it presses the response button.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HughsonWestlakeTestControllerTest {

    private class FakeToneGenerator(private val simulatedThresholdDb: Double) : ToneGenerator {
        var startCalls = 0
        var startSucceeds = true
        var level = -100.0
        var frequency = 0.0
        var ear: ToneEar? = null
        var toneActive = false
        val activations = mutableListOf<Triple<ToneEar?, Double, Double>>()
        var respondCallback: (() -> Unit)? = null

        override val isRunning: Boolean get() = startCalls > 0
        override val sampleRate: Int get() = 48_000

        override fun start(): Boolean {
            startCalls++
            return startSucceeds
        }

        override fun stop() {}
        override fun setFrequency(hz: Double) { frequency = hz }
        override fun setLevelDbFs(db: Double) { level = db }
        override fun setEar(ear: ToneEar) { this.ear = ear }
        override fun setRampMs(ms: Double) {}

        override fun setToneActive(active: Boolean) {
            toneActive = active
            if (!active) return
            activations += Triple(ear, frequency, level)
            if (level >= simulatedThresholdDb) respondCallback?.invoke()
        }
    }

    private val protocol = ProtocolConfig(catchTrialProbability = 0.0, maxCatchTrials = 0)

    private fun controller(
        tone: FakeToneGenerator,
        scope: kotlinx.coroutines.CoroutineScope,
        ambient: AmbientNoiseCheck? = null,
    ) = HughsonWestlakeTestController(
        toneGenerator = tone,
        watchdogScope = scope,
        volumeGuard = null,
        ambientNoiseCheck = ambient,
        protocol = protocol,
        random = Random(11),
        clock = { 1_700_000_000_000L },
        idFactory = { "run-1" },
    ).also { tone.respondCallback = it::onUserResponse }

    private fun config(ear: Ear?, frequencies: List<Int> = listOf(1000)) = HearingTestConfig(
        ear = ear,
        frequenciesHz = frequencies,
        calibrationPresetId = "generic_uncalibrated",
        ancMode = AncMode.UNKNOWN,
        runAmbientNoiseCheck = false,
    )

    @Test
    fun `a full single-ear run completes with a threshold`() = runTest {
        val tone = FakeToneGenerator(simulatedThresholdDb = -60.0)
        val controller = controller(tone, this)

        assertEquals(PrepareResult.Ready, controller.prepare(config(Ear.LEFT)))
        controller.start()

        val completed = controller.state.first() as HearingTestState.Completed
        assertEquals(-60.0, completed.run.left.single().thresholdDb, 1e-9)
        assertTrue(completed.run.right.isEmpty())
        assertEquals(ToneEar.LEFT, tone.ear)
        assertFalse("the tone must be gated off at the end", tone.toneActive)
    }

    @Test
    fun `both ears run sequentially and stay channel isolated`() = runTest {
        val tone = FakeToneGenerator(simulatedThresholdDb = -55.0)
        val controller = controller(tone, this)
        controller.prepare(config(ear = null))
        controller.start()

        val completed = controller.state.first() as HearingTestState.Completed
        assertEquals(1, completed.run.left.size)
        assertEquals(1, completed.run.right.size)
        assertTrue(tone.activations.none { it.first == ToneEar.BOTH })
        // Left is measured before right.
        assertEquals(ToneEar.LEFT, tone.activations.first().first)
        assertEquals(ToneEar.RIGHT, tone.activations.last().first)
    }

    @Test
    fun `prepare fails when no audio stream can be opened`() = runTest {
        val tone = FakeToneGenerator(-60.0).apply { startSucceeds = false }
        val result = controller(tone, this).prepare(config(Ear.LEFT))
        assertTrue(result is PrepareResult.Failed)
    }

    @Test
    fun `a loud room produces a warning but still allows the run`() = runTest {
        val tone = FakeToneGenerator(-60.0)
        val ambient = object : AmbientNoiseCheck {
            override suspend fun measureDbA(durationMillis: Long) = 55.0
        }
        val controller = controller(tone, this, ambient)
        val result = controller.prepare(
            config(Ear.LEFT).copy(runAmbientNoiseCheck = true),
        )
        assertTrue(result is PrepareResult.Warning)

        controller.start()
        val completed = controller.state.first() as HearingTestState.Completed
        assertEquals(55.0, completed.run.ambientNoiseDbA!!, 1e-9)
    }

    @Test
    fun `a denied microphone permission is not a blocker`() = runTest {
        val ambient = object : AmbientNoiseCheck {
            override suspend fun measureDbA(durationMillis: Long): Double? = null
        }
        val controller = controller(FakeToneGenerator(-60.0), this, ambient)
        assertEquals(
            PrepareResult.Ready,
            controller.prepare(config(Ear.LEFT).copy(runAmbientNoiseCheck = true)),
        )
    }

    @Test
    fun `aborting stops the run and reports the reason`() = runTest {
        val tone = FakeToneGenerator(-60.0)
        val controller = controller(tone, this)
        controller.prepare(config(Ear.LEFT, frequencies = TEST_FREQUENCIES_HZ))

        val job = launch { controller.start() }
        controller.abort(AbortReason.VOLUME_CHANGED)
        advanceUntilIdle()
        job.join()

        assertEquals(
            HearingTestState.Aborted(AbortReason.VOLUME_CHANGED),
            controller.state.first(),
        )
        assertFalse(tone.toneActive)
    }

    @Test
    fun `catch trials are presented silently and flagged as false positives`() = runTest {
        // A button masher: presses continuously, so the silent catch trials
        // must show up as false positives.
        val tone = FakeToneGenerator(-60.0)
        val controller = HughsonWestlakeTestController(
            toneGenerator = tone,
            watchdogScope = this,
            protocol = ProtocolConfig(catchTrialProbability = 1.0, maxCatchTrials = 4),
            random = Random(5),
            clock = { 0L },
            idFactory = { "run-2" },
        )
        val masher = launch {
            while (true) {
                controller.onUserResponse()
                delay(10)
            }
        }

        controller.prepare(config(Ear.RIGHT))
        controller.start()
        masher.cancel()

        assertTrue(controller.state.first() is HearingTestState.Completed)
        assertEquals(4, controller.reliability.catchTrials)
        assertEquals(4, controller.reliability.falsePositives)
        assertTrue(controller.reliability.unreliable)
        // Not a single catch trial may have produced audible output.
        assertTrue(tone.activations.all { it.third <= 0.0 })
    }

    @Test
    fun `progress is reported per frequency`() = runTest {
        val tone = FakeToneGenerator(-50.0)
        val controller = controller(tone, this)
        val seen = mutableSetOf<Int>()
        val collector = launch {
            controller.state.collect { state ->
                if (state is HearingTestState.Presenting) {
                    seen += state.frequencyHz
                    assertEquals(3, state.frequencyCount)
                    assertEquals(Ear.LEFT, state.ear)
                }
            }
        }
        controller.prepare(config(Ear.LEFT, frequencies = listOf(500, 1000, 2000)))
        controller.start()
        advanceUntilIdle()
        collector.cancel()

        // The state flow is conflated, so only the tone generator sees every
        // single presentation; the flow must at least report real frequencies.
        assertTrue(seen.isNotEmpty())
        assertTrue(listOf(500, 1000, 2000).containsAll(seen))
        assertEquals(setOf(500.0, 1000.0, 2000.0), tone.activations.map { it.second }.toSet())
    }
}
