package org.distrinet.lanshield.ui.openports

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable


const val OPEN_PORTS_ROUTE = "open_ports_route"

fun NavController.navigateToOpenPorts(navOptions: NavOptions) =
    navigate(OPEN_PORTS_ROUTE, navOptions)


fun NavGraphBuilder.openPortsScreen() {
    composable(
        route = OPEN_PORTS_ROUTE,
    ) {
        val viewModel = hiltViewModel<OpenPortsViewModel>()
        OpenPortsRoute(
            viewModel = viewModel
        )
    }
}