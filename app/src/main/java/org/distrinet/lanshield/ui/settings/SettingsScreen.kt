package org.distrinet.lanshield.ui.settings

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.startActivity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.distrinet.lanshield.ABOUT_LANSHIELD_URL
import org.distrinet.lanshield.FEEDBACK_URL
import org.distrinet.lanshield.PRIVACY_POLICY_URL
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.R
import org.distrinet.lanshield.STUDY_MORE_INFO_URL
import org.distrinet.lanshield.isAppUsageAccessGranted
import org.distrinet.lanshield.ui.LANShieldIcons
import org.distrinet.lanshield.ui.components.GrantAppUsagePermissionDialog
import org.distrinet.lanshield.ui.components.LanShieldInfoDialog


val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")


@Composable
internal fun SettingsRoute(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel,
    navigateToPerAppExceptions: () -> Unit,
) {
    val isShareLanMetricsEnabled by viewModel.shareLanMetricsEnabled.collectAsStateWithLifecycle(
        false
    )
    val isShareAppUsageEnabled by viewModel.shareAppUsageEnabled.collectAsStateWithLifecycle(false)
    val isAutoStartEnabled by viewModel.isAutoStartEnabled.collectAsStateWithLifecycle(false)
    val defaultPolicy by viewModel.defaultPolicy.collectAsStateWithLifecycle(initialValue = Policy.DEFAULT)
    val systemAppsPolicy by viewModel.systemAppsPolicy.collectAsStateWithLifecycle(initialValue = Policy.DEFAULT)

    val isAllowMulticastEnabled by viewModel.allowMulticast.collectAsStateWithLifecycle(false)
    val isAllowDnsEnabled by viewModel.allowDns.collectAsStateWithLifecycle(false)
    val isHideMulticastNot by viewModel.hideMulticastNot.collectAsStateWithLifecycle(false)
    val isHideDnsNot by viewModel.hideDnsNot.collectAsStateWithLifecycle(false)


    var showGrantAppUsageDialog by remember { mutableStateOf(false) }


    val context = LocalContext.current

    SettingsScreen(
        modifier = modifier,
        defaultPolicy = defaultPolicy,
        isShareLanMetricsEnabled = isShareLanMetricsEnabled,
        isShareAppUsageEnabled = isShareAppUsageEnabled,
        onChangeShareLanMetrics = { isEnabled -> viewModel.onChangeShareLanMetrics(isEnabled) },
        onChangeShareAppUsage = { isEnabled ->
            onChangeShareAppUsageWithPermission(
                isEnabled,
                { viewModel.onChangeShareAppUsage(it) },
                context,
                { showGrantAppUsageDialog = true })
        },
        onChangeDefaultPolicy = { newPolicy -> viewModel.onChangeDefaultPolicy(newPolicy) },
        navigateToPerAppExceptions = navigateToPerAppExceptions,
        showGrantAppUsageDialog = showGrantAppUsageDialog,
        onDismissGrantAppUsageDialog = { showGrantAppUsageDialog = false },
        onConfirmGrantAppUsageDialog = {
            startUsageAccessSettingsActivity(context)
            showGrantAppUsageDialog = false
        },
        systemAppsPolicy = systemAppsPolicy,
        onChangeSystemAppsPolicy = { viewModel.onChangeSystemAppsPolicy(it) },
        onChangeAutoStart = { viewModel.onChangeAutoStart(it) },
        isAutoStartEnabled = isAutoStartEnabled,
        isAllowMulticastEnabled = isAllowMulticastEnabled,
        onChangeAllowMulticast = { viewModel.onChangeAllowMulticast(it) },
        isAllowDnsEnabled = isAllowDnsEnabled,
        onChangeAllowDns = { viewModel.onChangeAllowDns(it) },
        isHideMulticastNot = isHideMulticastNot,
        onChangeMulticastNot = { viewModel.onChangeHideMulticastNot(it) },
        isHideDnsNot = isHideDnsNot,
        onChangeDnsNot = { viewModel.onChangeHideDnsNot(it) },
    )
}

private fun onChangeShareAppUsageWithPermission(
    isEnabled: Boolean,
    setShareAppUsage: (Boolean) -> Unit,
    context: Context,
    showDialog: () -> Unit
) {
//    var accessGranted = checkSelfPermission(
//        context,
//        android.Manifest.permission.PACKAGE_USAGE_STATS
//    ) == PackageManager.PERMISSION_GRANTED

    val accessGranted = isAppUsageAccessGranted(context)

    if (isEnabled && !accessGranted) {
        showDialog()
    } else setShareAppUsage(isEnabled)
}

