package dev.dankyeeter.btdashboard.system.devices

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Robolectric for the real `org.json`; the DataStore sits on a fresh file per test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsLedgerStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file get() = File(folder.root, "settings_ledger.preferences_pb")

    /** The same factory [dev.dankyeeter.btdashboard.system.SystemGraph] uses (V10). */
    private fun dataStore(): DataStore<Preferences> = SettingsLedgerStore.createDataStore { file }

    private suspend fun DataStore<Preferences>.putRaw(json: String) {
        edit { it[stringPreferencesKey("entries_json")] = json }
    }

    private val key = DeviceKey.fromAddress("AC:DE:48:00:37:8F")!!

    @Test
    fun `all three entry types survive a round trip, nulls included`() = runTest {
        val entries = listOf(
            LedgerEntry.Global("bluetooth_disable_absolute_volume", prior = null),
            LedgerEntry.Global("persist.bluetooth.avrcpversion", prior = "avrcp14"),
            LedgerEntry.HdAudio(key, HdAudioPreference.SYSTEM_DEFAULT),
            LedgerEntry.Ldac(key, priorWish = null, priorLive = null),
            LedgerEntry.Ldac("other", CodecPreference(codec = "LDAC", ldacQuality = 1000L), priorLive = 990L),
        )
        val store = SettingsLedgerStore(dataStore())

        entries.forEach { assertTrue(store.recordIfAbsent(it)) }

        assertEquals(entries, store.entries().getOrThrow())
    }

    @Test
    fun `a second record for the same setting keeps the first`() = runTest {
        val store = SettingsLedgerStore(dataStore())

        store.recordIfAbsent(LedgerEntry.Global("k", prior = null))
        assertTrue("a held baseline still allows the write", store.recordIfAbsent(LedgerEntry.Global("k", "2")))

        assertEquals(listOf(LedgerEntry.Global("k", null)), store.entries().getOrThrow())
    }

    @Test
    fun `remove drops only that setting`() = runTest {
        val store = SettingsLedgerStore(dataStore())
        store.recordIfAbsent(LedgerEntry.Global("k", null))
        store.recordIfAbsent(LedgerEntry.HdAudio(key, HdAudioPreference.ENABLE))

        store.remove(LedgerEntry.Global("k", null))

        assertEquals(listOf(LedgerEntry.HdAudio(key, HdAudioPreference.ENABLE)), store.entries().getOrThrow())
    }

    @Test
    fun `an undecodable ledger refuses to record and reports a failure`() = runTest {
        val dataStore = dataStore()
        dataStore.edit { it[stringPreferencesKey("entries_json")] = "[{\"type\":\"global\"" }
        val store = SettingsLedgerStore(dataStore)

        assertFalse(store.recordIfAbsent(LedgerEntry.Global("k", null)))
        assertTrue("never an empty list in disguise", store.entries().isFailure)
    }

    @Test
    fun `an applier over an undecodable ledger does not write`() = runTest {
        val dataStore = dataStore()
        dataStore.edit { it[stringPreferencesKey("entries_json")] = "not json" }
        val settings = FakeSettings()
        val avrcp = BluetoothDeveloperOptions.avrcpVersion.key
        val profile = DeviceProfile(key, "Bathys", developerOptions = mapOf(avrcp to "avrcp16"))

        val actions = DeviceProfileApplier(
            profiles = object : DeviceProfileSource {
                override suspend fun profileFor(deviceKey: String): DeviceProfile? = null
            },
            volume = object : MediaVolumeController {
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
            secureSettings = settings,
            ledger = SettingsLedgerStore(dataStore),
        ).applyNow(profile)

        assertTrue(settings.writes.isEmpty())
        assertTrue(actions.single() is ProfileAction.Skipped)
    }

    @Test
    fun `any codec wish and System Default survive a round trip (V6)`() = runTest {
        val entries = listOf(
            LedgerEntry.Ldac(key, CodecPreference("AAC", 48_000, 24, 2, 0), priorLive = null),
            LedgerEntry.Ldac("other", CodecPreference(BluetoothCodecOptions.SYSTEM_DEFAULT), priorLive = null),
        )
        val store = SettingsLedgerStore(dataStore())

        entries.forEach { assertTrue(store.recordIfAbsent(it)) }

        assertEquals(entries, store.entries().getOrThrow())
    }

    @Test
    fun `a ledger written in the T-047 format still decodes unchanged (V6)`() = runTest {
        val dataStore = dataStore()
        dataStore.putRaw(
            """[{"type":"global","key":"k"},{"type":"hdAudio","deviceKey":"d","prior":"ENABLE"},""" +
                """{"type":"ldac","deviceKey":"d","priorWish":{"codec":"LDAC","sampleRateHz":0,""" +
                """"bitsPerSample":0,"channelMode":0,"ldacQuality":1000},"priorLive":990}]""",
        )

        assertEquals(
            listOf(
                LedgerEntry.Global("k", prior = null),
                LedgerEntry.HdAudio("d", HdAudioPreference.ENABLE),
                LedgerEntry.Ldac("d", CodecPreference("LDAC", ldacQuality = 1000L), priorLive = 990L),
            ),
            SettingsLedgerStore(dataStore).entries().getOrThrow(),
        )
    }

    @Test
    fun `an undecodable ledger is discarded on request and then works again (AD-042)`() = runTest {
        val dataStore = dataStore()
        dataStore.putRaw("{kaputt")
        val store = SettingsLedgerStore(dataStore)
        assertTrue(store.entries().isFailure)

        assertTrue(store.discardUnreadable())

        assertEquals(Result.success(emptyList<LedgerEntry>()), store.entries())
        assertTrue(store.recordIfAbsent(LedgerEntry.Global("k", null)))
    }

    @Test
    fun `a readable ledger is never discarded (AD-042, V8)`() = runTest {
        val store = SettingsLedgerStore(dataStore())
        store.recordIfAbsent(LedgerEntry.Global("k", null))

        assertFalse(store.discardUnreadable())

        assertEquals(listOf(LedgerEntry.Global("k", null)), store.entries().getOrThrow())
    }

    @Test
    fun `a ledger never written counts as readable (V8)`() = runTest {
        val dataStore = dataStore()
        val store = SettingsLedgerStore(dataStore)

        assertFalse(file.exists())
        assertEquals(Result.success(emptyList<LedgerEntry>()), store.entries())
        assertFalse(store.discardUnreadable())

        assertTrue("the key stays absent", dataStore.data.first().asMap().isEmpty())
    }

    @Test
    fun `a corrupt file reads as unreadable, never as empty, and can then be discarded (AD-042, V9, V10)`() = runTest {
        file.writeBytes(ByteArray(16) { 0xFF.toByte() })
        val store = SettingsLedgerStore(dataStore())

        assertTrue("never an empty list in disguise", store.entries().isFailure)
        assertTrue(store.discardUnreadable())
        assertEquals(Result.success(emptyList<LedgerEntry>()), store.entries())
    }

    @Test
    fun `the corruption marker cannot be read as a JSON array (V9)`() {
        assertFalse(SettingsLedgerStore.CORRUPT_MARKER.trimStart().startsWith("["))
    }

    /** SR-040: an empty file is damage, not a fresh install — only a missing file is. */
    @Test
    fun `a zero-byte file reads as unreadable and can then be discarded (SR-040, V10)`() = runTest {
        file.writeBytes(ByteArray(0))
        val store = SettingsLedgerStore(dataStore())

        assertTrue("never an empty list in disguise", store.entries().isFailure)
        assertTrue(store.discardUnreadable())
        assertEquals(Result.success(emptyList<LedgerEntry>()), store.entries())
    }

    @Test
    fun `an undecodable ledger does not reach the log (SR-033, V12)`() = runTest {
        val dataStore = dataStore()
        dataStore.putRaw("""[{"type":"ldac","deviceKey":"KEY-SENTINEL"""")
        val store = SettingsLedgerStore(dataStore)
        ShadowLog.clear()

        assertFalse(store.recordIfAbsent(LedgerEntry.Global("k", null)))

        val logged = ShadowLog.getLogs().map { "${it.msg} ${it.throwable}" }
        assertTrue(logged.toString(), logged.isNotEmpty() && logged.none { it.contains("KEY-SENTINEL") })
    }

    @Test
    fun `a codec entry from the applier carries no address and no live level (V4)`() = runTest {
        val address = "AC:DE:48:00:37:8F"
        val profile = DeviceProfile(key, "Bathys", codecPreference = CodecPreference("LDAC", ldacQuality = 1000L))
        val store = SettingsLedgerStore(dataStore())

        DeviceProfileApplier(
            profiles = object : DeviceProfileSource {
                override suspend fun profileFor(deviceKey: String): DeviceProfile? = profile
            },
            volume = object : MediaVolumeController {
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
            secureSettings = FakeSettings(),
            codec = object : CodecPreferenceController {
                override fun isAvailable(): Boolean = true
                override suspend fun apply(address: String, preference: CodecPreference) =
                    CodecApplyOutcome.Applied("LDAC")
            },
            ledger = store,
        ).applyNow(profile, address)

        assertEquals(listOf(LedgerEntry.Ldac(key, profile.codecPreference, priorLive = null)), store.entries().getOrThrow())
        val onDisk = file.readText(Charsets.ISO_8859_1)
        assertFalse(onDisk.contains(address, ignoreCase = true))
        assertFalse(onDisk.contains(address.replace(":", ""), ignoreCase = true))
    }

    private class FakeSettings : SecureSettingsController {
        val writes = mutableListOf<String>()
        override fun isWritable(): Boolean = true
        override fun read(key: String): String? = null
        override fun readState(key: String): SettingRead = SettingRead.Unset
        override fun write(key: String, value: String): Boolean {
            writes += key
            return true
        }
        override fun clear(key: String): Boolean = true
    }
}

/**
 * The ledger never leaves the phone (AD-033 M5): restored elsewhere it would
 * put back another device's values. Holds while DataStore keeps its files at
 * `files/datastore/<name>.preferences_pb` — documented, not measured.
 */
class SettingsLedgerBackupExclusionTest {

    private val excluded = "datastore/settings_ledger.preferences_pb"

    private fun excludesIn(file: String, section: String): List<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/$file"))
        val sectionNode = document.getElementsByTagName(section).item(0) as org.w3c.dom.Element
        val excludes = sectionNode.getElementsByTagName("exclude")
        return (0 until excludes.length).map { (excludes.item(it) as org.w3c.dom.Element).getAttribute("path") }
    }

    @Test
    fun `the store name is the one the backup rules exclude`() {
        assertEquals("settings_ledger", SettingsLedgerStore.DATASTORE_NAME)
    }

    @Test
    fun `full backup excludes the ledger`() {
        assertTrue(excluded in excludesIn("backup_rules.xml", "full-backup-content"))
    }

    @Test
    fun `cloud backup excludes the ledger`() {
        assertTrue(excluded in excludesIn("data_extraction_rules.xml", "cloud-backup"))
    }

    @Test
    fun `device transfer excludes the ledger`() {
        assertTrue(excluded in excludesIn("data_extraction_rules.xml", "device-transfer"))
    }
}
