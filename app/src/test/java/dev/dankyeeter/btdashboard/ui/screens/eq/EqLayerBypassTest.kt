package dev.dankyeeter.btdashboard.ui.screens.eq

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.audio.eq.HeadroomMode
import dev.dankyeeter.btdashboard.audio.eq.MediaVolumeSource
import dev.dankyeeter.btdashboard.audio.eq.VolumeAwareTilt
import dev.dankyeeter.btdashboard.audio.eq.withVolumeTilt
import dev.dankyeeter.btdashboard.system.SystemGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Hold-to-compare: one layer out of the signal, nothing else touched.
 *
 * The two switches this serves are claims about what an ear will notice, and
 * the app had been trying to win those arguments with paragraphs. The control
 * has to satisfy three things to be worth more than the paragraphs, and each is
 * a test here: it removes the layer it names and no other, it does not change
 * the level while doing it — a louder side always wins an A/B, which would make
 * the demonstration lie — and it changes nothing that is remembered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EqLayerBypassTest {

    private val layout = EqBandLayout.OCTAVE_10

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

    private fun curve(vararg gains: Float) = List(layout.bandCount) { gains.getOrElse(it) { 0f } }

    private fun settings(
        gains: List<Float> = curve(),
        loudnessRestoration: Boolean = false,
        tiltOn: Boolean = false,
    ) = EqSettings(
        enabled = true,
        layout = layout,
        leftGainsDb = gains,
        rightGainsDb = gains,
        loudnessRestoration = loudnessRestoration,
        volumeAwareTilt = tiltOn,
    )

    // ---- the rule -----------------------------------------------------------

    @Test
    fun `holding the restoration layer empties the compressor and nothing else`() {
        val boosted = settings(gains = curve(6f, -3f), loudnessRestoration = true)
            .sanitized(HeadroomMode.TRACK)
        val without = boosted.withoutLayer(EqLayer.LOUDNESS_RESTORATION)

        assertTrue(
            "the compressor still has gains in it: ${without.compressorGainsFor(Ear.LEFT)}",
            without.compressorGainsFor(Ear.LEFT).all { it == 0f },
        )
        // The static path is untouched: in this mode it only ever carried the
        // cuts, and a comparison that also removed those would be comparing two
        // things at once.
        assertEquals(boosted.staticGainsFor(Ear.LEFT), without.staticGainsFor(Ear.LEFT))
    }

    @Test
    fun `holding the tilt layer removes the tilt and leaves the user's curve`() {
        val quiet = settings(gains = curve(0f, 4f), tiltOn = true)
            .withVolumeTilt(0.1f)
            .sanitized(HeadroomMode.TRACK)
        assertTrue(quiet.tiltGainsDb.any { it > 0f })

        val without = quiet.withoutLayer(EqLayer.QUIET_LISTENING_TILT)
        assertTrue(
            "a tilt survived the hold: ${without.activeTiltDb}",
            VolumeAwareTilt.isFlat(without.activeTiltDb),
        )
        assertEquals(quiet.leftGainsDb, without.leftGainsDb)
        assertEquals(quiet.rightGainsDb, without.rightGainsDb)
    }

    @Test
    fun `the level is held across the comparison`() {
        val quiet = settings(tiltOn = true).withVolumeTilt(0.1f).sanitized(HeadroomMode.TRACK)
        val without = quiet.withoutLayer(EqLayer.QUIET_LISTENING_TILT)

        // The pre-gain the tilt bought stays applied. Handing it back here would
        // make the "without" side louder, and louder is what an ear votes for.
        assertEquals(quiet.preGainDb, without.preGainDb)
        // And it survives the sanitising the attachment layer does on the way
        // in, because that only ever deepens.
        assertEquals(quiet.preGainDb, without.sanitized().preGainDb)
    }

    @Test
    fun `a layer that is switched off is not removed twice`() {
        // With loudness restoration off the positive band gains are the static
        // curve itself; zeroing them here would silently be "Compare with EQ
        // off" wearing another label.
        val static = settings(gains = curve(6f), loudnessRestoration = false)
        assertEquals(static, static.withoutLayer(EqLayer.LOUDNESS_RESTORATION))

        val untilted = settings(gains = curve(6f), tiltOn = false)
        assertEquals(untilted, untilted.withoutLayer(EqLayer.QUIET_LISTENING_TILT))
    }

    @Test
    fun `holding nothing changes nothing`() {
        val any = settings(gains = curve(3f, -2f), loudnessRestoration = true, tiltOn = true)
        assertEquals(any, any.withoutLayer(null))
    }

    // ---- the wiring ---------------------------------------------------------

    @Test
    fun `a hold is transient and is not written into the settings`() {
        val viewModel = viewModel()
        volume.set(0.1f)
        idle()
        viewModel.setVolumeAwareTilt(true)
        idle()

        val before = viewModel.settings.value
        assertTrue(before.tiltGainsDb.any { it > 0f })

        viewModel.holdLayerBypass(EqLayer.QUIET_LISTENING_TILT)
        idle()
        assertEquals(EqLayer.QUIET_LISTENING_TILT, viewModel.heldLayer.value)
        assertEquals(
            "a comparison must not become an edit",
            before,
            viewModel.settings.value,
        )

        viewModel.releaseLayerBypass(EqLayer.QUIET_LISTENING_TILT)
        idle()
        assertNull(viewModel.heldLayer.value)
        assertEquals(before, viewModel.settings.value)
    }

    @Test
    fun `releasing a layer nobody is holding leaves the other hold alone`() {
        // The press-state observer emits "not pressed" on its first pass, and
        // both controls are on screen at once: an unqualified release would let
        // one button's first composition cancel the other button's hold.
        val viewModel = viewModel()
        viewModel.holdLayerBypass(EqLayer.QUIET_LISTENING_TILT)
        idle()

        viewModel.releaseLayerBypass(EqLayer.LOUDNESS_RESTORATION)
        idle()

        assertEquals(EqLayer.QUIET_LISTENING_TILT, viewModel.heldLayer.value)
    }
}
