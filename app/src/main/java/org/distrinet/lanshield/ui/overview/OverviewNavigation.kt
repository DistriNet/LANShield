package org.distrinet.lanshield.ui.overview

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable

const val OVERVIEW_ROUTE = "overview_route"


fun NavController.navigateToOverview(navOptions: NavOptions?= null) = navigate(OVERVIEW_ROUTE, navOptions)

fun NavGraphBuilder.overviewScreen() {
    composable(
        route = OVERVIEW_ROUTE,
    ) {
        val viewModel = hiltViewModel<OverviewViewModel>()
        OverviewRoute(viewModel)
    }
}
