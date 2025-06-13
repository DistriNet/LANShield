package org.distrinet.lanshield.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import org.distrinet.lanshield.ui.LANShieldAppState
import org.distrinet.lanshield.ui.intro.INTRO_ROUTE
import org.distrinet.lanshield.ui.intro.introScreen
import org.distrinet.lanshield.ui.lantraffic.lanTrafficPerAppScreen
import org.distrinet.lanshield.ui.lantraffic.lanTrafficScreen
import org.distrinet.lanshield.ui.lantraffic.navigateToLANTrafficPerApp
import org.distrinet.lanshield.ui.overview.OVERVIEW_ROUTE
import org.distrinet.lanshield.ui.overview.overviewScreen
import org.distrinet.lanshield.ui.settings.lanAccessPoliciesScreen
import org.distrinet.lanshield.ui.settings.navigateToLanAccessPolicies
import org.distrinet.lanshield.ui.settings.settingsScreen

@Composable
fun LANShieldNavHost(
    appState: LANShieldAppState,
    modifier: Modifier = Modifier,
    startDestination: String = INTRO_ROUTE,
) {
    val navController = appState.navController
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        overviewScreen()
        lanTrafficScreen(onNavigateToLANTrafficPerApp = { packageName ->
            navController.navigateToLANTrafficPerApp(
                packageName
            )
        })
        lanTrafficPerAppScreen(navigateBack = { navController.popBackStack() })
        settingsScreen(navigateToPerAppExceptions = { navController.navigateToLanAccessPolicies() })
        lanAccessPoliciesScreen(navigateBack = { navController.popBackStack() })
        introScreen(navigateToOverview = { navController.navigate(OVERVIEW_ROUTE) {
            popUpTo(INTRO_ROUTE) { inclusive = true }
        } })
    }
}