package dev.dankyeeter.btdashboard.system.devices

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory ledger with the real baseline rule. [accept] false stands in for a
 * ledger file that cannot be written; [log] records the order of events.
 */
class FakeSettingsLedger(
    vararg initial: LedgerEntry,
    private val accept: Boolean = true,
    private val log: MutableList<String> = mutableListOf(),
) : SettingsLedger {
    var recorded: List<LedgerEntry> = initial.toList()
        private set

    override val lock = Mutex()

    override suspend fun recordIfAbsent(entry: LedgerEntry): Boolean {
        log += "record $entry"
        if (!accept) return false
        recorded = recorded.withBaseline(entry)
        return true
    }

    override suspend fun entries(): Result<List<LedgerEntry>> = Result.success(recorded)

    override suspend fun remove(entry: LedgerEntry) {
        recorded = recorded - entry
    }

    override suspend fun discardUnreadable(): Boolean = false
}

class WithBaselineTest {

    @Test
    fun `a second entry for the same setting leaves the first baseline`() {
        val first = listOf(LedgerEntry.Global("k", null))

        val after = first.withBaseline(LedgerEntry.Global("k", "2"))

        assertEquals(listOf(LedgerEntry.Global("k", null)), after)
    }

    @Test
    fun `another key or type is a different setting`() {
        val first = listOf(LedgerEntry.Global("k", null))

        val after = first
            .withBaseline(LedgerEntry.Global("j", "1"))
            .withBaseline(LedgerEntry.HdAudio("k", HdAudioPreference.ENABLE))

        assertEquals(3, after.size)
    }
}

class LedgerApplierTest {

    private val key = DeviceKey.fromAddress("AC:DE:48:00:11:22")!!
    private val avrcp = BluetoothDeveloperOptions.avrcpVersion.key
    private val absoluteKey = "bluetooth_disable_absolute_volume"
    private val reason = "the value before could not be read, so it could not be restored — nothing was written"

    private fun withOption(value: String) =
        DeviceProfile(deviceKey = key, name = "Bathys", developerOptions = mapOf(avrcp to value))

    private fun applier(
        settings: SecureSettingsController,
        ledger: SettingsLedger,
        absolute: AbsoluteVolumeController = FakeAbsoluteVolume(),
        profiles: DeviceProfileSource = object : DeviceProfileSource {
            override suspend fun profileFor(deviceKey: String): DeviceProfile? = null
        },
        codec: CodecPreferenceController = UnavailableCodecPreferenceController,
    ) = DeviceProfileApplier(
        profiles = profiles,
        volume = object : MediaVolumeController {
            override fun currentPercent(): Int? = null
            override fun setPercent(percent: Int): Boolean = true
        },
        compensation = object : CompensationApplier {
            override suspend fun apply(compensationProfileId: String): Boolean = true
        },
        absoluteVolume = absolute,
        secureSettings = settings,
        codec = codec,
        ledger = ledger,
    )

    @Test
    fun `the prior value is recorded before the write, under the lock`() = runTest {
        val log = mutableListOf<String>()
        val ledger = FakeSettingsLedger(log = log)
        val fake = FakeSecureSettings(initial = mapOf(avrcp to "avrcp14"))
        val settings = object : SecureSettingsController by fake {
            override fun write(key: String, value: String): Boolean {
                log += "write $key locked=${ledger.lock.isLocked}"
                return fake.write(key, value)
            }
        }

        applier(settings, ledger).applyNow(withOption("avrcp16"))

        assertEquals(
            listOf("record ${LedgerEntry.Global(avrcp, "avrcp14")}", "write $avrcp locked=true"),
            log,
        )
    }

    @Test
    fun `applying again keeps the first baseline`() = runTest {
        val ledger = FakeSettingsLedger()
        val settings = FakeSecureSettings()
        val applier = applier(settings, ledger)

        applier.applyNow(withOption("avrcp15"))
        applier.applyNow(withOption("avrcp16"))

        assertEquals(listOf(avrcp to "avrcp15", avrcp to "avrcp16"), settings.writes)
        assertEquals(listOf(LedgerEntry.Global(avrcp, null)), ledger.recorded)
    }

    @Test
    fun `an unreadable prior value stops the write`() = runTest {
        val settings = FakeSecureSettings(unreadable = setOf(avrcp))

        val actions = applier(settings, FakeSettingsLedger()).applyNow(withOption("avrcp16"))

        assertTrue(settings.writes.isEmpty())
        val skipped = actions.filterIsInstance<ProfileAction.Skipped>().single()
        assertEquals(BluetoothDeveloperOptions.avrcpVersion.label, skipped.what)
        assertEquals(reason, skipped.reason)
    }

    @Test
    fun `a ledger that cannot record stops the write`() = runTest {
        val settings = FakeSecureSettings(initial = mapOf(avrcp to "avrcp14"))

        val actions = applier(settings, FakeSettingsLedger(accept = false))
            .applyNow(withOption(BluetoothDeveloperOptions.USE_SYSTEM_DEFAULT))

        assertTrue(settings.clears.isEmpty())
        assertEquals(reason, actions.filterIsInstance<ProfileAction.Skipped>().single().reason)
    }

    @Test
    fun `absolute volume records its global key before writing`() = runTest {
        val ledger = FakeSettingsLedger()
        val absolute = FakeAbsoluteVolume(enabled = true)
        val profile = DeviceProfile(key, "Encore", absoluteVolumeEnabled = false)

        applier(FakeSecureSettings(initial = mapOf(absoluteKey to "0")), ledger, absolute).applyNow(profile)

        assertEquals(listOf(LedgerEntry.Global(absoluteKey, "0")), ledger.recorded)
        assertEquals(1, absolute.writes)
    }

