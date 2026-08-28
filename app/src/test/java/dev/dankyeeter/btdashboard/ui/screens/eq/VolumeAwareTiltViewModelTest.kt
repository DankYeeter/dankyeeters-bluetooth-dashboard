package dev.dankyeeter.btdashboard.ui.screens.eq

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.audio.eq.MediaVolumeSource
import dev.dankyeeter.btdashboard.audio.eq.VolumeAwareTilt
import dev.dankyeeter.btdashboard.system.SystemGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The EQ screen's ViewModel follows the media volume.
 *
 * This is the wiring the maths cannot prove on its own: `VolumeAwareTiltTest`
 * in :core-audio says what curve a volume fraction produces, and says nothing
 * about whether anything ever asks. The failure this guards against is the
 * quiet one — a feature that is switched on, described on screen, and inert
 * because nobody connected the observer.
 *
 * The volume is injected rather than driven through `AudioManager`. Under
 * Robolectric the shadow AudioManager does not write through to
 * `Settings.System`, so the content observer the real monitor registers never
 * fires and the test would be asserting on a notification path that only
 * exists on a device. What belongs here is the ViewModel's reaction to a number
 * that changed.
 *
 * The EQ is deliberately left **disabled** throughout: switching it on would
 * send the settings to the attachment layer, which builds a real
 * `DynamicsProcessing` effect, and there is no audio HAL here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VolumeAwareTiltViewModelTest {

    /** A volume that can be moved from the test, with the real one's contract. */
    private class FakeVolume(initial: Float) : MediaVolumeSource {
        private val state = MutableStateFlow(initial)
        override val fraction: StateFlow<Float> = state.asStateFlow()

        fun set(value: Float) {
            state.value = value
        }
    }

    private val volume = FakeVolume(VolumeAwareTilt.REFERENCE_FRACTION)

    @Before
    fun setUp() {
        SystemGraph.init(ApplicationProvider.getApplicationContext<Context>())
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun viewModel(): EqViewModel = EqViewModel(volume).also { idle() }

    @Test
    fun `a volume change re-derives the band gains`() {
        val viewModel = viewModel()
        viewModel.setVolumeAwareTilt(true)
        idle()

        // At the reference volume there is nothing to restore, and the screen
        // says as much.
        assertTrue(
            "expected no tilt at the reference volume: ${viewModel.settings.value.tiltGainsDb}",
            VolumeAwareTilt.isFlat(viewModel.settings.value.tiltGainsDb),
        )

        volume.set(0.1f)
        idle()

        val quiet = viewModel.settings.value
        assertTrue(
            "expected a tilt at a tenth of the volume: ${quiet.tiltGainsDb}",
            quiet.tiltGainsDb.any { it > 0f },
        )
        // The bottom of the curve is where the correction lives.
        assertTrue(
            "expected the bass band to be raised furthest: ${quiet.tiltGainsDb}",
            quiet.tiltGainsDb.first() > quiet.tiltGainsDb[quiet.bandCount / 2],
        )
        // And it is charged for: the boost is real, so the headroom moved with it.
        assertEquals(-quiet.tiltGainsDb.max(), quiet.preGainDb)

        // Turning the volume back up takes it away again — the layer is derived
        // afresh every time, so nothing accumulates across changes.
        volume.set(VolumeAwareTilt.REFERENCE_FRACTION)
        idle()
        assertTrue(
            "expected the tilt to be gone again: ${viewModel.settings.value.tiltGainsDb}",
            VolumeAwareTilt.isFlat(viewModel.settings.value.tiltGainsDb),
        )
        assertEquals(0f, viewModel.settings.value.preGainDb)
    }

    @Test
    fun `the volume is ignored while the feature is switched off`() {
        val viewModel = viewModel()

        volume.set(0.1f)
        idle()

        val settings = viewModel.settings.value
        assertTrue(
            "off must mean flat: ${settings.tiltGainsDb}",
            VolumeAwareTilt.isFlat(settings.tiltGainsDb),
        )
        assertEquals(0f, settings.preGainDb)
    }

    @Test
    fun `switching the feature off puts the curve back`() {
        val viewModel = viewModel()
        volume.set(0.1f)
        idle()

        viewModel.setVolumeAwareTilt(true)
        idle()
        assertTrue(viewModel.settings.value.tiltGainsDb.any { it > 0f })
        assertTrue(viewModel.settings.value.preGainDb < 0f)

        viewModel.setVolumeAwareTilt(false)
        idle()

        val off = viewModel.settings.value
        assertTrue(
            "a stale tilt survived the switch: ${off.tiltGainsDb}",
            VolumeAwareTilt.isFlat(off.tiltGainsDb),
        )
        // The headroom goes with it. Leaving it behind would mean the music
        // stayed 12 dB down with nothing boosted to justify it.
        assertEquals(0f, off.preGainDb)
    }
}
