package org.distrinet.lanshield.ui.lantraffic

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink

const val LAN_TRAFFIC_ROUTE = "lan_traffic_route"
const val LAN_TRAFFIC_PER_APP_ROUTE_PREFIX = "lan_traffic_per_app_route"
const val LAN_TRAFFIC_PER_APP_ROUTE = "lan_traffic_per_app_route/{packageName}"

fun NavController.navigateToLANTraffic(navOptions: NavOptions) =
    navigate(LAN_TRAFFIC_ROUTE, navOptions)

fun NavController.navigateToLANTrafficPerApp(packageName: String, navOptions: NavOptions? = null) {
    this.navigate("lan_traffic_per_app_route/$packageName", navOptions)
}

fun getLanTrafficPerAppRoute(packageName: String) : String {
    return "$LAN_TRAFFIC_PER_APP_ROUTE_PREFIX/$packageName"
}


fun NavGraphBuilder.lanTrafficScreen(onNavigateToLANTrafficPerApp: (String) -> Unit) {
    composable(
        route = LAN_TRAFFIC_ROUTE,
    ) {
        val viewModel = hiltViewModel<LANTrafficViewModel>()
        LANTrafficRoute(
            viewModel = viewModel,
            onNavigateToLANTrafficPerApp = onNavigateToLANTrafficPerApp
        )
    }
}

fun NavGraphBuilder.lanTrafficPerAppScreen(navigateBack: () -> Unit) {
    composable(
        route = LAN_TRAFFIC_PER_APP_ROUTE,
        arguments = listOf(navArgument("packageName") { type = NavType.StringType }),
        deepLinks = listOf(navDeepLink { uriPattern = "org.distrinet.lanshield://${LAN_TRAFFIC_PER_APP_ROUTE}" }),

        ) { backStackEntry ->
        val packageName = backStackEntry.arguments?.getString("packageName")!!
        val viewModel = hiltViewModel<LANTrafficPerAppViewModel>()
        LANTrafficPerAppRoute(
            viewModel = viewModel,
            packageName = packageName,
            navigateBack = navigateBack
        )
    }
}