    @Test
    fun `absolute volume reset is skipped when the ledger refuses`() = runTest {
        val absolute = FakeAbsoluteVolume()
        val profile = DeviceProfile(key, "Encore", absoluteVolumeSystemDefault = true)

        val actions = applier(FakeSecureSettings(), FakeSettingsLedger(accept = false), absolute).applyNow(profile)

        assertEquals(0, absolute.clears)
        val skipped = actions.filterIsInstance<ProfileAction.Skipped>().single()
        assertEquals("absolute volume", skipped.what)
        assertEquals(reason, skipped.reason)
    }

    // ---- AD-041: the codec wish of a profile ---------------------------------

    private val address = "AC:DE:48:00:11:22"
    private val ldac1000 = CodecPreference("LDAC", ldacQuality = 1000L)

    /** The stored profile, changeable while a test holds the ledger lock. */
    private class StoredProfile(var profile: DeviceProfile?) : DeviceProfileSource {
        override suspend fun profileFor(deviceKey: String): DeviceProfile? = profile?.takeIf { it.deviceKey == deviceKey }
    }

    private class LoggingCodec(private val log: MutableList<String>, private val ledger: SettingsLedger) :
        CodecPreferenceController {
        override fun isAvailable(): Boolean = true
        override suspend fun apply(address: String, preference: CodecPreference): CodecApplyOutcome {
            log += "codec ${preference.codec} locked=${ledger.lock.isLocked}"
            return CodecApplyOutcome.Applied(preference.codec)
        }
    }

    private fun withCodec(preference: CodecPreference?) =
        DeviceProfile(deviceKey = key, name = "Bathys", codecPreference = preference)

    /** Also V7: the fake holds a real Mutex, so a nested lock would hang this test. */
    @Test
    fun `a codec wish is recorded before the write, under the lock (AD-041, V1)`() = runTest {
        val log = mutableListOf<String>()
        val ledger = FakeSettingsLedger(log = log)
        val profile = withCodec(ldac1000)

        applier(FakeSecureSettings(), ledger, profiles = StoredProfile(profile), codec = LoggingCodec(log, ledger))
            .applyNow(profile, address)

        val entry = LedgerEntry.Ldac(key, priorWish = ldac1000, priorLive = null)
        assertEquals(listOf("record $entry", "codec LDAC locked=true"), log)
    }

    @Test
    fun `a ledger that cannot record stops the codec write (AD-041, V1)`() = runTest {
        val log = mutableListOf<String>()
        val ledger = FakeSettingsLedger(accept = false, log = log)
        val profile = withCodec(ldac1000)

        val actions = applier(FakeSecureSettings(), ledger, profiles = StoredProfile(profile), codec = LoggingCodec(log, ledger))
            .applyNow(profile, address)

        assertFalse(log.any { it.startsWith("codec") })
        val skipped = actions.filterIsInstance<ProfileAction.Skipped>().single()
        assertEquals("codec", skipped.what)
        assertEquals(reason, skipped.reason)
    }

    @Test
    fun `non-LDAC wishes and System Default are recorded too (AD-041, V1)`() = runTest {
        listOf(CodecPreference("AAC"), CodecPreference(BluetoothCodecOptions.SYSTEM_DEFAULT)).forEach { wish ->
            val log = mutableListOf<String>()
            val ledger = FakeSettingsLedger(log = log)
            val profile = withCodec(wish)

            applier(FakeSecureSettings(), ledger, profiles = StoredProfile(profile), codec = LoggingCodec(log, ledger))
                .applyNow(profile, address)

            assertEquals(listOf(LedgerEntry.Ldac(key, priorWish = wish, priorLive = null)), ledger.recorded)
            assertEquals("record came first for ${wish.codec}", 2, log.size)
        }
    }

    @Test
    fun `without an address the ledger is not touched (AD-041, V1)`() = runTest {
        val log = mutableListOf<String>()
        val ledger = FakeSettingsLedger(log = log)
        val profile = withCodec(ldac1000)

        applier(FakeSecureSettings(), ledger, profiles = StoredProfile(profile), codec = LoggingCodec(log, ledger))
            .applyNow(profile, address = null)

        assertTrue(log.isEmpty())
    }

    @Test
    fun `the prior wish is read fresh under the lock, not from the profile handed in (SR-032, V2)`() = runTest {
        val log = mutableListOf<String>()
        val ledger = FakeSettingsLedger(log = log)
        val stale = withCodec(CodecPreference("LDAC", ldacQuality = 1002L))
        val stored = StoredProfile(stale)
        val applier = applier(FakeSecureSettings(), ledger, profiles = stored, codec = LoggingCodec(log, ledger))

        ledger.lock.lock()
        val applying = launch { applier.applyNow(stale, address) }
        runCurrent()
        // The way back ran meanwhile and withdrew the wish.
        stored.profile = withCodec(null)
        ledger.lock.unlock()
        applying.join()

        assertEquals(listOf(LedgerEntry.Ldac(key, priorWish = null, priorLive = null)), ledger.recorded)
    }

    @Test
    fun `a profile gone from the store is neither recorded nor written (V2)`() = runTest {
        val log = mutableListOf<String>()
        val ledger = FakeSettingsLedger(log = log)

        val actions = applier(FakeSecureSettings(), ledger, profiles = StoredProfile(null), codec = LoggingCodec(log, ledger))
            .applyNow(withCodec(ldac1000), address)

        assertTrue(log.isEmpty())
        assertEquals(reason, actions.filterIsInstance<ProfileAction.Skipped>().single().reason)
    }

    @Test
    fun `the placeholder ledger never discards anything (AD-042)`() = runTest {
        assertFalse(NoSettingsLedger.discardUnreadable())
    }
}
