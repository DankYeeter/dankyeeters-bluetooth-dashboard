package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.link.BroadcastEventMapper
import dev.dankyeeter.btdashboard.monitor.link.BtActions
import dev.dankyeeter.btdashboard.monitor.link.MonitorEventType
import dev.dankyeeter.btdashboard.monitor.link.RawBroadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BroadcastEventMapperTest {

    private val encore = RawBroadcast(
        action = BtActions.ACL_CONNECTED,
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        deviceName = "Encore",
    )

    @Test
    fun `acl connect and disconnect map to events`() {
        val connect = BroadcastEventMapper.map(encore, 100)!!
        assertEquals(MonitorEventType.ACL_CONNECTED, connect.type)
        assertEquals("Encore connected", connect.detail)

        val disconnect = BroadcastEventMapper
            .map(encore.copy(action = BtActions.ACL_DISCONNECTED), 200)!!
        assertEquals(MonitorEventType.ACL_DISCONNECTED, disconnect.type)
    }

    @Test
    fun `playing state extra decides the event type`() {
        val playing = BroadcastEventMapper.map(
            encore.copy(
                action = BtActions.A2DP_PLAYING_STATE_CHANGED,
                ints = mapOf(BtActions.EXTRA_STATE to BtActions.STATE_PLAYING),
            ),
            300,
        )!!
        assertEquals(MonitorEventType.PLAYING_STARTED, playing.type)

        val stopped = BroadcastEventMapper.map(
            encore.copy(
                action = BtActions.A2DP_PLAYING_STATE_CHANGED,
                ints = mapOf(BtActions.EXTRA_STATE to BtActions.STATE_NOT_PLAYING),
            ),
            400,
        )!!
        assertEquals(MonitorEventType.PLAYING_STOPPED, stopped.type)
    }

    @Test
    fun `unknown playing state is dropped instead of guessed`() {
        assertNull(
            BroadcastEventMapper.map(
                encore.copy(
                    action = BtActions.A2DP_PLAYING_STATE_CHANGED,
                    ints = mapOf(BtActions.EXTRA_STATE to 99),
                ),
                500,
            ),
        )
    }

    @Test
    fun `codec change reads the codec from the stringified extra`() {
        val event = BroadcastEventMapper.map(
            encore.copy(
                action = BtActions.A2DP_CODEC_CONFIG_CHANGED,
                strings = mapOf("codec" to "codecConfig{codecName:LDAC,mCodecType:4}"),
            ),
            600,
        )!!
        assertEquals(MonitorEventType.CODEC_CHANGED, event.type)
        assertEquals(CodecFamily.LDAC, event.codec)
        assertEquals("Codec is now LDAC on Encore", event.detail)
    }

    @Test
    fun `active device change without a device means no active device`() {
        val event = BroadcastEventMapper.map(
            RawBroadcast(BtActions.A2DP_ACTIVE_DEVICE_CHANGED, null, null),
            700,
        )!!
        assertEquals(MonitorEventType.ACTIVE_DEVICE_CHANGED, event.type)
        assertEquals("No active Bluetooth audio device", event.detail)
    }

    @Test
    fun `unknown actions are ignored`() {
        assertNull(BroadcastEventMapper.map(encore.copy(action = "com.oem.SOMETHING"), 800))
    }
}
