package dev.dankyeeter.btdashboard.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.persist.AccentChoice
import dev.dankyeeter.btdashboard.system.persist.AppearanceChoice
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {

    private val store = SystemGraph.appearanceStore

    val appearance: StateFlow<AppearanceChoice> = store.choice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppearanceChoice.SYSTEM)

    /** Free ARGB — the single value the theme reads; presets write it too. */
    val accentArgb: StateFlow<Long> = store.accentArgb
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccentChoice.GOLD.argb)

    fun setAccent(choice: AccentChoice) {
        viewModelScope.launch { store.setAccent(choice) }
    }

    fun setAccentArgb(argb: Long) {
        viewModelScope.launch { store.setAccentArgb(argb) }
    }

    fun setAppearance(choice: AppearanceChoice) {
        viewModelScope.launch { store.setChoice(choice) }
    }
}
