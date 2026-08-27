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

    /**
     * [retryable] is false where another attempt cannot possibly go differently
     * — a missing platform feature, not a missing switch. Offering "Try again"
     * there invites the user to press it until they conclude the app is broken,
     * when the honest answer is that this phone cannot do it at all.
     */
    data class Failed(
        val reason: String,
        val fix: ActivateFix = ActivateFix.NONE,
        val retryable: Boolean = true,
    ) : ActivateState

    data object Done : ActivateState
}

/**
 * [ActivateState.Done] with no helper on the other end is not a state, it is a
 * stale claim — so it is turned back into the button that fixes it.
 *
 * This exists because of a black screen seen twice on the device: the whole app
 * replaced by the words "Helper running." and nothing else, no bottom bar and
 * nothing tappable, recoverable only by force-stopping.
 *
 * The route into it is short. `Done` is written once when activation succeeds
 * and was never cleared, while the view model outlives any single screen. The
 * gate re-opens the moment the helper connection goes away — and it can go away
 * under a perfectly healthy app, because a privileged call that throws calls
 * `PrivilegedConnection.forget()` (the `getCodecStatus unavailable:
 * InvocationTargetException` line in the log is exactly such a call). The gate
 * then rendered a view model still saying `Done`, whose only content is that
 * sentence, on a full-screen surface that has no navigation by design.
 *
 * Pure and separate from the view model so the rule can be tested without an
 * Android context, and so the two surfaces that show activation cannot disagree
 * about it.
 */
internal fun reconciled(state: ActivateState, helperConnected: Boolean): ActivateState =
    if (state is ActivateState.Done && !helperConnected) ActivateState.Idle else state

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
 * are both fixable and get their own words; so is most of what can go wrong
 * inside the handshake, which is why [broken] translates each step rather than
 * lumping them together. The one that genuinely cannot be retried says so and
 * offers nothing, instead of a button that would fail identically.
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

    /**
     * Told by the screen what the helper connection is actually doing.
     *
     * The view model cannot answer that itself — the connection lives in
     * `PrivilegedConnection`, which the composables already observe — and it
     * must not be allowed to keep saying "Helper running." after the thing it is
     * describing has gone. See [reconciled] for the screen this prevents.
     */
    fun onHelperConnectionChanged(connected: Boolean) {
        _state.value = reconciled(_state.value, connected)
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

            is HelperAutoStart.Outcome.Broken -> broken(outcome.step)
        }
    }

    /**
     * A failed handshake step, said as something a person can act on.
     *
     * The step names are the app's own internal markers - "tls-exporter",
     * "identity" - and they used to be printed straight into the reason, which
     * told the user nothing and looked like a crash dump. Each one has a real
     * meaning and, for most of them, a real next move; the detail behind them
     * stays in the log, where it is of use to whoever reads logs.
     */
    private fun broken(step: String): ActivateState.Failed = when (step) {
        // The one permanent failure: some OEM builds do not expose the TLS
        // keying material the pairing handshake is defined in terms of. No
        // number of retries changes that, so nothing is offered.
        "tls-exporter" -> ActivateState.Failed(
            "This Android build does not expose what the pairing handshake needs, so " +
                "the app cannot start the helper by itself on this phone.",
            ActivateFix.NONE,
            retryable = false,
        )

        "helper" -> ActivateState.Failed(
            "Pairing worked, but the helper did not come up. Try again — if it keeps " +
                "failing, restart the phone and activate once more.",
        )

        // Someone else's phone answered. Worth naming precisely, because the
        // fix is on a different device and nothing here can reach it.
        "identity" -> ActivateState.Failed(
            "The debugging service that answered is not on this phone, so the app " +
                "refused to connect to it. Turn off wireless debugging on any other " +
                "device on this network.",
        )

        "certificate" -> ActivateState.Failed(
            "The app's own key could not be read. Reinstalling the app rebuilds it.",
        )

        else -> ActivateState.Failed(
            "The handshake with the debugging service failed. Try again.",
        )
    }
}
