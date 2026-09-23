package dev.dankyeeter.btdashboard.ui.tuning

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.monitor.codec.BtAudioDevice
import dev.dankyeeter.btdashboard.monitor.link.live.LdacQualityMode
import dev.dankyeeter.btdashboard.system.devices.CodecApplyOutcome
import dev.dankyeeter.btdashboard.system.devices.CodecPreference
import dev.dankyeeter.btdashboard.system.devices.DeviceKey
import dev.dankyeeter.btdashboard.system.devices.DeviceProfile
import dev.dankyeeter.btdashboard.system.devices.DeviceProfileSource
import dev.dankyeeter.btdashboard.system.devices.DeviceProfileStore
import dev.dankyeeter.btdashboard.system.devices.HdAudioController
import dev.dankyeeter.btdashboard.system.devices.HdAudioOutcome
import dev.dankyeeter.btdashboard.system.devices.HdAudioPreference
import dev.dankyeeter.btdashboard.system.devices.HdAudioState
import dev.dankyeeter.btdashboard.system.devices.LedgerEntry
import dev.dankyeeter.btdashboard.system.devices.SecureSettingsController
import dev.dankyeeter.btdashboard.system.devices.SettingRead
import dev.dankyeeter.btdashboard.system.devices.SettingsLedger
import dev.dankyeeter.btdashboard.system.devices.withBaseline
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val SHOW_NAMELESS = "bluetooth_show_devices_without_names"
private const val AVRCP = "bluetooth_avrcp_version"
private const val ABSOLUTE_VOLUME = "bluetooth_disable_absolute_volume"

private const val ADDRESS_AWAY = "AC:DE:48:00:37:8F"
private const val ADDRESS_HERE = "AC:DE:48:00:11:22"
private val KEY_AWAY = DeviceKey.fromAddress(ADDRESS_AWAY)!!
private val KEY_HERE = DeviceKey.fromAddress(ADDRESS_HERE)!!

private val LDAC_990 = CodecPreference(codec = "LDAC", ldacQuality = LdacQuality.HIGH_QUALITY)

/** Keeps entries in memory with the real "first baseline wins" rule. */
private class FakeLedger(var held: List<LedgerEntry> = emptyList(), val refuse: Boolean = false) : SettingsLedger {
    override val lock = Mutex()
    val recordLog = mutableListOf<String>()
    var onRecord: suspend () -> Unit = {}

    override suspend fun recordIfAbsent(entry: LedgerEntry): Boolean {
        onRecord()
        recordLog += "record"
        if (refuse) return false
        held = held.withBaseline(entry)
        return true
    }

    override suspend fun entries(): Result<List<LedgerEntry>> = Result.success(held)

    override suspend fun remove(entry: LedgerEntry) {
        held = held - entry
    }
}

private class FakeGlobals(val log: MutableList<String>) : SecureSettingsController {
    val values = mutableMapOf<String, String>()
    var failWrites = false
    var unreadable = false
    var beforeWrite: () -> Unit = {}

    override fun isWritable() = true
    override fun read(key: String) = values[key]
    override fun readState(key: String): SettingRead = when {
        unreadable -> SettingRead.Unreadable
        else -> values[key]?.let(SettingRead::Value) ?: SettingRead.Unset
    }

    override fun write(key: String, value: String): Boolean {
        beforeWrite()
        log += "write $key=$value"
        if (failWrites) return false
        values[key] = value
        return true
    }

    override fun clear(key: String): Boolean {
        beforeWrite()
        log += "clear $key"
        values.remove(key)
        return true
    }
}

/**
 * Returns [profile] on the first read, then null — standing in for a
 * DataStore read that fails right after a successful save. [DeviceProfileStore]
 * cannot be made to do this on the real disk (point 2, T-047j).
 */
private class FlakyProfileSource(private val profile: DeviceProfile) : DeviceProfileSource {
    private var calls = 0
    override suspend fun profileFor(deviceKey: String): DeviceProfile? {
        calls++
        return if (calls == 1) profile else null
    }
}

