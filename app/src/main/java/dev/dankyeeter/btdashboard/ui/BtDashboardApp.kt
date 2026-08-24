package dev.dankyeeter.btdashboard.ui

import android.util.Log
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.dankyeeter.btdashboard.system.setup.SetupPhase
import dev.dankyeeter.btdashboard.ui.screens.activate.ActivateRoute
import dev.dankyeeter.btdashboard.ui.screens.bluetooth.BluetoothScreen
import dev.dankyeeter.btdashboard.ui.screens.devices.DeviceProfilesScreen
import dev.dankyeeter.btdashboard.ui.screens.eq.EqScreen
import dev.dankyeeter.btdashboard.ui.screens.hearing.HearingTestScreen
import dev.dankyeeter.btdashboard.ui.screens.monitor.MonitorScreen
import dev.dankyeeter.btdashboard.ui.screens.onboarding.SystemAccessScreen
import dev.dankyeeter.btdashboard.ui.screens.settings.SettingsScreen
import dev.dankyeeter.btdashboard.ui.screens.wizard.SetupWizardScreen
import dev.dankyeeter.btdashboard.ui.screens.wizard.rememberSetupPhase

enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    /** What the headphone is doing right now, plus its own settings. */
    BLUETOOTH("bluetooth", "Bluetooth", Icons.Filled.Bluetooth),
    EQ("eq", "EQ", Icons.Filled.Equalizer),
    /** The hearing test. Named for what it produces, not for what it measures. */
    PROFILING("profiling", "Sound Profiling", Icons.Filled.Hearing),
    MONITORING("monitoring", "Monitoring", Icons.Filled.Insights),
    /** App-level: setup, backup, how-tos, appearance. */
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

const val ROUTE_ONBOARDING = "onboarding"
const val ROUTE_WIZARD = "wizard"
const val ROUTE_DEVICE_PROFILES = "device_profiles"

/** Must match [dev.dankyeeter.btdashboard.system.boot.OpenRoute.ACTIVATE]. */
const val ROUTE_ACTIVATE = "activate"

/** Full-screen flows: the bottom bar would only offer a way to lose your place. */
private val FULL_SCREEN_ROUTES = setOf(ROUTE_ONBOARDING, ROUTE_WIZARD, ROUTE_ACTIVATE)

/**
 * @param requestedRoute a screen asked for from outside the app, e.g. by the
 *   boot notification's "Activate" button. Null in the ordinary case.
 * @param onRouteHandled called once the request has been navigated to, so the
 *   same request is not replayed on every recomposition.
 */
