@file:OptIn(ExperimentalMaterial3Api::class)

package org.distrinet.lanshield.ui.overview

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.distrinet.lanshield.ABOUT_LANSHIELD_URL
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.R
import org.distrinet.lanshield.VPN_ALWAYS_ON_STATUS
import org.distrinet.lanshield.VPN_SERVICE_STATUS
import org.distrinet.lanshield.ui.LANShieldIcons
import org.distrinet.lanshield.ui.theme.LANShieldTheme

@Composable
internal fun OverviewRoute(
    viewModel: OverviewViewModel,
) {

    val vpnConnectionRequested = viewModel.vpnConnectionRequested.collectAsStateWithLifecycle()
    val vpnServiceStatus by viewModel.vpnServiceStatus.observeAsState()
    val vpnServiceAlwaysOnStatus by viewModel.vpnServiceAlwaysOnStatus.observeAsState(VPN_ALWAYS_ON_STATUS.ENABLED)
    val defaultPolicy by viewModel.defaultPolicy.collectAsStateWithLifecycle(initialValue = Policy.DEFAULT)
    val amountBlockedApps by viewModel.amountBlockedApps.observeAsState(initial = 0)
    val amountAllowedApps by viewModel.amountAllowedApps.observeAsState(initial = 0)


    OverviewScreen(
        vpnServiceAlwaysOnStatus = vpnServiceAlwaysOnStatus,
        isSwitchChecked = vpnServiceStatus == VPN_SERVICE_STATUS.ENABLED,
        onLANShieldSwitchChanged = { isEnabled -> viewModel.onLANShieldSwitchChanged(isEnabled) },
        vpnConnectionRequested = vpnConnectionRequested.value,
        onVPNPermissionRequestDialogDismissed = { viewModel.onVPNPermissionRequestDialogDismissed() },
        onVPNPermissionRequestDialogConfirmed = { viewModel.onVPNPermissionRequestDialogConfirmed() },
        defaultPolicy = defaultPolicy,
        amountAllowedApps = amountAllowedApps,
        amountBlockedApps = amountBlockedApps,
    )
}


@Preview
@Composable
internal fun OverviewViewScreenPreview() {
    val (isSwitchChecked, onLANShieldSwitchChanged) = remember { mutableStateOf(false) }
    val (vpnConnectionRequested, setVPNConnectionRequested) = remember {
        mutableStateOf(
            false
        )
    }


    LANShieldTheme {
        OverviewScreen(
            isSwitchChecked = isSwitchChecked,
            onLANShieldSwitchChanged = onLANShieldSwitchChanged,
            vpnConnectionRequested = vpnConnectionRequested,
            defaultPolicy = Policy.BLOCK,
            amountBlockedApps = 10,
            amountAllowedApps = 20,
            onVPNPermissionRequestDialogDismissed = { setVPNConnectionRequested(true) },
            onVPNPermissionRequestDialogConfirmed = { setVPNConnectionRequested(false) },
            vpnServiceAlwaysOnStatus = VPN_ALWAYS_ON_STATUS.DISABLED
        )
    }
}

@Composable
internal fun OverviewScreen(
    isSwitchChecked: Boolean,
    onLANShieldSwitchChanged: (Boolean) -> Unit,
    vpnConnectionRequested: Boolean,
    onVPNPermissionRequestDialogDismissed: () -> Unit,
    onVPNPermissionRequestDialogConfirmed: () -> Unit,
    vpnServiceAlwaysOnStatus: VPN_ALWAYS_ON_STATUS,
    defaultPolicy: Policy,
    amountAllowedApps: Int,
    amountBlockedApps: Int
) {

    var shouldShowAlwaysOnInfoDialog by remember { mutableStateOf( false ) }

    Scaffold(topBar = { TopBarOverview() }) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding),
        ) {
            val shouldShowVPNPermissionInfoDialog = remember { mutableStateOf(false) }
            val context = LocalContext.current

            if (shouldShowAlwaysOnInfoDialog) {
                InfoDialog(
                    onDismiss = { shouldShowAlwaysOnInfoDialog = false },
                    onConfirm = { shouldShowAlwaysOnInfoDialog = false; openVpnSettings(context) },
                    titleText = stringResource(R.string.enable_always_on),
                    dialogText = stringResource(R.string.overview_always_on_info_dialog),
                    confirmText = stringResource(R.string.next),
                )
            }

            if (shouldShowVPNPermissionInfoDialog.value) {
                InfoDialog(
                    onDismiss = { shouldShowVPNPermissionInfoDialog.value = false },
                    onConfirm = { shouldShowVPNPermissionInfoDialog.value = false },
                    titleText = stringResource(R.string.vpn_notification),
                    dialogText = stringResource(R.string.overview_vpn_notification_info_dialog),
                    confirmText = stringResource(R.string.dismiss),
                )
            }
            if (vpnConnectionRequested) {
                if (!hasVPNPermission()) {
                    InfoDialog(
                        onDismiss = onVPNPermissionRequestDialogDismissed,
                        onConfirm = onVPNPermissionRequestDialogConfirmed,
                        titleText = stringResource(R.string.vpn_permission_request),
                        dialogText = stringResource(R.string.overview_vpn_permission_info_dialog),
                        confirmText = stringResource(R.string.next),
                    )
                } else {
                    onVPNPermissionRequestDialogConfirmed()
                }
            }

            Image(
                painter = painterResource(id = R.mipmap.logo_foreground), // replace 'logo' with the actual name of your logo file
                contentDescription = stringResource(id = R.string.lanshield_logo),
                modifier = Modifier
                    .size(150.dp)
                    .align(Alignment.CenterHorizontally), // adjust the size as needed
                contentScale = ContentScale.Crop
            )
            Text(
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                    .align(Alignment.CenterHorizontally),
                text = stringResource(R.string.lanshield_lets_you_control_lan_access_of_other_apps),
                textAlign = TextAlign.Center
            )
            val uriHandler = LocalUriHandler.current
            Button(
                onClick = { uriHandler.openUri(ABOUT_LANSHIELD_URL) },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 8.dp)
            ) {
                Text(stringResource(R.string.learn_more_on_lanshield_eu), textAlign = TextAlign.Center)
            }
