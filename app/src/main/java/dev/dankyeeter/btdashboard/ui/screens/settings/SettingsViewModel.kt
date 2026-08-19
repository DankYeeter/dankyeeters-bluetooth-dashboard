package dev.dankyeeter.btdashboard.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.persist.AppearanceChoice
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {

    private val store = SystemGraph.appearanceStore

    val appearance: StateFlow<AppearanceChoice> = store.choice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppearanceChoice.SYSTEM)

    fun setAppearance(choice: AppearanceChoice) {
        viewModelScope.launch { store.setChoice(choice) }
    }
}
