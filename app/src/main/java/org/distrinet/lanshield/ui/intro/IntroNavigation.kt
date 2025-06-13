package org.distrinet.lanshield.ui.intro

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable

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