private fun startUsageAccessSettingsActivity(context: Context) {
    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopBar(modifier: Modifier = Modifier) {

    CenterAlignedTopAppBar(
        title = { Text(text = stringResource(id = R.string.settings)) },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
        modifier = modifier,
    )
}

@Composable
fun SettingsClickableComp(
    icon: ImageVector,
    @StringRes iconDesc: Int,
    @StringRes name: Int,
    @StringRes description: Int? = null,
    onClick: () -> Unit
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        onClick = onClick,
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(id = name),
                        style = MaterialTheme.typography.bodyMedium.copy(
//                            color = MaterialTheme.colorScheme.surfaceTint
                        ),
                        modifier = Modifier
                            .padding(top = 16.dp, bottom = 16.dp, end = 16.dp, start = 16.dp),
                        textAlign = TextAlign.Start,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (description != null) {
                        Text(
                            text = stringResource(id = description),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(start = 24.dp, bottom = 4.dp),
                            textAlign = TextAlign.Start,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1.0f))
                Icon(
                    imageVector = icon,
                    contentDescription = stringResource(id = iconDesc),
                    tint = MaterialTheme.colorScheme.surfaceTint,
                    modifier = Modifier.padding(end = 8.dp)
//                            .size(24.dp)
                )
            }
        }

    }
}

@Composable
fun SettingsPolicy(
    text: String,
    policy: Policy,
    onChangePolicy: (Policy) -> Unit
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        onClick = {
            val newPolicy = if (policy == Policy.BLOCK) Policy.ALLOW else Policy.BLOCK
            onChangePolicy(newPolicy)
        },
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    textAlign = TextAlign.Start,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.weight(1.0f))
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .scale(0.9F)
                        .align(Alignment.CenterVertically)
                        .padding(end = 0.dp)
                ) {
                    SegmentedButton(
                        selected = policy == Policy.BLOCK,
                        onClick = { onChangePolicy(Policy.BLOCK) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = 0,
                            count = 2
                        )
                    ) {
                        Text(text = stringResource(id = R.string.block))
                    }
                    SegmentedButton(
                        selected = policy == Policy.ALLOW,
                        onClick = { onChangePolicy(Policy.ALLOW) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = 1,
                            count = 2
                        )
                    ) {
                        Text(text = stringResource(id = R.string.allow))
                    }
                }
            }
        }

    }
}

@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    @StringRes name: Int,
    showMoreInfo: Boolean = false,
    onMoreInfoClick: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(id = name))
            if (showMoreInfo) {
                Spacer(modifier = Modifier.weight(1F))
                Icon(
                    imageVector = LANShieldIcons.Help,
                    contentDescription = stringResource(id = R.string.more_info),
                    modifier = Modifier
                        .clickable(onClick = onMoreInfoClick)
                        .padding(end = 4.dp)
                        .scale(1.2F)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Card(
//            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4),
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
fun SettingsSwitchComp(
    modifier: Modifier = Modifier,
    @StringRes name: Int,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
) {
    Surface(
        color = Color.Transparent,
        modifier = modifier
            .fillMaxWidth()
            .padding(4.dp),
        onClick = { onCheckedChange(!isChecked) },
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(id = name),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = isChecked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.padding(end = 8.dp),
                    thumbContent = {
                        if (icon != null) {
                            Icon(imageVector = icon, contentDescription = null)
                        }
                    })
            }
        }
    }
}

@Preview
@Composable
internal fun SettingsScreenPreview() {
    SettingsScreen(
        isShareAppUsageEnabled = true,
        onChangeShareAppUsage = {},
        isShareLanMetricsEnabled = true,
        onChangeShareLanMetrics = {},
        defaultPolicy = Policy.BLOCK,
        onChangeDefaultPolicy = {},
        navigateToPerAppExceptions = {},
        showGrantAppUsageDialog = false,
        onDismissGrantAppUsageDialog = {},
        onConfirmGrantAppUsageDialog = {},
        systemAppsPolicy = Policy.ALLOW,
        onChangeSystemAppsPolicy = {},
        isAutoStartEnabled = true,
        onChangeAutoStart = {},
        isAllowMulticastEnabled = false,
        onChangeAllowMulticast = {},
        isAllowDnsEnabled = false,
        onChangeAllowDns = {},
        isHideDnsNot = false,
        onChangeDnsNot = {},
        isHideMulticastNot = false,
        onChangeMulticastNot = {}
    )
}