@Composable
fun BtDashboardApp(
    requestedRoute: String? = null,
    onRouteHandled: () -> Unit = {},
) {
    // The gate: three faces, and which one is live is worked out on the spot.
    //
    // 1. Something required is missing - the whole setup process, from the top.
    // 2. Permissions are in place and only the helper is gone - the Activate
    //    button alone. This is the state after a reboot, and it would be an
    //    insult to walk someone through four steps to reach one tap.
    // 3. Everything is in place - none of this is anywhere to be seen, and the
    //    setup lives on as an entry in Settings.
    //
    // Nothing about that is remembered. Android revokes the permissions of
    // unused apps by itself and the user can switch notifications off at any
    // time; a stored "setup done" would keep claiming otherwise, and the
    // permission it would be lying about is the one the pairing code is typed
    // into. It is also how a fresh install used to skip its own setup: the flag
    // had to be guessed while it was being read, and the guess was "done".
    //
    // Why the helper gates the app at all: the codec controls, the Bluetooth
    // settings and - decisively - equalising players that never announce their
    // audio session all go through it. There is no in-between state, no banner
    // and no greyed-out tabs. This also covers the helper dying mid-session.
    val phase = rememberSetupPhase()

    // Once the process is on screen it stays there until the user is done with
    // it, even as its own steps start reporting green.
    //
    // Without this, granting the last required permission would rip the screen
    // away mid-flow - the phase flips to ACTIVATION_ONLY the instant it is
    // granted, and the optional step the user had not reached yet would never
    // be offered. The live state decides whether the process *opens*; the
    // person decides when it closes.
    var inSetup by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(phase) {
        when (phase) {
            SetupPhase.FULL_SETUP -> inSetup = true
            // Everything is in place, so the process closes itself. Daniel's
            // rule for it: the setup steps are to be nowhere in sight once the
            // permissions are granted. Making the last one a tap on "Done"
            // would be one step too many, and it would be the pointless one.
            SetupPhase.READY -> inSetup = false
            SetupPhase.ACTIVATION_ONLY -> Unit
        }
    }

    // The condition decides, not the effect above: a saved `inSetup` restored
    // after a rotation must not be able to flash the setup over a finished app
    // for the frame before the effect runs.
    if (phase == SetupPhase.FULL_SETUP || (inSetup && phase != SetupPhase.READY)) {
        SetupWizardScreen(onDone = { inSetup = false })
        return
    }
    if (phase == SetupPhase.ACTIVATION_ONLY) {
        ActivateRoute(onDone = {})
        return
    }

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // A screen asked for from outside - today the boot notification's
    // "Activate" button. It is a deliberate request, so it wins over whatever
    // the app would otherwise have opened on.
    LaunchedEffect(requestedRoute) {
        val route = requestedRoute ?: return@LaunchedEffect
        runCatching { navController.navigate(route) }
            .onFailure { Log.w("BtDashboardApp", "cannot open requested route $route", it) }
        onRouteHandled()
    }

    Scaffold(
        bottomBar = {
            if (currentRoute !in FULL_SCREEN_ROUTES) {
                NavigationBar {
                    Destination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(Destination.BLUETOOTH.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.BLUETOOTH.route,
            modifier = Modifier.padding(padding),
        ) {
            appGraph(
                onOpenOnboarding = { navController.navigate(ROUTE_ONBOARDING) },
                onBack = {
                    // Popping the wizard on a fresh install would leave an empty
                    // back stack; fall back to the dashboard in that case.
                    if (!navController.popBackStack()) {
                        navController.navigate(Destination.BLUETOOTH.route) {
                            popUpTo(Destination.BLUETOOTH.route) { inclusive = true }
                        }
                    }
                },
                // "Watch live" starts deep capture and jumps to the timeline.
                onWatchLive = { navController.navigate(Destination.MONITORING.route) },
                onOpenWizard = { navController.navigate(ROUTE_WIZARD) },
                onOpenDeviceProfiles = { navController.navigate(ROUTE_DEVICE_PROFILES) },
            )
        }
    }
}

private fun NavGraphBuilder.appGraph(
    onOpenOnboarding: () -> Unit,
    onBack: () -> Unit,
    onWatchLive: () -> Unit = {},
    onOpenWizard: () -> Unit = {},
    onOpenDeviceProfiles: () -> Unit = {},
) {
    composable(Destination.BLUETOOTH.route) {
        BluetoothScreen(
            onWatchLive = onWatchLive,
            onOpenDeviceProfiles = onOpenDeviceProfiles,
        )
    }
    composable(Destination.EQ.route) { EqScreen() }
    composable(Destination.PROFILING.route) { HearingTestScreen() }
    composable(Destination.MONITORING.route) { MonitorScreen() }
    composable(Destination.SETTINGS.route) {
        SettingsScreen(onOpenWizard = onOpenWizard, onOpenOnboarding = onOpenOnboarding)
    }
    composable(ROUTE_ONBOARDING) { SystemAccessScreen(onDone = onBack) }
    composable(ROUTE_WIZARD) { SetupWizardScreen(onDone = onBack) }
    composable(ROUTE_ACTIVATE) { ActivateRoute(onDone = onBack) }
    composable(ROUTE_DEVICE_PROFILES) { DeviceProfilesScreen(onBack = onBack) }
}