//            OverviewStatus(defaultPolicy = defaultPolicy, amountAllowedApps = amountAllowedApps, amountBlockedApps = amountBlockedApps )
            Spacer(modifier = Modifier.weight(1f))
            AnimatedVisibility(
                isSwitchChecked && vpnServiceAlwaysOnStatus == VPN_ALWAYS_ON_STATUS.DISABLED,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Column {
//                    Button(
//                        onClick = { shouldShowAlwaysOnInfoDialog = true },
//                        modifier = Modifier
//                            .padding(12.dp)
//                            .align(Alignment.CenterHorizontally)
//                    ) {
//                        Text(stringResource(id = R.string.enable_always_on), textAlign = TextAlign.Center)
//                    }
                    Button(
                        onClick = { shouldShowVPNPermissionInfoDialog.value = true },
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                    ) {
                        Text("Why is there a VPN notification?", textAlign = TextAlign.Center)
                    }
                }

            }
            CardEnableLANShield(
                isSwitchChecked = isSwitchChecked,
                onLANShieldSwitchChanged = onLANShieldSwitchChanged,
            )
        }
    }
}

fun openVpnSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_VPN_SETTINGS)
        context.startActivity(intent)
    }
    catch(_: ActivityNotFoundException) {
    }

}
@Composable
internal fun InfoDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    dialogText: String,
    titleText: String,
    confirmText: String
) {
    AlertDialog(
        icon = {
            Icon(LANShieldIcons.Info, contentDescription = stringResource(id = R.string.info))
        },
        title = { Text(text = titleText) },
        text = {
            Text(text = dialogText)
        },
        onDismissRequest = onDismiss,
        dismissButton = {},
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                }
            ) {
                Text(text = confirmText)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TopBarOverview(modifier: Modifier = Modifier) {
    CenterAlignedTopAppBar(
        title = { Text(text = stringResource(id = R.string.app_name)) },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
        modifier = modifier
    )
}


@Composable
fun OverviewStatus(defaultPolicy: Policy, amountBlockedApps: Int, amountAllowedApps: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 32.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 6.dp
        ),
        border = BorderStroke(1.dp, Color.Black),
    ) {
        Column(modifier = Modifier.padding(16.dp))
        {
            if (defaultPolicy == Policy.ALLOW) {
                Text(stringResource(R.string.policy_allow_lan_traffic))
                Text(
                    stringResource(
                        R.string.manually_blocked_app_with_amount,
                        amountBlockedApps,
                        if (amountBlockedApps != 1) "s" else ""
                    ))
            } else {
                Text(stringResource(R.string.policy_block_lan_traffic))
                Text(
                    stringResource(
                        R.string.manually_allowed_app_with_amount,
                        amountAllowedApps,
                        if (amountAllowedApps != 1) "s" else ""
                    ))
            }
        }

    }
}

@Composable
private fun CardEnableLANShield(
    isSwitchChecked: Boolean,
    onLANShieldSwitchChanged: ((Boolean) -> Unit),
) {
    val interactionSource = remember { MutableInteractionSource() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 6.dp
        ),
        border = BorderStroke(1.dp, Color.Black),
    ) {

        Row(
            modifier = Modifier
                .clickable(
                    interactionSource = interactionSource,
                    // This is for removing ripple when Row is clicked
                    indication = null,
                    role = Role.Switch,
                    onClick = {
                        onLANShieldSwitchChanged(!isSwitchChecked)
                    }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {

            Text(stringResource(id = R.string.lanshield_enabled))
            Switch(
                thumbContent = if (isSwitchChecked) {
                    {
                        Icon(
                            imageVector = LANShieldIcons.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
                checked = isSwitchChecked,
                onCheckedChange = onLANShieldSwitchChanged
            )
        }
    }
}

@Composable
fun hasVPNPermission(): Boolean {
    var vpnPermissionChecked by remember { mutableStateOf(false) }

    if (!vpnPermissionChecked) {
        if (VpnService.prepare(LocalContext.current) == null) {
            vpnPermissionChecked = true
            return true
        }
        return false
    }
    return true
}