@Composable
internal fun SettingsScreen(
    modifier: Modifier = Modifier,
    isShareLanMetricsEnabled: Boolean,
    onChangeShareLanMetrics: (Boolean) -> Unit,
    isShareAppUsageEnabled: Boolean,
    onChangeShareAppUsage: (Boolean) -> Unit,
    defaultPolicy: Policy,
    onChangeDefaultPolicy: (Policy) -> Unit,
    systemAppsPolicy: Policy,
    onChangeSystemAppsPolicy: (Policy) -> Unit,
    navigateToPerAppExceptions: () -> Unit,
    showGrantAppUsageDialog: Boolean,
    onDismissGrantAppUsageDialog: () -> Unit,
    onConfirmGrantAppUsageDialog: () -> Unit,
    isAutoStartEnabled: Boolean,
    onChangeAutoStart: (Boolean) -> Unit,
    isAllowMulticastEnabled: Boolean,
    onChangeAllowMulticast: (Boolean) -> Unit,
    isAllowDnsEnabled: Boolean,
    onChangeAllowDns: (Boolean) -> Unit,
    isHideDnsNot: Boolean,
    onChangeDnsNot: (Boolean) -> Unit,
    isHideMulticastNot: Boolean,
    onChangeMulticastNot: (Boolean) -> Unit,
) {

    var showLanBlockingMoreInfoDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val manageNotificationsIntent = remember(LocalContext.current) {

        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("app_package", context.packageName)
            putExtra("app_uid", context.applicationInfo.uid)
            putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
        }
    }


    Scaffold(topBar = { SettingsTopBar() }, modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showLanBlockingMoreInfoDialog) {
                LanShieldInfoDialog(
                    onDismiss = { showLanBlockingMoreInfoDialog = false },
                    title = { Text(stringResource(id = R.string.lan_traffic_blocking)) },
                    text = { Text(stringResource(id = R.string.lan_traffic_blocking_info)) }
                )
            }
            if (showGrantAppUsageDialog) {
                GrantAppUsagePermissionDialog(
                    onDismiss = onDismissGrantAppUsageDialog,
                    onConfirm = onConfirmGrantAppUsageDialog
                )
            }

            SettingsGroup(
                name = R.string.lan_traffic_blocking,
                showMoreInfo = true,
                onMoreInfoClick = { showLanBlockingMoreInfoDialog = true }) {
                SettingsPolicy(
                    text = stringResource(id = R.string.default_policy),
                    policy = defaultPolicy,
                    onChangePolicy = onChangeDefaultPolicy
                )
                AnimatedVisibility(visible = defaultPolicy == Policy.BLOCK) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsPolicy(
                        text = stringResource(R.string.system_apps),
                        policy = systemAppsPolicy,
                        onChangePolicy = onChangeSystemAppsPolicy
                    )
                }
                AnimatedVisibility(visible = defaultPolicy == Policy.BLOCK) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsSwitchComp(
                        name = R.string.allow_multicast_traffic,
                        isChecked = isAllowMulticastEnabled,
                        onCheckedChange = onChangeAllowMulticast
                    )
                }
                AnimatedVisibility(visible = defaultPolicy == Policy.BLOCK) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsSwitchComp(
                        name = R.string.allow_dns_traffic,
                        isChecked = isAllowDnsEnabled,
                        onCheckedChange = onChangeAllowDns
                    )
                }
                AnimatedVisibility(visible = defaultPolicy == Policy.ALLOW) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsSwitchComp(
                        name = R.string.hide_multicast_notification,
                        isChecked = isHideMulticastNot,
                        onCheckedChange = onChangeMulticastNot
                    )
                }
                AnimatedVisibility(visible = defaultPolicy == Policy.ALLOW) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsSwitchComp(
                        name = R.string.hide_dns_notification,
                        isChecked = isHideDnsNot,
                        onCheckedChange = onChangeDnsNot
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsClickableComp(
                    name = R.string.per_app_exceptions,
                    icon = LANShieldIcons.ChevronRight,
                    iconDesc = R.string.per_app_exceptions,
                    onClick = navigateToPerAppExceptions
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsClickableComp(
                    name = R.string.manage_notifications,
                    icon = LANShieldIcons.ChevronRight,
                    iconDesc = R.string.manage_notifications,
                    onClick = { startActivity(context, manageNotificationsIntent, null) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsSwitchComp(
                    name = R.string.automatically_start_on_boot,
                    isChecked = isAutoStartEnabled,
                    onCheckedChange = onChangeAutoStart,
                )
            }
            SettingsGroup(name = R.string.join_our_academic_study_by_ku_leuven) {
                var icon: ImageVector? = null
                if (isShareLanMetricsEnabled) {
                    icon = if (isShareAppUsageEnabled) {
                        LANShieldIcons.SentimentVerySatisfied
                    } else {
                        LANShieldIcons.Mood
                    }
                }

                SettingsSwitchComp(
                    name = R.string.share_anonymous_lan_metrics,
                    isChecked = isShareLanMetricsEnabled,
                    onCheckedChange = onChangeShareLanMetrics,
                    icon = icon
                )


                AnimatedVisibility(visible = isShareLanMetricsEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsSwitchComp(
                        name = R.string.share_anonymous_app_usage,
                        isChecked = isShareAppUsageEnabled,
                        onCheckedChange = onChangeShareAppUsage
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsClickableComp(
                    name = R.string.more_info,
                    icon = LANShieldIcons.OpenInNewOutlined,
                    iconDesc = R.string.more_info,
                    onClick = { uriHandler.openUri(STUDY_MORE_INFO_URL) }
                )
            }
            SettingsGroup(name = R.string.about) {
                SettingsClickableComp(
                    name = R.string.give_feedback,
                    icon = LANShieldIcons.FeedbackOutlined,
                    iconDesc = R.string.give_feedback,
                    onClick = { uriHandler.openUri(FEEDBACK_URL) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsClickableComp(
                    name = R.string.privacy_policy,
                    icon = LANShieldIcons.OpenInNewOutlined,
                    iconDesc = R.string.app_name,
                    onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsClickableComp(
                    name = R.string.about_lanshield,
                    icon = LANShieldIcons.OpenInNewOutlined,
                    iconDesc = R.string.app_name,
                    onClick = { uriHandler.openUri(ABOUT_LANSHIELD_URL) }
                )
            }
        }
    }
}