package dev.dankyeeter.btdashboard.ui.screens.activate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dankyeeter.btdashboard.privileged.adb.HelperAutoStart
import dev.dankyeeter.btdashboard.privileged.adb.PairingCodeNotification
import dev.dankyeeter.btdashboard.system.setup.SetupStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the activate screen is showing right now. */
sealed interface ActivateState {
    data object Idle : ActivateState

    /** The one-time explanation of the local connection. */
    data object Disclosure : ActivateState

    data class Working(val step: String) : ActivateState

    /** adbd wants the six-digit code. [wrongCode] after a rejected attempt. */
    data class NeedsCode(val wrongCode: Boolean = false) : ActivateState

    data class Failed(val reason: String, val fix: ActivateFix = ActivateFix.NONE) : ActivateState

    data object Done : ActivateState
}

/**
 * What the user can do about a failure, as something the screen can act on
 * rather than something it can only say.
 *
 * The reason text used to carry the whole instruction - "turn on Wireless
 * debugging in Developer options" - and left the person to find it. The app
 * knows where that switch lives and can open it; a sentence that describes a
 * destination the app could have opened is a sentence that should have been a
 * button.
 */
enum class ActivateFix {
    /** Nothing useful to offer. The reason has to carry it alone. */
    NONE,

    /** Developer options, with the wireless debugging entry asked for by name. */
    WIRELESS_DEBUGGING,

    /** Wi-Fi settings: wireless debugging cannot come up without a network. */
    WIFI,
}

/**
 * Drives the one screen that brings the helper back.
 *
 * The states exist because each step fails differently and a user can only act
 * on some of them. "The pairing dialog is not open" and "the code was wrong"
 * are both fixable and get their own words; a broken handshake is not, and says
 * so plainly instead of offering false hope.
 */
class ActivateViewModel(application: Application) : AndroidViewModel(application) {

    private val autoStart = HelperAutoStart(application)
    private val setupStore = SetupStore(application)

    private val _state = MutableStateFlow<ActivateState>(ActivateState.Idle)
    val state: StateFlow<ActivateState> = _state.asStateFlow()

    /**
     * The button. Shows the disclosure first, once ever, then tries.
     */
    fun activate() {
        viewModelScope.launch {
            if (!setupStore.isLocalConnectionAccepted()) {
                _state.value = ActivateState.Disclosure
                return@launch
            }
            attempt()
        }
    }

    /**
     * "Not now" on the disclosure, from a surface that cannot be left.
     *
     * The gate closes itself when it is dismissed, but the same dialog also
     * appears inside the setup process, where there is nowhere to go - and a
     * dialog whose only two buttons are "Continue" and nothing at all is a
     * trap. This puts the screen back where it was.
     */
    fun dismissDisclosure() {
        _state.value = ActivateState.Idle
    }

    fun onDisclosureAccepted() {
        viewModelScope.launch {
            setupStore.setLocalConnectionAccepted(true)
            attempt()
        }
    }

    fun submitCode(code: String) {
        viewModelScope.launch {
            _state.value = ActivateState.Working("Pairing…")
            report(autoStart.pairThenStart(code), afterPairing = true)
        }
    }

    private suspend fun attempt() {
        _state.value = ActivateState.Working("Looking for the debugging service…")
        report(autoStart.attemptAndLog(), afterPairing = false)
    }

    private fun report(outcome: HelperAutoStart.Outcome, afterPairing: Boolean) {
        _state.value = when (outcome) {
            is HelperAutoStart.Outcome.Started -> ActivateState.Done

            is HelperAutoStart.Outcome.NeedsPairing -> {
                // The code cannot be typed here, and that is not a preference.
                // Android publishes the pairing service only while its own
                // dialog is in the foreground - measured - so a user who comes
                // back to this screen to type has already killed the thing they
                // were about to pair with. The notification shade is the one
                // place that overlays without displacing.
                PairingCodeNotification.show(getApplication())
                ActivateState.NeedsCode()
            }

            is HelperAutoStart.Outcome.WrongCode -> ActivateState.NeedsCode(wrongCode = true)

            // Before pairing this means wireless debugging is off; during
            // pairing it means the dialog that publishes the pairing service
            // was closed. Same outcome, different thing to do about it.
            HelperAutoStart.Outcome.NoService -> if (afterPairing) {
                ActivateState.Failed(
                    "Android stopped offering pairing. Open \"Pair device with " +
                        "pairing code\" again and leave it open.",
                    ActivateFix.WIRELESS_DEBUGGING,
                )
            } else {
                ActivateState.Failed(
                    "Wireless debugging is off. It also switches itself off while " +
                        "a USB cable is plugged in.",
                    ActivateFix.WIRELESS_DEBUGGING,
                )
            }

            // Named plainly, because it is the one failure with a five-second
            // fix. Anything vaguer sends the user looking for a bug in the app.
            HelperAutoStart.Outcome.NoWifi ->
                ActivateState.Failed("Connect to Wi-Fi first — this needs it.", ActivateFix.WIFI)

            is HelperAutoStart.Outcome.Broken ->
                ActivateState.Failed("Could not reach the debugging service (${outcome.step}).")
        }
    }
}
