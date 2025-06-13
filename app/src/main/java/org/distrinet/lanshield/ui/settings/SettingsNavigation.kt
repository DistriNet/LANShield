package org.distrinet.lanshield.ui.settings

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable

const val SETTINGS_ROUTE = "settings_route"
const val SETTINGS_LAN_POLICIES = "settings_lan_policies"


fun NavController.navigateToSettings(navOptions: NavOptions? = null) =
    navigate(SETTINGS_ROUTE, navOptions)

fun NavController.navigateToLanAccessPolicies(navOptions: NavOptions? = null) =
    navigate(SETTINGS_LAN_POLICIES, navOptions)

fun NavGraphBuilder.settingsScreen(navigateToPerAppExceptions: () -> Unit) {
    composable(
        route = SETTINGS_ROUTE,
    ) {
        val viewModel = hiltViewModel<SettingsViewModel>()
        SettingsRoute(
            viewModel = viewModel,
            navigateToPerAppExceptions = navigateToPerAppExceptions
        )
    }
}

fun NavGraphBuilder.lanAccessPoliciesScreen(navigateBack: () -> Unit) {
    composable(
        route = SETTINGS_LAN_POLICIES,
        ) { _ ->
        val viewModel = hiltViewModel<LANAccessPoliciesViewModel>()
        LANAccessPoliciesRoute(viewModel = viewModel, navigateBack = navigateBack)
    }
}