package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysBluetoothParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-sample tests. The dump format is version-fragile, so every sample we
 * care about is pinned here; samples are never edited to make the parser pass.
 */
class DumpsysBluetoothParserTest {

    private fun load(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("dumps/$name")) {
            "missing test resource dumps/$name"
        }.bufferedReader().use { it.readText() }

    @Test
    fun `pixel 8 LDAC dump yields both devices with codec and rssi`() {
        val snapshot = DumpsysBluetoothParser.parse(load("bt_manager_pixel8_ldac.txt"))
        val encore = snapshot.devices.first { it.address == "xx:xx:xx:xx:ab:cd" }

        assertEquals(CodecFamily.LDAC, encore.codec)
        assertEquals(4, encore.rawCodecType)
        assertEquals(96_000, encore.sampleRateHz)
        assertEquals(24, encore.bitsPerSample)
        assertEquals(-62, encore.rssiDbm)
        assertTrue(encore.isPlaying)
        assertTrue(encore.isActive)
        assertEquals("Noble FoKus Prestige Encore", encore.name)

        val boom = snapshot.devices.first { it.address == "xx:xx:xx:xx:77:0a" }
        assertEquals(CodecFamily.SBC, boom.codec)
        assertEquals(44_100, boom.sampleRateHz)
        assertEquals(-71, boom.rssiDbm)
        assertFalse(boom.isPlaying)
    }

    /**
     * Also the case that shows what "name first" buys: this dump prints
     * `codecName=aptX Adaptive` next to `mCodecType=8`, and 8 is one of the ids
     * this app no longer names. The label survives because it never depended on
     * the number when a name was there.
     */
    @Test
    fun `pixel 11 aptX Adaptive dump uses the other key spelling`() {
        val snapshot = DumpsysBluetoothParser.parse(load("bt_manager_pixel11_aptx_adaptive.txt"))
        val encore = snapshot.devices.first { it.address == "xx:xx:xx:xx:ab:cd" }

        assertEquals(CodecFamily.APTX_ADAPTIVE, encore.codec)
        assertEquals(8, encore.rawCodecType)
        assertEquals(48_000, encore.sampleRateHz)
        assertEquals(24, encore.bitsPerSample)
        assertEquals(-58, encore.rssiDbm)
        assertTrue(encore.isPlaying)
        assertTrue(encore.isActive)
    }

    @Test
    fun `a truncated dump degrades to warnings instead of throwing`() {
        val snapshot = DumpsysBluetoothParser.parse(load("bt_manager_truncated.txt"))
        assertTrue(snapshot.isEmpty)
        assertTrue(snapshot.warnings.any { it.contains("no devices") })
    }

    @Test
    fun `garbage input never crashes the parser`() {
        val garbage = DumpsysBluetoothParser.parse("\u0000 not a dump {{{ ][ mSampleRate:")
        assertNotNull(garbage)
        assertTrue(garbage.isEmpty)
        assertTrue(DumpsysBluetoothParser.parse("").warnings.isNotEmpty())
    }

    @Test
    fun `implausible rssi values are rejected`() {
        val dump = """
            Bluetooth Status
            RemoteDevices:
              Address: AA:BB:CC:DD:EE:FF
                Rssi: 4200
        """.trimIndent()
        assertNull(DumpsysBluetoothParser.parse(dump).devices.single().rssiDbm)
    }
}
