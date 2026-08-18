package dev.dankyeeter.btdashboard.ui.screens.eq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.audio.eq.Ear
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.system.SystemGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EqViewModel : ViewModel() {

    private val store = SystemGraph.settingsStore
    private val controller = SystemGraph.eqController

    private val _settings = MutableStateFlow(EqSettings.FLAT)
    val settings: StateFlow<EqSettings> = _settings.asStateFlow()

    /** A/B toggle: when true the UI plays flat while keeping the edited curve. */
    private val _bypass = MutableStateFlow(false)
    val bypass: StateFlow<Boolean> = _bypass.asStateFlow()

    val attachmentStatus = controller.status

    init {
        viewModelScope.launch {
            store.settings.collect { _settings.value = it }
        }
    }

    fun setBandGain(ear: Ear, bandIndex: Int, gainDb: Float) {
        val cur = _settings.value
        val updated = when (ear) {
            Ear.LEFT -> cur.copy(leftGainsDb = cur.leftGainsDb.replaceAt(bandIndex, gainDb))
            Ear.RIGHT -> cur.copy(rightGainsDb = cur.rightGainsDb.replaceAt(bandIndex, gainDb))
        }.sanitized()
        _settings.value = updated
        pushLive(updated)
    }

    /** Edits both ears together — the common case before a hearing test exists. */
    fun setLinkedBandGain(bandIndex: Int, gainDb: Float) {
        val cur = _settings.value
        val updated = cur.copy(
            leftGainsDb = cur.leftGainsDb.replaceAt(bandIndex, gainDb),
            rightGainsDb = cur.rightGainsDb.replaceAt(bandIndex, gainDb),
        ).sanitized()
        _settings.value = updated
        pushLive(updated)
    }

    fun setEnabled(enabled: Boolean) = commit(_settings.value.copy(enabled = enabled))

    fun setLimiterEnabled(enabled: Boolean) = commit(_settings.value.copy(limiterEnabled = enabled))

    fun setBypass(bypass: Boolean) {
        _bypass.value = bypass
        pushLive(if (bypass) EqSettings.FLAT.copy(enabled = _settings.value.enabled) else _settings.value)
    }

    fun resetFlat() = commit(EqSettings.FLAT.copy(enabled = _settings.value.enabled))

    /** Persists the current curve; call on slider release. */
    fun persist() {
        viewModelScope.launch { store.save(_settings.value) }
    }

    private fun commit(value: EqSettings) {
        val clean = value.sanitized()
        _settings.value = clean
        pushLive(clean)
        viewModelScope.launch { store.save(clean) }
    }

    private fun pushLive(value: EqSettings) {
        // update() re-uses the existing attachment and falls back to a full
        // attach only when nothing is attached yet — cheap enough for drags.
        if (value.enabled) controller.update(value) else controller.deactivate()
    }
}

private fun List<Float>.replaceAt(index: Int, value: Float): List<Float> =
    toMutableList().also { it[index] = value }
