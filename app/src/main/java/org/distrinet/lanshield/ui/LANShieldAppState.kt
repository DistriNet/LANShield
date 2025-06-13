package org.distrinet.lanshield.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.util.trace
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import kotlinx.coroutines.CoroutineScope
import org.distrinet.lanshield.navigation.TopLevelDestination
import org.distrinet.lanshield.navigation.TopLevelDestination.LAN_TRAFFIC
import org.distrinet.lanshield.navigation.TopLevelDestination.OVERVIEW
import org.distrinet.lanshield.navigation.TopLevelDestination.SETTINGS
import org.distrinet.lanshield.ui.lantraffic.LAN_TRAFFIC_ROUTE
import org.distrinet.lanshield.ui.lantraffic.navigateToLANTraffic
import org.distrinet.lanshield.ui.overview.OVERVIEW_ROUTE
import org.distrinet.lanshield.ui.overview.navigateToOverview
import org.distrinet.lanshield.ui.settings.SETTINGS_ROUTE
import org.distrinet.lanshield.ui.settings.navigateToSettings

@Composable
fun rememberLANShieldAppState(
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    navController: NavHostController = rememberNavController(),
): LANShieldAppState {
    return remember(
        navController,
        coroutineScope,
    ) {
        LANShieldAppState(
            navController = navController,
            coroutineScope = coroutineScope,
        )
    }
}


class LANShieldAppState(
    val navController: NavHostController,
    coroutineScope: CoroutineScope,
) {
    val currentDestination: NavDestination?
        @Composable get() = navController
            .currentBackStackEntryAsState().value?.destination

    val currentTopLevelDestination: TopLevelDestination?
        @Composable get() = when (currentDestination?.route) {
            OVERVIEW_ROUTE -> OVERVIEW
            LAN_TRAFFIC_ROUTE -> LAN_TRAFFIC
            SETTINGS_ROUTE -> SETTINGS
            else -> null
        }

    /**
     * Map of top level destinations to be used in the TopBar, BottomBar and NavRail. The key is the
     * route.
     */
    val topLevelDestinations: List<TopLevelDestination> = TopLevelDestination.entries


    /**
     * UI logic for navigating to a top level destination in the app. Top level destinations have
     * only one copy of the destination of the back stack, and save and restore state whenever you
     * navigate to and from it.
     *
     * @param topLevelDestination: The destination the app needs to navigate to.
     */
    fun navigateToTopLevelDestination(topLevelDestination: TopLevelDestination) {
        trace("Navigation: ${topLevelDestination.name}") {
            val topLevelNavOptions = navOptions {
                // Pop up to the start destination of the graph to
                // avoid building up a large stack of destinations
                // on the back stack as users select items
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                // Avoid multiple copies of the same destination when
                // reselecting the same item
                launchSingleTop = true
                // Restore state when reselecting a previously selected item
                restoreState = true
            }

            when (topLevelDestination) {
                OVERVIEW -> navController.navigateToOverview(topLevelNavOptions)
                LAN_TRAFFIC -> navController.navigateToLANTraffic(topLevelNavOptions)
                SETTINGS -> navController.navigateToSettings(topLevelNavOptions)
            }
        }
    }
}