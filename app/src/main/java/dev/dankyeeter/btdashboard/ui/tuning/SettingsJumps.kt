package dev.dankyeeter.btdashboard.ui.tuning

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.dankyeeter.btdashboard.monitor.optimize.Fallback
import dev.dankyeeter.btdashboard.monitor.optimize.Measure
import kotlin.coroutines.cancellation.CancellationException

/**
 * The system page for [measure], or null: none exists, it is a physical step, or it is the
 * sink's (AD-045). A1 has none: its search sources are the pairing page, which must not be
 * opened, and a Play-services switch without a public action.
 */
fun settingsAction(measure: Measure): String? = when (measure) {
    Measure.NO_2_4_GHZ_WIFI -> Settings.ACTION_WIFI_SETTINGS
    Measure.NO_SECOND_DEVICE -> Settings.ACTION_BLUETOOTH_SETTINGS
    Measure.NO_DISCOVERY, Measure.BODY_OUT_OF_PATH, Measure.USB_CABLE_OFF, Measure.SINK_ALLOWS_LDAC -> null
}

/** Always null — a way around is knowledge, not an action (AD-037); spelled out so a new entry forces a decision. */
fun settingsAction(fallback: Fallback): String? = when (fallback) {
    Fallback.LOWER_STEP, Fallback.ADAPTIVE_BITRATE, Fallback.CODEC_CHANGE, Fallback.FAMILY_44_1_KHZ -> null
}

/**
 * Starts [action] bare — no extras, no data, no grants (W3). False when [action] is not one of
 * the two pages [settingsAction] names, or when starting it fails for any reason, so the caller
 * can say so instead of doing nothing.
 */
fun openSettings(context: Context, action: String): Boolean {
    if (action !in JUMP_ACTIONS) return false
    return try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}

private val JUMP_ACTIONS = setOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_BLUETOOTH_SETTINGS)
