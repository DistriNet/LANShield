package org.distrinet.lanshield.ui.intro

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import org.distrinet.lanshield.ui.lantraffic.LANTrafficPerAppRoute
import org.distrinet.lanshield.ui.lantraffic.LANTrafficPerAppViewModel
import org.distrinet.lanshield.ui.lantraffic.LAN_TRAFFIC_PER_APP_ROUTE
import org.distrinet.lanshield.ui.lantraffic.LAN_TRAFFIC_ROUTE

const val INTRO_ROUTE = "intro_route"

fun NavController.navigateToIntro(navOptions: NavOptions) =
    navigate(INTRO_ROUTE, navOptions)


fun NavGraphBuilder.introScreen(navigateToOverview: () -> Unit) {
    composable(
        route = INTRO_ROUTE,
        ) { backStackEntry ->
        val viewModel = hiltViewModel<IntroViewModel>()
        IntroRoute(
            viewModel = viewModel,
            navigateToOverview = navigateToOverview,
        )
    }
}
