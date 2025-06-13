package org.distrinet.lanshield.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import org.distrinet.lanshield.navigation.LANShieldNavHost
import org.distrinet.lanshield.navigation.TopLevelDestination
import org.distrinet.lanshield.ui.components.LANShieldBackground
import org.distrinet.lanshield.ui.components.LANShieldGradientBackground
import org.distrinet.lanshield.ui.components.LANShieldNavigationSuiteScaffold
import org.distrinet.lanshield.ui.intro.INTRO_ROUTE
import org.distrinet.lanshield.ui.overview.OVERVIEW_ROUTE
import org.distrinet.lanshield.ui.theme.LocalGradientColors

@Composable
fun LANShieldApp(
    appState: LANShieldAppState,
    modifier: Modifier = Modifier,
    windowAdaptiveInfo: WindowAdaptiveInfo = currentWindowAdaptiveInfo(),
    introCompleted: Boolean
) {


    val showTopLevelNavigation = appState.currentTopLevelDestination != null

    LANShieldBackground(modifier = modifier) {
        LANShieldGradientBackground(
            gradientColors = LocalGradientColors.current
        ) {
            if(showTopLevelNavigation) {
                LANShieldScaffold(
                    modifier = modifier,
                    appState = appState,
                    windowAdaptiveInfo = windowAdaptiveInfo
                ) {
                    LANShieldScreen(
                        modifier = modifier,
                        appState = appState,
                        introCompleted = introCompleted
                    )
                }
            }
            else {
                LANShieldScreen(
                    modifier = modifier,
                    appState = appState,
                    introCompleted = introCompleted
                )
            }
        }
    }
}

@Composable
private fun LANShieldScaffold(modifier: Modifier, appState: LANShieldAppState, windowAdaptiveInfo: WindowAdaptiveInfo, content: @Composable () -> Unit) {
    val currentDestination = appState.currentDestination

    LANShieldNavigationSuiteScaffold(
        navigationSuiteItems = {
            appState.topLevelDestinations.forEach { destination ->
                val selected = currentDestination
                    .isTopLevelDestinationInHierarchy(destination)
                item(
                    selected = selected,
                    onClick = { appState.navigateToTopLevelDestination(destination) },
                    icon = {
                        Icon(
                            imageVector = destination.unselectedIcon,
                            contentDescription = null,
                        )
                    },
                    selectedIcon = {
                        Icon(
                            imageVector = destination.selectedIcon,
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(destination.iconTextId)) },
                    modifier = modifier
                )
            }
        },
        windowAdaptiveInfo = windowAdaptiveInfo,
    ) {
        content()
    }
}


@Composable
fun LANShieldScreen(modifier: Modifier = Modifier, appState: LANShieldAppState, introCompleted: Boolean) {
    val startDestination = if(introCompleted) OVERVIEW_ROUTE else INTRO_ROUTE

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal,
                    ),
                ),
        ) {
            Box(
                modifier = Modifier.consumeWindowInsets(WindowInsets(0, 0, 0, 0)),
            ) {
                LANShieldNavHost(appState = appState, startDestination = startDestination)
            }
        }
    }
}

private fun NavDestination?.isTopLevelDestinationInHierarchy(destination: TopLevelDestination) =
    this?.hierarchy?.any {
        it.route?.contains(destination.name, true) ?: false
    } ?: false