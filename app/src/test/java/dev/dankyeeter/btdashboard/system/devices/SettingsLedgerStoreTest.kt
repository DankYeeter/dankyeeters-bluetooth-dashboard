package dev.dankyeeter.btdashboard.system.devices

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Robolectric for the real `org.json`; the DataStore sits on a fresh file per test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsLedgerStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { File(folder.root, "settings_ledger.preferences_pb") }

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
                override fun currentPercent(): Int? = null
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
