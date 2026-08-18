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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.dankyeeter.btdashboard.ui.screens.dashboard.DashboardScreen
import dev.dankyeeter.btdashboard.ui.screens.eq.EqScreen
import dev.dankyeeter.btdashboard.ui.screens.hearing.HearingTestScreen
import dev.dankyeeter.btdashboard.ui.screens.monitor.MonitorScreen
import dev.dankyeeter.btdashboard.ui.screens.onboarding.ShizukuOnboardingScreen

enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Dashboard", Icons.Filled.Speaker),
    EQ("eq", "EQ", Icons.Filled.Equalizer),
    HEARING("hearing", "Hearing Test", Icons.Filled.Hearing),
    MONITOR("monitor", "Monitor", Icons.Filled.Insights),
}

const val ROUTE_ONBOARDING = "onboarding"

@Composable
fun BtDashboardApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute != ROUTE_ONBOARDING) {
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
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private fun NavGraphBuilder.appGraph(
    onOpenOnboarding: () -> Unit,
    onBack: () -> Unit,
) {
    composable(Destination.DASHBOARD.route) { DashboardScreen(onOpenOnboarding = onOpenOnboarding) }
    composable(Destination.EQ.route) { EqScreen() }
    composable(Destination.HEARING.route) { HearingTestScreen() }
    composable(Destination.MONITOR.route) { MonitorScreen() }
    composable(ROUTE_ONBOARDING) { ShizukuOnboardingScreen(onDone = onBack) }
}
