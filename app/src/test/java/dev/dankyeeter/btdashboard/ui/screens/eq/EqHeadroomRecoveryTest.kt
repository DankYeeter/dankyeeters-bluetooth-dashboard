package dev.dankyeeter.btdashboard.ui.screens.eq

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.audio.eq.Ear
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
 * The headroom follows the curve that is actually playing, in both directions.
 *
 * `EqHeadroomTest` in :core-audio settles what each [HeadroomMode] computes.
 * This settles the wiring, which is where the owner's bug actually lived: the
 * model was capable of the right answer all along, and nothing on the drag path
 * ever asked for it. The failure was quiet in the worst way — the music simply
 * stayed 5 dB down after a band had been put back to zero, with no control on
 * screen admitting to it and "Reset bands to flat" as the only way out.
 *
 * The EQ is left **disabled** throughout, as in `VolumeAwareTiltViewModelTest`:
 * switching it on would attach a real `DynamicsProcessing` effect and there is
 * no audio HAL here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EqHeadroomRecoveryTest {

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

    /** One slider drag: a run of values, then the release. */
    private fun EqViewModel.drag(band: Int, through: List<Float>) {
        through.forEach { setLinkedBandGain(band, it) }
        persist()
        idle()
    }

    @Test
    fun `a band pushed up and put back leaves the level where it started`() {
        val viewModel = viewModel()
        val before = viewModel.settings.value.preGainDb
        assertEquals(0f, before)

        viewModel.drag(band = 3, through = listOf(1f, 3f, 5f))
        assertEquals(
            "a +5 dB boost has to be charged for",
            -5f,
            viewModel.settings.value.preGainDb,
        )

        viewModel.drag(band = 3, through = listOf(4f, 2f, 0f))
        assertEquals(
            "the headroom stayed spent on a boost that is no longer there",
            before,
            viewModel.settings.value.preGainDb,
        )
    }

    @Test
    fun `the headroom is not handed back until the finger comes off`() {
        val viewModel = viewModel()
        viewModel.drag(band = 0, through = listOf(6f))
        assertEquals(-6f, viewModel.settings.value.preGainDb)

        // Mid-drag, before persist(): the level must stay put rather than rise
        // and fall with every value the thumb passes through.
        listOf(5f, 3f, 1f, 0f).forEach { value ->
            viewModel.setLinkedBandGain(0, value)
            assertEquals(
                "the level pumped at $value dB",
                -6f,
                viewModel.settings.value.preGainDb,
            )
        }

        viewModel.persist()
        idle()
        assertEquals(0f, viewModel.settings.value.preGainDb)
    }

    @Test
    fun `no value passed through during a drag is left able to clip`() {
        val viewModel = viewModel()
        listOf(0f, 2f, 9f, 15f, 7f, 12f, 0f, 4f).forEach { value ->
            viewModel.setLinkedBandGain(1, value)
            val settings = viewModel.settings.value
            val peak = settings.staticGainsFor(Ear.LEFT).max()
            assertTrue(
                "at $value dB the pre-gain was ${settings.preGainDb} against a peak of $peak",
                settings.preGainDb <= -peak,
            )
        }
    }

    @Test
    fun `a released drag that changed nothing does not disturb the settings`() {
        val viewModel = viewModel()
        viewModel.drag(band = 2, through = listOf(4f))
        val settled = viewModel.settings.value

        // Release again with nothing moved: idempotent, so a stray release
        // cannot become a second edit.
        viewModel.persist()
        idle()
        assertEquals(settled, viewModel.settings.value)
    }

    @Test
    fun `a tilt-bought headroom is released when the tilt is switched off`() {
        val viewModel = viewModel()
        volume.set(0.1f)
        idle()

        viewModel.setVolumeAwareTilt(true)
        idle()
        val quiet = viewModel.settings.value
        assertTrue("expected a tilt at a tenth of the volume", quiet.tiltGainsDb.any { it > 0f })
        assertEquals(-quiet.tiltGainsDb.max(), quiet.preGainDb)

        viewModel.setVolumeAwareTilt(false)
        idle()
        assertEquals(
            "the music stayed down with nothing boosted to justify it",
            0f,
            viewModel.settings.value.preGainDb,
        )
    }

    @Test
    fun `a band boost survives the tilt being switched off`() {
        // The two layers are charged together and must be released separately;
        // the failure this guards against is a tilt switch that takes the
        // band's headroom with it and lets the band clip.
        val viewModel = viewModel()
        viewModel.drag(band = 4, through = listOf(6f))

        volume.set(0.1f)
        idle()
        viewModel.setVolumeAwareTilt(true)
        idle()

        viewModel.setVolumeAwareTilt(false)
        idle()
        assertEquals(
            "the band is still boosted, so its headroom is still owed",
            -6f,
            viewModel.settings.value.preGainDb,
        )
    }
}
