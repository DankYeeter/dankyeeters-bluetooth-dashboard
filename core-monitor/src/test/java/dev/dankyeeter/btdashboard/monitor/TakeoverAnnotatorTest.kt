package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.link.MonitorEvent
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
import dev.dankyeeter.btdashboard.monitor.link.TakeoverAnnotator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoverAnnotatorTest {

    private val encore = "AA:BB:CC:DD:EE:FF"
    private val boom = "11:22:33:44:55:66"

    private fun event(
        type: MonitorEventType,
        address: String?,
        name: String?,
        at: Long,
    ) = MonitorEvent(at, address, name, type, "raw")

    @Test
    fun `stop followed by an active device change is annotated as a takeover`() {
        val annotator = TakeoverAnnotator()
        annotator.onEvent(event(MonitorEventType.ACTIVE_DEVICE_CHANGED, encore, "Encore", 0))
        annotator.onEvent(event(MonitorEventType.PLAYING_STARTED, encore, "Encore", 10))

        val fromStop = annotator.onEvent(
            event(MonitorEventType.PLAYING_STOPPED, encore, "Encore", 1_000),
        )
        assertTrue(fromStop.isEmpty()) // still ambiguous at this point

        val derived = annotator.onEvent(
            event(MonitorEventType.ACTIVE_DEVICE_CHANGED, boom, "Motion Boom", 1_200),
        )
        assertEquals(1, derived.size)
        assertEquals(MonitorEventType.TAKEOVER, derived[0].type)
        assertEquals(
            "Playback paused — Motion Boom took the stream from Encore",
            derived[0].detail,
        )
        // The annotation is stamped at the moment playback actually stopped.
        assertEquals(1_000L, derived[0].timestampMs)
    }

    @Test
    fun `a lone stop becomes an interruption once the window passes`() {
        val annotator = TakeoverAnnotator(correlationWindowMs = 3_000)
        annotator.onEvent(event(MonitorEventType.PLAYING_STARTED, encore, "Encore", 0))
        annotator.onEvent(event(MonitorEventType.PLAYING_STOPPED, encore, "Encore", 1_000))

        assertTrue(annotator.flushPending(2_000).isEmpty()) // window still open

        val flushed = annotator.flushPending(5_000)
        assertEquals(1, flushed.size)
        assertEquals(MonitorEventType.INTERRUPTION, flushed[0].type)
        assertTrue(flushed[0].detail.contains("stayed connected"))
        // Flushing twice must not duplicate the annotation.
        assertTrue(annotator.flushPending(9_000).isEmpty())
    }

    @Test
    fun `an active device change far after the stop is not a takeover`() {
        val annotator = TakeoverAnnotator(correlationWindowMs = 3_000)
        annotator.onEvent(event(MonitorEventType.PLAYING_STARTED, encore, "Encore", 0))
        annotator.onEvent(event(MonitorEventType.PLAYING_STOPPED, encore, "Encore", 1_000))
        val derived = annotator.onEvent(
            event(MonitorEventType.ACTIVE_DEVICE_CHANGED, boom, "Motion Boom", 60_000),
        )
        assertTrue(derived.none { it.type == MonitorEventType.TAKEOVER })
    }

    @Test
    fun `stopping while another device is already active is an immediate takeover`() {
        val annotator = TakeoverAnnotator()
        annotator.onEvent(event(MonitorEventType.PLAYING_STARTED, encore, "Encore", 0))
        annotator.onEvent(event(MonitorEventType.ACTIVE_DEVICE_CHANGED, boom, "Motion Boom", 500))
        val derived = annotator.onEvent(
            event(MonitorEventType.PLAYING_STOPPED, encore, "Encore", 600),
        )
        assertEquals(MonitorEventType.TAKEOVER, derived.single().type)
    }

    @Test
    fun `playing state tracking survives disconnects`() {
        val annotator = TakeoverAnnotator()
        annotator.onEvent(event(MonitorEventType.PLAYING_STARTED, encore, "Encore", 0))
        assertTrue(annotator.anyPlaying)
        annotator.onEvent(event(MonitorEventType.ACL_DISCONNECTED, encore, "Encore", 100))
        assertTrue(!annotator.anyPlaying)
    }
}
