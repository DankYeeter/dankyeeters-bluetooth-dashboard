package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysBluetoothParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression cover for the codec the dashboard actually shows.
 *
 * The shape below is copied from a real `dumpsys bluetooth_manager` on a Pixel
 * 8 Pro (Android 16) with a Focal Bathys on aptX HD. It contains the three
 * traps that made the app report "SBC · 48 kHz · 16 bit" on that link:
 * the adapter-wide `codecConfigOffloading` list that starts with SBC, the
 * per-device `mCodecsSelectableCapabilities` list, and the state-machine
 * history whose `CODEC_CONFIG_CHANGED` records carry old negotiations.
 */
class DumpsysCodecAttributionTest {

    private val dump = """
        Bluetooth Status
          enabled: true
          state: ON
          codecConfigPriorities:
            UNKNOWN CODEC(7): -1
          codecConfigOffloading:
            {codecName:SBC,mCodecType:0,mCodecPriority:0,mSampleRate:0x0(NONE),mBitsPerSample:0x0(NONE),mChannelMode:0x0(NONE)}
            {codecName:AAC,mCodecType:1,mCodecPriority:0,mSampleRate:0x0(NONE),mBitsPerSample:0x0(NONE),mChannelMode:0x0(NONE)}
            {codecName:Opus,mCodecType:6,mCodecPriority:0,mSampleRate:0x0(NONE),mBitsPerSample:0x0(NONE),mChannelMode:0x0(NONE)}
          === A2dpStateMachine for XX:XX:XX:XX:91:81 ===
            mConnectionState: STATE_DISCONNECTED, mLastConnectionState: STATE_CONNECTING
            mIsPlaying: false
            mCodecConfig: {codecName:AptX-HD,mCodecType:3,mCodecPriority:4001,mSampleRate:0x2(48000),mBitsPerSample:0x2(24),mChannelMode:0x2(STEREO)}
            mCodecsSelectableCapabilities:
              {codecName:AptX-HD,mCodecType:3,mCodecPriority:4001,mSampleRate:0x3(44100|48000),mBitsPerSample:0x2(24),mChannelMode:0x2(STEREO)}
              {codecName:AptX,mCodecType:2,mCodecPriority:3001,mSampleRate:0x3(44100|48000),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO)}
              {codecName:AAC,mCodecType:1,mCodecPriority:2001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO)}
              {codecName:SBC,mCodecType:0,mCodecPriority:1001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x3(MONO|STEREO)}
            StateMachine:
              rec[1]: time=08-17 15:54:26.152 processed=Connecting what=103(0x67) CODEC_CONFIG_CHANGED: obj={mCodecConfig:{codecName:SBC,mCodecType:0,mCodecPriority:1001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x2(STEREO)}}
          === A2dpStateMachine for XX:XX:XX:XX:C0:D7 (Active) ===
            mConnectionState: STATE_CONNECTED, mLastConnectionState: STATE_CONNECTING
            mIsPlaying: true
            mCodecConfig: {codecName:LDAC,mCodecType:4,mCodecPriority:5001,mSampleRate:0x8(96000),mBitsPerSample:0x4(32),mChannelMode:0x2(STEREO)}
            mCodecsSelectableCapabilities:
              {codecName:SBC,mCodecType:0,mCodecPriority:1001,mSampleRate:0x1(44100),mBitsPerSample:0x1(16),mChannelMode:0x3(MONO|STEREO)}
    """.trimIndent()

    private fun device(suffix: String) =
        DumpsysBluetoothParser.parse(dump).devices.first { it.address.endsWith(suffix) }

    @Test
    fun `reads the negotiated codec, not the offloading list`() {
        assertEquals(CodecFamily.APTX_HD, device("91:81").codec)
    }

    @Test
    fun `reads the rate and depth of the negotiated config`() {
        val d = device("91:81")
        assertEquals(48_000, d.sampleRateHz)
        assertEquals(24, d.bitsPerSample)
    }

    @Test
    fun `the selectable-capabilities list does not overwrite the live config`() {
        // aptX HD advertises 44100|48000; the negotiated value is the single 48000.
        assertEquals(48_000, device("91:81").sampleRateHz)
    }

    @Test
    fun `stale CODEC_CONFIG_CHANGED history is ignored`() {
        // The history record for this device says SBC 44100.
        assertEquals(CodecFamily.APTX_HD, device("91:81").codec)
        assertEquals(48_000, device("91:81").sampleRateHz)
    }

    @Test
    fun `each device keeps its own codec`() {
        assertEquals(CodecFamily.LDAC, device("C0:D7").codec)
        assertEquals(96_000, device("C0:D7").sampleRateHz)
        assertEquals(32, device("C0:D7").bitsPerSample)
    }

    @Test
    fun `a bonded but disconnected device is not reported as connected`() {
        // Every bonded device keeps a state-machine block with its last codec.
        assertEquals(false, device("91:81").isConnected)
        assertEquals(true, device("C0:D7").isConnected)
    }

    @Test
    fun `the active marker in the block header is read`() {
        assertEquals(true, device("C0:D7").isActive)
        assertEquals(false, device("91:81").isActive)
    }
}

/** The spellings of one codec that all appear in real Android text sources. */
class CodecNameSpellingTest {

    @Test
    fun `every aptX HD spelling decodes to aptX HD`() {
        listOf("AptX-HD", "aptX_HD", "aptX HD", "APTXHD", "aptxhd").forEach { spelling ->
            assertEquals(
                "spelling: $spelling",
                CodecFamily.APTX_HD,
                dev.dankyeeter.btdashboard.monitor.codec.CodecDecoding.codecFamilyFromName(spelling),
            )
        }
    }

    @Test
    fun `plain aptX is still plain aptX`() {
        listOf("AptX", "aptx", "APTX").forEach { spelling ->
            assertEquals(
                "spelling: $spelling",
                CodecFamily.APTX,
                dev.dankyeeter.btdashboard.monitor.codec.CodecDecoding.codecFamilyFromName(spelling),
            )
        }
    }

    @Test
    fun `aptX Adaptive is not mistaken for aptX HD`() {
        assertEquals(
            CodecFamily.APTX_ADAPTIVE,
            dev.dankyeeter.btdashboard.monitor.codec.CodecDecoding.codecFamilyFromName("aptX-Adaptive"),
        )
    }
}
