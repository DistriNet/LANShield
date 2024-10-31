package org.distrinet.lanshield.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import org.distrinet.lanshield.R
import org.distrinet.lanshield.ui.LANShieldIcons

enum class TopLevelDestination(
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val iconTextId: Int,
    val titleTextId: Int,
) {
    OVERVIEW(
        selectedIcon = LANShieldIcons.Home,
        unselectedIcon = LANShieldIcons.HomeOutlined,
        iconTextId = R.string.overview,
        titleTextId = R.string.overview,
    ),
    LAN_TRAFFIC(
        selectedIcon = LANShieldIcons.Lan,
        unselectedIcon = LANShieldIcons.LanOutlined,
        iconTextId = R.string.lan_traffic,
        titleTextId = R.string.lan_traffic,
    ),
    SETTINGS(
        selectedIcon = LANShieldIcons.Settings,
        unselectedIcon = LANShieldIcons.SettingsOutlined,
        iconTextId = R.string.settings,
        titleTextId = R.string.settings,
    ),
}
