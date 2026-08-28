package dev.dankyeeter.btdashboard.system.devices

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pinned playback quality has to be asked for again on every connect.
 *
 * ## Why this is a test and not an obvious consequence
 *
 * The Bluetooth stack renegotiates the A2DP codec each time a headphone
 * connects, so `setCodecConfigPreference` is not a setting — it is a request
 * with a lifetime of one link. That is exactly what the Monitoring panel's chips
 * used to do and all they did: pin 990, take the headphones off, put them back
 * on, and the link is adaptive again with nothing on screen admitting it.
 *
 * The fix is that the chips write into the profile, and the profile is replayed
 * by [DeviceProfileApplier] on `ACTION_ACL_CONNECTED`. This pins the second half
 * of that: a profile carrying an LDAC quality really does re-request **that
 * quality**, with the raw address, and reports what was read back rather than
 * what was asked for.
 */
class LdacQualityOnConnectTest {

    private val address = "AC:DE:48:00:11:22"
    private val key = DeviceKey.fromAddress(address)!!

    /** Records the request and answers with whatever the test wants observed. */
    private class FakeCodecPreferences(
        private val available: Boolean = true,
        private val outcome: (CodecPreference) -> CodecApplyOutcome = {
            CodecApplyOutcome.Applied("LDAC · 96 kHz · 32 bit")
        },
    ) : CodecPreferenceController {
        val requests = mutableListOf<Pair<String, CodecPreference>>()

        override fun isAvailable(): Boolean = available

        override suspend fun apply(
            address: String,
            preference: CodecPreference,
        ): CodecApplyOutcome {
            requests += address to preference
            return outcome(preference)
        }
    }

    private fun applier(
        profile: DeviceProfile,
        codec: CodecPreferenceController,
    ) = DeviceProfileApplier(
        profiles = object : DeviceProfileSource {
            override suspend fun profileFor(deviceKey: String): DeviceProfile? =
                profile.takeIf { it.deviceKey == deviceKey }
        },
        volume = object : MediaVolumeController {
            override fun currentPercent(): Int? = 50
            override fun setPercent(percent: Int): Boolean = true
        },
        compensation = object : CompensationApplier {
            override suspend fun apply(compensationProfileId: String): Boolean = true
        },
        absoluteVolume = object : AbsoluteVolumeController {
            override fun isWritable(): Boolean = true
            override fun isEnabled(): Boolean = true
            override fun setEnabled(enabled: Boolean): Boolean = true
            override fun clear(): Boolean = true
        },
        secureSettings = object : SecureSettingsController {
            override fun isWritable(): Boolean = true
            override fun read(key: String): String? = null
            override fun write(key: String, value: String): Boolean = true
            override fun clear(key: String): Boolean = true
        },
        codec = codec,
    )

    private fun pinned(quality: Long) = DeviceProfile(
        deviceKey = key,
        name = "Bathys",
        codecPreference = CodecPreference(codec = "LDAC", ldacQuality = quality),
    )

    @Test
    fun `a stored quality is requested again when the device connects`() = runTest {
        val codec = FakeCodecPreferences()

        val result = applier(pinned(1000L), codec).onDeviceConnected(address)

        val (requestedAddress, preference) = codec.requests.single()
        assertEquals("the raw address is what the API takes", address, requestedAddress)
        assertEquals("LDAC", preference.codec)
        assertEquals(1000L, preference.ldacQuality)
        assertTrue(result is ApplyResult.Applied)
        assertTrue(
            "a read-back has to be reported as one",
            (result as ApplyResult.Applied).actions.any { it is ProfileAction.CodecSet },
        )
    }

    /**
     * Withdrawing a pin is not the same as never having had one.
     *
     * Choosing ABR clears the quality and leaves the codec wish standing, so the
     * connect still asks for LDAC and stops forcing a rate. The zero has to
     * travel unchanged — a helper that saw 1003 here would renegotiate on every
     * connect to reach the state the stack was already in.
     */
    @Test
    fun `a withdrawn pin re-requests the codec without a rate`() = runTest {
        val codec = FakeCodecPreferences()

        applier(pinned(0L), codec).onDeviceConnected(address)

        assertEquals(0L, codec.requests.single().second.ldacQuality)
    }

    /**
     * Without the helper the step is *skipped with a reason*, never reported as
     * left alone — the profile still holds the wish and will ask again.
     */
    @Test
    fun `no helper is a stated skip rather than silence`() = runTest {
        val codec = FakeCodecPreferences(available = false)

        val result = applier(pinned(1000L), codec).onDeviceConnected(address)

        assertTrue(codec.requests.isEmpty())
        val skip = (result as ApplyResult.Applied).actions
            .filterIsInstance<ProfileAction.Skipped>()
            .single { it.what == "codec" }
        assertTrue(skip.reason.contains("helper"))
    }

    /**
     * A quality the stack did not take is neither a success nor a failure, and
     * the applier has a separate action for exactly that.
     */
    @Test
    fun `a rate that did not stick is reported as not observed`() = runTest {
        val codec = FakeCodecPreferences(
            outcome = {
                CodecApplyOutcome.NotObserved("LDAC · 96 kHz", "still adaptive after 2000 ms")
            },
        )

        val result = applier(pinned(1000L), codec).onDeviceConnected(address)

        val action = (result as ApplyResult.Applied).actions
            .filterIsInstance<ProfileAction.CodecNotObserved>()
            .single()
        assertTrue(action.detail.contains("2000 ms"))
    }

    /**
     * The wire-format contract for the field this whole feature stores.
     *
     * Device profiles do not travel in the app's backup document — that carries
     * hearing runs, the audiogram and compensation profiles — so the *stored*
     * shape is the only contract there is, and this is it: a quality is only
     * legal on LDAC, only at the four values AOSP defines, and zero always means
     * "no preference". [DeviceProfile.sanitized] drops anything else on the way
     * in, which is what protects the Bluetooth stack from restored rubbish.
     */
    @Test
    fun `only a real ldac quality survives sanitising`() {
        val good = pinned(1000L).sanitized()
        assertEquals(1000L, good.codecPreference?.ldacQuality)

        // A rate AOSP does not define.
        assertEquals(null, pinned(1500L).sanitized().codecPreference)

        // A rate on a codec that has no such knob.
        val wrongCodec = DeviceProfile(
            deviceKey = key,
            name = "Bathys",
            codecPreference = CodecPreference(codec = "AAC", ldacQuality = 1000L),
        )
        assertEquals(null, wrongCodec.sanitized().codecPreference)

        // Zero is the defaulted state and is always legal.
        assertEquals(0L, pinned(0L).sanitized().codecPreference?.ldacQuality)
    }
}
