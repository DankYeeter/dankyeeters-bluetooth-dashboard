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

    /**
     * The whole `BluetoothCodecStatus` blob, which is what the real broadcast
     * actually carries — not the one-config extract the test above uses.
     *
     * Codec entries are the verbatim spellings from
     * `dumps/bt_manager_pixel11_ldac_txqueue.txt`, plus the `LHDCv5` line the
     * owner's phone prints, arranged the way `toString()` prints them: the
     * negotiated config first, then everything the two ends could have agreed
     * on instead.
     */
    private val ldacStatusBlob =
        "BluetoothCodecStatus{mCodecConfig:{codecName:LDAC,mCodecType:4,mCodecPriority:5001," +
            "mSampleRate:0x8(96000),mBitsPerSample:0x4(32),mChannelMode:0x2(STEREO)}," +
            "mCodecsLocalCapabilities:[{codecName:SBC,mCodecType:0},{codecName:AAC,mCodecType:1}," +
            "{codecName:AptX,mCodecType:2},{codecName:AptX-HD,mCodecType:3}," +
            "{codecName:LDAC,mCodecType:4},{codecName:Opus,mCodecType:6}," +
            "{codecName:LHDCv5,mCodecType:7}]," +
            "mCodecsSelectableCapabilities:[{codecName:SBC,mCodecType:0}," +
            "{codecName:AptX-HD,mCodecType:3},{codecName:LDAC,mCodecType:4}]}"

    /**
     * Live repro, 18:30:35 on the owner's phone: the events list announced
     * "Codec is now aptX HD" on a link every `mCodecConfig` in the same dump
     * records as `codecName:LDAC,mCodecType:4`.
     *
     * Nothing was wrong with the codec table. The mapper searched the entire
     * blob for a brand, and aptX HD is in the capability list — a codec the
     * link *could* have used, announced as the one it did. The negotiated
     * section is the only part of that string that describes the link.
     */
    @Test
    fun `a codec change names the negotiated codec, not one from the capability list`() {
        val event = BroadcastEventMapper.map(
            encore.copy(
                action = BtActions.A2DP_CODEC_CONFIG_CHANGED,
                strings = mapOf("codec" to ldacStatusBlob),
            ),
            700,
        )!!
        assertEquals(CodecFamily.LDAC, event.codec)
        assertEquals("Codec is now LDAC on Encore", event.detail)
    }

    /**
     * The same blob with the type-7 link negotiated. Two ways to get this
     * wrong meet here: naming a codec from the capability list, and naming
     * type 7 aptX Adaptive. It is LHDC v5, and the phone says so.
     */
    @Test
    fun `a negotiated LHDCv5 link is named by its own codecName`() {
        val event = BroadcastEventMapper.map(
            encore.copy(
                action = BtActions.A2DP_CODEC_CONFIG_CHANGED,
                strings = mapOf(
                    "codec" to ldacStatusBlob.replace(
                        "mCodecConfig:{codecName:LDAC,mCodecType:4",
                        "mCodecConfig:{codecName:LHDCv5,mCodecType:7",
                    ),
                ),
            ),
            800,
        )!!
        assertEquals(CodecFamily.LHDC_V5, event.codec)
        assertEquals("Codec is now LHDC v5 on Encore", event.detail)
    }

    /**
     * A codec type the blob names nothing for is announced as the number it
     * is. "Vendor codec (type 9)" is a thing the user can look up; a brand
     * picked off a list next to it is not.
     */
    @Test
    fun `an unnamed vendor codec is announced with its raw type`() {
        val event = BroadcastEventMapper.map(
            encore.copy(
                action = BtActions.A2DP_CODEC_CONFIG_CHANGED,
                strings = mapOf("codec" to "codecConfig{mCodecType:9}"),
            ),
            900,
        )!!
        assertEquals(CodecFamily.VENDOR, event.codec)
        assertEquals("Codec is now Vendor codec (type 9) on Encore", event.detail)
    }

    /** A build that sends the plain int extra and no status object at all. */
    @Test
    fun `the int extra still answers when no status object was sent`() {
        val event = BroadcastEventMapper.map(
            encore.copy(
                action = BtActions.A2DP_CODEC_CONFIG_CHANGED,
                ints = mapOf("codecType" to 4),
            ),
            1_000,
        )!!
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
