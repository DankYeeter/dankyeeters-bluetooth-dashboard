package dev.dankyeeter.btdashboard.ui.tuning

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.provider.Settings
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.optimize.Condition
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionBook
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionValue

/**
 * The environment book the comparison reads at each end of an arm (AD-035):
 * what the app can tell about the surroundings from sources it already has a
 * reason to touch — no new helper command, no Wi-Fi service, no scan switch
 * borrowed (F3). The band is the one network read (AD-046): the active
 * network's frequency and nothing else of it, without location access.
 * Lesbarkeit is decided at runtime, never assumed: a source that does not
 * answer says so as [ConditionValue.Unreadable] and nothing is derived from it.
 *
 * `ble_scan_always_enabled` is deliberately absent here: R-010 C7 says
 * Bluetooth scanning only matters with Bluetooth off, which never holds while
 * this process runs, so it would be a refuted measure with nowhere honest to
 * show (AK-12). P-3 still inventories that key on its own path; this book
 * does not carry it.
 */
fun readConditions(context: Context, snapshot: LinkLiveSnapshot, discoverySeen: Boolean?): ConditionBook {
    val resolver = context.contentResolver
    val radio = readGlobalSetting(resolver, KEY_WIFI_ON)
    return mapOf(
        Condition.WIFI_RADIO to radio,
        Condition.WIFI_BAND to readBand(context, radio),
        Condition.WIFI_SCAN_ALWAYS to readGlobalSetting(resolver, KEY_WIFI_SCAN_ALWAYS),
        Condition.USB_POWER to usbValue(readBatteryPlugged(context)),
        Condition.OTHER_ACL_LINKS to (
            snapshot.pairing?.otherAclLinks
                ?.let { ConditionValue.Read(it.toString()) }
                ?: ConditionValue.Unreadable("pairing facts not read")
            ),
        Condition.DISCOVERY_SEEN to (
            discoverySeen
                ?.let { ConditionValue.Read(if (it) "yes" else "no") }
                ?: ConditionValue.Unreadable("not observed in this reading")
            ),
    )
}

/**
 * One `Settings.Global` boolean key, turned into on/off or the reason it
 * could not be read.
 *
 * `"1"`/`"0"` -> on/off; unset reads as [ConditionValue.Unreadable] with "not
 * set on this phone" rather than an assumed default, because on
 * `bluetooth_disable_absolute_volume` null already means "unset and working
 * fine" for a different key — nothing here may fold "unset" into "off". A
 * thrown read carries the exception's own message.
 */
internal fun settingValue(raw: String?, error: Throwable?): ConditionValue = when {
    error != null -> ConditionValue.Unreadable(error.message ?: "reading the setting threw")
    raw == "1" -> ConditionValue.Read("on")
    raw == "0" -> ConditionValue.Read("off")
    raw == null -> ConditionValue.Unreadable("not set on this phone")
    else -> ConditionValue.Unreadable("unexpected value '$raw'")
}

/**
 * The battery's plug source, folded to the three words AD-035 asks for.
 * Only a USB source counts as the measure's cable; AC, wireless and dock all
 * read as "other" — present, but not the cable A5 is about ("nur Kabel, nie
 * USB 3": this says a source is present, never which signalling runs on it).
 */
internal fun usbValue(plugged: Int?): ConditionValue = when (plugged) {
    null -> ConditionValue.Unreadable("battery state not read")
    BatteryManager.BATTERY_PLUGGED_USB -> ConditionValue.Read("usb")
    0 -> ConditionValue.Read("none")
    else -> ConditionValue.Read("other")
}

/**
 * The band of the active Wi-Fi network (AD-046): "off" with the radio off,
 * otherwise the band [frequencyMhz] falls in (ScanResult's limits, whose
 * checks are hidden API). No frequency means Wi-Fi is not the active network
 * — connected but not active reads as unreadable, never as a band.
 */
internal fun bandValue(radio: ConditionValue, frequencyMhz: Int?, error: Throwable?): ConditionValue = when {
    radio == ConditionValue.Read("off") -> radio
    radio !is ConditionValue.Read -> ConditionValue.Unreadable("Wi-Fi power not read")
    error != null -> ConditionValue.Unreadable(error.message ?: "reading the network threw")
    frequencyMhz == null -> ConditionValue.Unreadable("Wi-Fi is not the active network")
    frequencyMhz in BAND_2_4_GHZ_MHZ -> ConditionValue.Read("2.4 GHz")
    frequencyMhz in BAND_5_GHZ_MHZ -> ConditionValue.Read("5 GHz")
    frequencyMhz == BAND_6_GHZ_EXTRA_MHZ || frequencyMhz in BAND_6_GHZ_MHZ -> ConditionValue.Read("6 GHz")
    else -> ConditionValue.Unreadable("$frequencyMhz MHz in no known band")
}

/**
 * Asks the network only with the radio on. Needs ACCESS_NETWORK_STATE alone;
 * Android blanks the location fields of this answer for an app without
 * location access, and only the frequency is taken from it.
 */
private fun readBand(context: Context, radio: ConditionValue): ConditionValue {
    if (radio != ConditionValue.Read("on")) return bandValue(radio, null, null)
    return runCatching {
        context.getSystemService(ConnectivityManager::class.java)
            ?.let { (it.getNetworkCapabilities(it.activeNetwork)?.transportInfo as? WifiInfo)?.frequency }
    }.fold(
        onSuccess = { mhz -> bandValue(radio, mhz, null) },
        onFailure = { error -> bandValue(radio, null, error) },
    )
}

private fun readGlobalSetting(resolver: ContentResolver, key: String): ConditionValue =
    runCatching { Settings.Global.getString(resolver, key) }
        .fold(
            onSuccess = { raw -> settingValue(raw, null) },
            onFailure = { error -> settingValue(null, error) },
        )

/**
 * The plug type off the battery's sticky intent, or null when Android is not
 * carrying one at all — [usbValue] turns that into [ConditionValue.Unreadable]
 * rather than a guessed "none".
 */
private fun readBatteryPlugged(context: Context): Int? {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return null
    return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, EXTRA_PLUGGED_MISSING)
        .takeIf { it != EXTRA_PLUGGED_MISSING }
}

private const val EXTRA_PLUGGED_MISSING = Int.MIN_VALUE
private val BAND_2_4_GHZ_MHZ = 2412..2484
private val BAND_5_GHZ_MHZ = 5160..5885
private const val BAND_6_GHZ_EXTRA_MHZ = 5935
private val BAND_6_GHZ_MHZ = 5955..7115
private const val KEY_WIFI_ON = "wifi_on"
private const val KEY_WIFI_SCAN_ALWAYS = "wifi_scan_always_enabled"
