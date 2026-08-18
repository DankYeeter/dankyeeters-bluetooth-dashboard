package dev.dankyeeter.btdashboard.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The calibration preset suggested by hardware detection (currently the AirPods
 * BLE beacon's model id).
 *
 * A *suggestion*, never a command: the EQ screen adopts it only while the user
 * is still on the generic preset. Detection must never silently replace a
 * preset the user picked, or the preset a hearing test was actually run with —
 * mixing a curve measured through one device with another device's correction
 * would make the numbers meaningless.
 */
object DetectedDeviceRepository {

    private val _suggestedPresetId = MutableStateFlow<String?>(null)
    val suggestedPresetId: StateFlow<String?> = _suggestedPresetId.asStateFlow()

    fun suggest(presetId: String) {
        _suggestedPresetId.value = presetId
    }

    fun clear() {
        _suggestedPresetId.value = null
    }
}
