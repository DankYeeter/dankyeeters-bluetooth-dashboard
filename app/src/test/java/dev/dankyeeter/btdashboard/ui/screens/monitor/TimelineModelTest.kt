package dev.dankyeeter.btdashboard.ui.screens.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.LinkDataSource
import dev.dankyeeter.btdashboard.monitor.link.LinkQualitySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions behind the timeline lanes.
 *
 * All of them come down to one rule: a stretch where nothing was measured must
 * never render as a stretch where nothing happened. The old RSSI chart failed
 * exactly that way — it showed a flat empty axis for a value the build could
 * not read at all, and it read as a quiet link.
 */
class TimelineModelTest {

    private val minute = 60_000L

    private fun sample(
        atMinute: Long,
        codec: CodecFamily? = null,
        rateHz: Int? = null,
        playing: Boolean = false,
        rssi: Int? = null,
    ) = LinkQualitySample(
        timestampMs = atMinute * minute,
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        source = LinkDataSource.CODEC_API,
        rssiDbm = rssi,
        codec = codec,
        sampleRateHz = rateHz,
        isPlaying = playing,
    )

    @Test
    fun `a value that holds becomes one span`() {
        val spans = spansOf(
            listOf(
                sample(0, codec = CodecFamily.APTX),
                sample(1, codec = CodecFamily.APTX),
                sample(2, codec = CodecFamily.APTX),
            ),
        ) { it.codec }

        assertEquals(1, spans.size)
        assertEquals(CodecFamily.APTX, spans[0].value)
        assertEquals(0L, spans[0].fromMs)
        assertEquals(2 * minute, spans[0].toMs)
    }

    @Test
    fun `a codec change splits the span`() {
        val spans = spansOf(
            listOf(
                sample(0, codec = CodecFamily.SBC),
                sample(1, codec = CodecFamily.SBC),
                sample(2, codec = CodecFamily.LDAC),
                sample(3, codec = CodecFamily.LDAC),
            ),
        ) { it.codec }

        assertEquals(2, spans.size)
        assertEquals(CodecFamily.SBC, spans[0].value)
        assertEquals(1 * minute, spans[0].toMs)
        assertEquals(CodecFamily.LDAC, spans[1].value)
        assertEquals(2 * minute, spans[1].fromMs)
    }

    @Test
    fun `a quiet stretch splits the span even when the value never changed`() {
        // The sampler idles to nothing when the screen is off. Drawing one bar
        // straight across that hour would claim the codec was observed the
        // whole time; it was not observed at all.
        val spans = spansOf(
            listOf(
                sample(0, codec = CodecFamily.APTX),
                sample(1, codec = CodecFamily.APTX),
                sample(60, codec = CodecFamily.APTX),
                sample(61, codec = CodecFamily.APTX),
            ),
        ) { it.codec }

        assertEquals(2, spans.size)
        assertEquals(1 * minute, spans[0].toMs)
        assertEquals(60 * minute, spans[1].fromMs)
    }

    @Test
    fun `an unreadable value produces no span rather than a span of unknown`() {
        val spans = spansOf(
            listOf(
                sample(0, codec = CodecFamily.APTX),
                sample(1, codec = null),
                sample(2, codec = CodecFamily.APTX),
            ),
        ) { it.codec }

        assertEquals(2, spans.size)
        assertEquals(0L, spans[0].fromMs)
        assertEquals(0L, spans[0].toMs)
        assertEquals(2 * minute, spans[1].fromMs)
    }

    @Test
    fun `playing selects only the stretches audio actually flowed`() {
        val spans = spansOf(
            listOf(
                sample(0, playing = false),
                sample(1, playing = true),
                sample(2, playing = true),
                sample(3, playing = false),
            ),
        ) { s -> true.takeIf { s.isPlaying } }

        assertEquals(1, spans.size)
        assertEquals(1 * minute, spans[0].fromMs)
        assertEquals(2 * minute, spans[0].toMs)
    }

    @Test
    fun `coverage breaks only on silence, never on a changing value`() {
        val samples = listOf(
            sample(0, codec = CodecFamily.SBC),
            sample(1, codec = CodecFamily.LDAC),
            sample(90, codec = CodecFamily.LDAC),
        )
        val coverage = coverageSpans(samples)

        assertEquals(2, coverage.size)
        assertEquals(0L, coverage[0].fromMs)
        assertEquals(1 * minute, coverage[0].toMs)
        assertEquals(90 * minute, coverage[1].fromMs)
    }

    @Test
    fun `a lane is only offered when something really provides its value`() {
        val noRssi = listOf(sample(0, codec = CodecFamily.APTX), sample(1))
        assertFalse(hasAny(noRssi) { it.rssiDbm })
        assertTrue(hasAny(noRssi) { it.codec })

        assertTrue(hasAny(listOf(sample(0, rssi = -62))) { it.rssiDbm })
    }

    @Test
    fun `no samples means no spans, not a span of nothing`() {
        assertTrue(spansOf(emptyList<LinkQualitySample>()) { it.codec }.isEmpty())
        assertTrue(coverageSpans(emptyList()).isEmpty())
    }

    @Test
    fun `samples arriving out of order are still segmented by time`() {
        val spans = spansOf(
            listOf(
                sample(2, codec = CodecFamily.LDAC),
                sample(0, codec = CodecFamily.SBC),
                sample(1, codec = CodecFamily.SBC),
            ),
        ) { it.codec }

        assertEquals(2, spans.size)
        assertEquals(CodecFamily.SBC, spans[0].value)
        assertEquals(CodecFamily.LDAC, spans[1].value)
    }
}