private class FakeHdAudio(val log: MutableList<String>) : HdAudioController {
    override fun isAvailable() = true
    override suspend fun read(address: String): HdAudioState = HdAudioState.Known(supported = true, enabled = true)
    override suspend fun apply(address: String, preference: HdAudioPreference): HdAudioOutcome {
        log += "hd $preference"
        return HdAudioOutcome.Applied(preference.asEnabled())
    }
}

/** Robolectric for the real `DeviceProfileStore` and `org.json`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsRestoreTest {

    private val log = mutableListOf<String>()
    private val globals = FakeGlobals(log)
    private val profiles = DeviceProfileStore(ApplicationProvider.getApplicationContext<Application>())

    @Before
    fun emptyProfiles() = runBlocking {
        profiles.current().forEach { profiles.delete(it.deviceKey) }
    }

    private fun restore(
        ledger: SettingsLedger,
        connected: List<String> = listOf(ADDRESS_HERE),
        saveProfile: suspend (DeviceProfile) -> Unit = profiles::save,
    ) = SettingsRestore(
        ledger = ledger,
        globals = globals,
        hdAudio = FakeHdAudio(log),
        profiles = profiles,
        currentProfiles = profiles::current,
        saveProfile = saveProfile,
        connected = { connected.map { BtAudioDevice(it, "Bathys") } },
        requestLdac = { _, quality ->
            log += "ldac $quality"
            CodecApplyOutcome.Applied("LDAC")
        },
    )

    @Test
    fun `everything goes back, Autoapply first, and only what did not stays held`() = runTest {
        globals.values[SHOW_NAMELESS] = "1"
        globals.values[AVRCP] = "avrcp16"
        profiles.save(DeviceProfile(deviceKey = KEY_HERE, name = "Bathys", codecPreference = LDAC_990))
        profiles.save(DeviceProfile(deviceKey = "eq-only", name = "EQ only", mediaVolumePercent = 40))
        val hdAway = LedgerEntry.HdAudio(KEY_AWAY, HdAudioPreference.DISABLE)
        val ledger = FakeLedger(
            listOf(
                LedgerEntry.Global(SHOW_NAMELESS, prior = null),
                LedgerEntry.Global(AVRCP, prior = "avrcp14"),
                hdAway,
                LedgerEntry.Ldac(KEY_HERE, priorWish = null, priorLive = LdacQuality.ADAPTIVE),
            ),
        )
        val autoApplyAtEachWrite = mutableListOf<Boolean?>()
        globals.beforeWrite = { autoApplyAtEachWrite += runBlocking { profiles.profileFor(KEY_HERE)?.autoApply } }

        val report = restore(ledger).restoreAll()

        assertEquals(listOf("clear $SHOW_NAMELESS", "write $AVRCP=avrcp14", "ldac ${LdacQuality.ADAPTIVE}"), log)
        assertEquals("Autoapply was off before the first write-back", listOf(false, false), autoApplyAtEachWrite)
        assertEquals(listOf(hdAway), report.pending.map { it.first })
        assertTrue(report.pending.single().second.contains("not connected"))
        assertNull(profiles.profileFor(KEY_HERE)?.codecPreference)
        assertFalse(profiles.profileFor(KEY_HERE)!!.autoApply)
        assertTrue("an EQ/volume-only profile keeps Autoapply", profiles.profileFor("eq-only")!!.autoApply)
        assertEquals(listOf("Bathys"), report.autoApplyPausedFor)
        assertEquals(listOf(KEY_HERE), report.liveRestored)
        assertEquals("only the entry that did not go back is still held", listOf(hdAway), ledger.held)
    }

    @Test
    fun `a save that fails while pausing Autoapply writes nothing (M10)`() = runTest {
        profiles.save(DeviceProfile(deviceKey = KEY_HERE, name = "Bathys", codecPreference = LDAC_990))
        val entry = LedgerEntry.Global(AVRCP, prior = "avrcp14")
        val ledger = FakeLedger(listOf(entry))
        val restoreWithFailingSave = restore(ledger, saveProfile = { throw IOException("disk full") })

        val report = restoreWithFailingSave.restoreAll()

        assertTrue("nothing was written once Autoapply could not be paused", log.isEmpty())
        assertEquals(listOf(entry), ledger.held)
        assertEquals(listOf(entry), report.pending.map { it.first })
        assertTrue(report.autoApplyPausedFor.isEmpty())
        assertTrue(report.pending.single().second.contains("Autoapply"))
    }

    @Test
    fun `a confirm read that fails does not count as put back (point 2, T-047j)`() = runTest {
        val profile = DeviceProfile(deviceKey = KEY_HERE, name = "Bathys", codecPreference = LDAC_990)
        val entry = LedgerEntry.Ldac(KEY_HERE, priorWish = null, priorLive = null)
        val ledger = FakeLedger(listOf(entry))
        val flakyProfiles = FlakyProfileSource(profile)
        val restoreFlaky = SettingsRestore(
            ledger = ledger,
            globals = globals,
            hdAudio = FakeHdAudio(log),
            profiles = flakyProfiles,
            currentProfiles = { emptyList() },
            saveProfile = {},
            connected = { emptyList() },
            requestLdac = { _, _ -> CodecApplyOutcome.Applied("LDAC") },
        )

        val report = restoreFlaky.restoreAll()

        assertEquals(listOf(entry), report.pending.map { it.first })
        assertEquals(listOf(entry), ledger.held)
    }

    @Test
    fun `a write that does not stick keeps its entry and names it`() = runTest {
        globals.failWrites = true
        val entry = LedgerEntry.Global(AVRCP, prior = "avrcp14")
        val ledger = FakeLedger(listOf(entry))

        val report = restore(ledger).restoreAll()

        assertEquals(listOf(entry), ledger.held)
        assertEquals(listOf(entry), report.pending.map { it.first })
        assertTrue(report.restored.isEmpty())
    }

    @Test
    fun `a key outside the fixed list is never written (M8)`() = runTest {
        val entry = LedgerEntry.Global("adb_enabled", prior = "1")
        val ledger = FakeLedger(listOf(entry))

        val report = restore(ledger).restoreAll()

        assertTrue(log.isEmpty())
        assertEquals(listOf(entry), report.pending.map { it.first })
        assertEquals(listOf(entry), ledger.held)
    }

    @Test
    fun `absolute volume is on the fixed list and goes back through the globals`() = runTest {
        val ledger = FakeLedger(listOf(LedgerEntry.Global(ABSOLUTE_VOLUME, prior = "1")))

        restore(ledger).restoreAll()

        assertEquals(listOf("write $ABSOLUTE_VOLUME=1"), log)
        assertTrue(ledger.held.isEmpty())
    }

    @Test
    fun `only a read-back of not set confirms a delete (M9)`() = runTest {
        globals.values[SHOW_NAMELESS] = "1"
        globals.unreadable = true
        val entry = LedgerEntry.Global(SHOW_NAMELESS, prior = null)
        val ledger = FakeLedger(listOf(entry))

        val report = restore(ledger).restoreAll()

        assertEquals(listOf(entry), report.pending.map { it.first })
        assertEquals(listOf(entry), ledger.held)
    }

    @Test
    fun `connected with an unreadable level before, the level is not invented (M12)`() = runTest {
        val entry = LedgerEntry.Ldac(KEY_HERE, priorWish = null, priorLive = null)
        val ledger = FakeLedger(listOf(entry))

        val report = restore(ledger).restoreAll()

        assertTrue("no live request", log.isEmpty())
        assertEquals(listOf(KEY_HERE), report.liveKeptUntilReconnect)
        assertTrue(report.liveRestored.isEmpty())
        assertEquals(listOf<LedgerEntry>(entry), report.restored)
        assertTrue(ledger.held.isEmpty())
    }

    @Test
    fun `not connected, the live level counts as gone with the link`() = runTest {
        profiles.save(DeviceProfile(deviceKey = KEY_AWAY, name = "Bathys", codecPreference = LDAC_990))
        val ledger = FakeLedger(listOf(LedgerEntry.Ldac(KEY_AWAY, priorWish = null, priorLive = LdacQuality.STANDARD)))

        val report = restore(ledger, connected = emptyList()).restoreAll()

        assertTrue(log.isEmpty())
        assertNull(profiles.profileFor(KEY_AWAY)?.codecPreference)
        assertTrue(report.liveRestored.isEmpty() && report.liveKeptUntilReconnect.isEmpty())
        assertTrue(ledger.held.isEmpty())
    }

    // ---- LdacTuning.recordThenPin -------------------------------------------

    @Test
    fun `a pin holds the value before, then stores, then asks the link`() = runTest {
        val ledger = FakeLedger()
        ledger.onRecord = { log += "profile at record: ${profiles.profileFor(KEY_HERE)}" }

        LdacTuning.recordThenPin(
            ledger = ledger,
            profiles = profiles,
            deviceKey = KEY_HERE,
            device = BtAudioDevice(ADDRESS_HERE, "Bathys"),
            liveMode = LdacQualityMode.NOT_PINNED,
            quality = LdacQuality.HIGH_QUALITY,
            apply = { _, _ ->
                log += "apply"
                CodecApplyOutcome.Applied("LDAC")
            },
        )

        assertEquals("nothing stored yet when the value before was held", listOf("profile at record: null", "apply"), log)
        assertEquals(listOf(LedgerEntry.Ldac(KEY_HERE, priorWish = null, priorLive = LdacQuality.NONE)), ledger.held)
        assertEquals(LDAC_990, profiles.profileFor(KEY_HERE)?.codecPreference)
    }

    @Test
    fun `a second pin keeps the first baseline`() = runTest {
        val ledger = FakeLedger()
        repeat(2) { i ->
            LdacTuning.recordThenPin(
                ledger = ledger,
                profiles = profiles,
                deviceKey = KEY_HERE,
                device = null,
                liveMode = null,
                quality = if (i == 0) LdacQuality.HIGH_QUALITY else LdacQuality.STANDARD,
                apply = { _, _ -> CodecApplyOutcome.Unavailable("no link") },
            )
        }

        assertEquals(listOf(LedgerEntry.Ldac(KEY_HERE, priorWish = null, priorLive = null)), ledger.held)
    }

    @Test
    fun `a ledger that cannot hold the value before stops the pin`() = runTest {
        val state = LdacTuning.recordThenPin(
            ledger = FakeLedger(refuse = true),
            profiles = profiles,
            deviceKey = KEY_HERE,
            device = BtAudioDevice(ADDRESS_HERE, "Bathys"),
            liveMode = null,
            quality = LdacQuality.HIGH_QUALITY,
            apply = { _, _ ->
                log += "apply"
                CodecApplyOutcome.Applied("LDAC")
            },
        )

        assertTrue(log.isEmpty())
        assertNull(profiles.profileFor(KEY_HERE))
        assertTrue(state.messageIsError)
    }

    @Test
    fun `the live level is held as read, and an unreadable one as unknown (M12)`() {
        assertNull(LdacQuality.priorLiveOf(null))
        assertNull(LdacQuality.priorLiveOf(LdacQualityMode.UNKNOWN))
        assertEquals(LdacQuality.NONE, LdacQuality.priorLiveOf(LdacQualityMode.NOT_PINNED))
        assertEquals(LdacQuality.HIGH_QUALITY, LdacQuality.priorLiveOf(LdacQualityMode.HIGH_QUALITY))
    }

    // ---- report and banner ----------------------------------------------------

    @Test
    fun `every report line is one of the four kinds, in spec order (AK-T047-4)`() {
        val report = RestoreReport(
            restored = listOf(
                LedgerEntry.Global(SHOW_NAMELESS, prior = null),
                LedgerEntry.Global(ABSOLUTE_VOLUME, prior = "1"),
                LedgerEntry.HdAudio(KEY_AWAY, HdAudioPreference.ENABLE),
                LedgerEntry.Ldac(KEY_HERE, priorWish = LDAC_990, priorLive = LdacQuality.HIGH_QUALITY),
            ),
            pending = listOf(
                LedgerEntry.Ldac("third", priorWish = null, priorLive = null) to "the helper saw $ADDRESS_HERE",
            ),
            autoApplyPausedFor = listOf("Bathys"),
            liveRestored = listOf(KEY_HERE),
        )
        val names = mapOf(KEY_AWAY to "Away", KEY_HERE to "Here", "third" to "Third")

        val lines = report.lines { names.getValue(it) }

        assertEquals(
            listOf(
                "Show devices without names put back — cleared, it was not set before this app touched it.",
                "Absolute volume put back to off.",
                "HD audio for Away put back to on.",
                "Codec preference for Here put back to LDAC, High quality.",
                "LDAC quality for Here put back to High quality.",
                "Codec preference for Third could not be put back yet: the helper saw XX:XX:XX:XX:11:22.",
                "Autoapply paused for Bathys. It will not re-apply its codec, developer options, absolute " +
                    "volume or HD audio settings — and not its volume or EQ choices either — until you turn " +
                    "Autoapply back on.",
                "The codec family for Here goes back to the stack's own choice the next time it connects — " +
                    "that did not happen as part of this action.",
                "The codec family for Third goes back to the stack's own choice the next time it connects — " +
                    "that did not happen as part of this action.",
            ),
            lines,
        )
    }

    @Test
    fun `no codec entry, no codec family line`() {
        val report = RestoreReport(
            restored = listOf(LedgerEntry.HdAudio(KEY_AWAY, HdAudioPreference.SYSTEM_DEFAULT)),
            pending = emptyList(),
            autoApplyPausedFor = emptyList(),
        )

        assertEquals(
            listOf("HD audio for Away put back to back to Android's own choice."),
            report.lines { "Away" },
        )
    }

    @Test
    fun `an unreadable level before gets its own line, not a put-back one`() {
        val report = RestoreReport(
            restored = listOf(LedgerEntry.Ldac(KEY_HERE, priorWish = null, priorLive = null)),
            pending = emptyList(),
            autoApplyPausedFor = emptyList(),
            liveKeptUntilReconnect = listOf(KEY_HERE),
        )

        assertEquals(
            listOf(
                "Codec preference for Here put back — cleared, no preference was set before.",
                "LDAC quality for Here stays as it is until it connects again — the level before this app " +
                    "touched it could not be read.",
                "The codec family for Here goes back to the stack's own choice the next time it connects — " +
                    "that did not happen as part of this action.",
            ),
            report.lines { "Here" },
        )
    }

    @Test
    fun `the banner follows the ledger and offers Try again after an incomplete way back`() {
        val held = listOf(LedgerEntry.Global(AVRCP, "avrcp14"))
        val incomplete = RestoreReport(emptyList(), listOf(held.single() to "no"), emptyList())
        val complete = RestoreReport(held, emptyList(), emptyList())

        assertEquals(RestoreBanner.Hidden, restoreBannerFor(Result.success(emptyList()), incomplete))
        assertEquals(RestoreBanner.Open(1, tryAgain = false), restoreBannerFor(Result.success(held), null))
        assertEquals(RestoreBanner.Open(1, tryAgain = true), restoreBannerFor(Result.success(held), incomplete))
        assertEquals(RestoreBanner.Open(1, tryAgain = false), restoreBannerFor(Result.success(held), complete))
        assertEquals(RestoreBanner.Unreadable, restoreBannerFor(Result.failure(Exception("x")), null))
    }
}
