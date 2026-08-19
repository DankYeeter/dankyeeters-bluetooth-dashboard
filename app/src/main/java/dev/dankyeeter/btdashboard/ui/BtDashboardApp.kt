package dev.dankyeeter.btdashboard.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.ui.screens.dashboard.DashboardScreen
import dev.dankyeeter.btdashboard.ui.screens.devices.DeviceProfilesScreen
import dev.dankyeeter.btdashboard.ui.screens.eq.EqScreen
import dev.dankyeeter.btdashboard.ui.screens.hearing.HearingTestScreen
import dev.dankyeeter.btdashboard.ui.screens.monitor.MonitorScreen
import dev.dankyeeter.btdashboard.ui.screens.onboarding.ShizukuOnboardingScreen
import dev.dankyeeter.btdashboard.ui.screens.settings.SettingsScreen
import dev.dankyeeter.btdashboard.ui.screens.wizard.SetupWizardScreen

enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Dashboard", Icons.Filled.Speaker),
    EQ("eq", "EQ", Icons.Filled.Equalizer),
    HEARING("hearing", "Hearing Test", Icons.Filled.Hearing),
    MONITOR("monitor", "Monitor", Icons.Filled.Insights),
}

const val ROUTE_ONBOARDING = "onboarding"
const val ROUTE_WIZARD = "wizard"
const val ROUTE_DEVICE_PROFILES = "device_profiles"
const val ROUTE_SETTINGS = "settings"

/** Full-screen flows: the bottom bar would only offer a way to lose your place. */
private val FULL_SCREEN_ROUTES = setOf(ROUTE_ONBOARDING, ROUTE_WIZARD)

@Composable
fun BtDashboardApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // First launch opens the wizard exactly once. "Completed" only records that
    // it was seen — the live status is always recomputed, never trusted from here.
    var firstRunChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (firstRunChecked) return@LaunchedEffect
        firstRunChecked = true
        val completed = runCatching { SystemGraph.setupStore.isWizardCompleted() }.getOrDefault(true)
        if (!completed) navController.navigate(ROUTE_WIZARD)
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
                                    popUpTo(Destination.DASHBOARD.route) { saveState = true }
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
            startDestination = Destination.DASHBOARD.route,
            modifier = Modifier.padding(padding),
        ) {
            appGraph(
                onOpenOnboarding = { navController.navigate(ROUTE_ONBOARDING) },
                onBack = {
                    // Popping the wizard on a fresh install would leave an empty
                    // back stack; fall back to the dashboard in that case.
                    if (!navController.popBackStack()) {
                        navController.navigate(Destination.DASHBOARD.route) {
                            popUpTo(Destination.DASHBOARD.route) { inclusive = true }
                        }
                    }
                },
                // "Watch live" starts deep capture and jumps to the timeline.
                onWatchLive = { navController.navigate(Destination.MONITOR.route) },
                onOpenWizard = { navController.navigate(ROUTE_WIZARD) },
                onOpenDeviceProfiles = { navController.navigate(ROUTE_DEVICE_PROFILES) },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
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
    onOpenSettings: () -> Unit = {},
) {
    composable(Destination.DASHBOARD.route) {
        DashboardScreen(
            onOpenOnboarding = onOpenOnboarding,
            onWatchLive = onWatchLive,
            onOpenWizard = onOpenWizard,
            onOpenDeviceProfiles = onOpenDeviceProfiles,
            onOpenSettings = onOpenSettings,
        )
    }
    composable(Destination.EQ.route) { EqScreen() }
    composable(Destination.HEARING.route) { HearingTestScreen() }
    composable(Destination.MONITOR.route) { MonitorScreen() }
    composable(ROUTE_ONBOARDING) { ShizukuOnboardingScreen(onDone = onBack) }
    composable(ROUTE_WIZARD) { SetupWizardScreen(onDone = onBack) }
    composable(ROUTE_DEVICE_PROFILES) { DeviceProfilesScreen(onBack = onBack) }
    composable(ROUTE_SETTINGS) { SettingsScreen(onBack = onBack) }
}
