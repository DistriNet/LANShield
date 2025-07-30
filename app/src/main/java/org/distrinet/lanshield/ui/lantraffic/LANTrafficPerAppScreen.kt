package org.distrinet.lanshield.ui.lantraffic

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.distrinet.lanshield.PACKAGE_NAME_UNKNOWN
import org.distrinet.lanshield.PackageMetadata
import org.distrinet.lanshield.database.model.LANFlow
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.R
import org.distrinet.lanshield.RESERVED_PACKAGE_NAMES
import org.distrinet.lanshield.getPackageMetadata
import org.distrinet.lanshield.ui.LANShieldIcons
import org.distrinet.lanshield.ui.components.LanShieldInfoDialog
import org.distrinet.lanshield.ui.components.PolicyFilterSegmentedButtonRow
import org.distrinet.lanshield.ui.theme.LANShieldTypography
import java.net.InetSocketAddress


@Composable
internal fun LANTrafficPerAppRoute(
    modifier: Modifier = Modifier,
    viewModel: LANTrafficPerAppViewModel,
    packageName: String,
    navigateBack: () -> Unit
) {

    val lanFlows: List<LANFlow> by viewModel.getLANFlows(packageName)
        .collectAsState(initial = listOf())
    val context = LocalContext.current
    val packageMetadata = remember(packageName, context) { getPackageMetadata(packageName, context.packageManager) }
    val accessPolicy by viewModel.getAccessPolicy(packageName).observeAsState(Policy.DEFAULT)
    val defaultPolicy by viewModel.defaultPolicy.collectAsStateWithLifecycle(initialValue = Policy.ALLOW)
    val scope = rememberCoroutineScope()


    LANTrafficPerAppScreen(
        modifier = modifier,
        lanFlows = lanFlows,
        packageMetadata = packageMetadata,
        clearAll = {
            viewModel.clearLANFlows(packageName)
            navigateBack()
        },
        navigateBack = navigateBack,
        onChangeLanAccessPolicy = { scope.launch { viewModel.onChangeAccessPolicy(packageName, it, packageMetadata.isSystem)} },
        accessPolicy = accessPolicy ?: Policy.DEFAULT,
        defaultPolicy = defaultPolicy
    )
}

@Composable
@Preview
internal fun LANTrafficPerAppScreenPreview() {
    val address = InetSocketAddress("192.168.0.1", 1234)
    val lanFlow = LANFlow.createFlow(
        PACKAGE_NAME_UNKNOWN,
        localEndpoint = address,
        remoteEndpoint = address,
        transportLayerProtocol = "tcp",
        appliedPolicy = Policy.ALLOW
    )

    lanFlow.protocols = listOf("HTTP", "SSDP")
    lanFlow.dpiProtocol = "HTTP"

    LANTrafficPerAppScreen(
        lanFlows = listOf(lanFlow),
        packageMetadata = PackageMetadata("com.google.chrome", "Google Chrome", isSystem = false),
        clearAll = {},
        navigateBack = {},
        defaultPolicy = Policy.ALLOW,
        accessPolicy = Policy.DEFAULT,
        onChangeLanAccessPolicy = {},
        )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerAppTopBar(
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior,
    packageMetadata: PackageMetadata,
    setShowClearAllDialog: (Boolean) -> Unit,
    navigateBack: () -> Unit,
    onClickChangeSetPolicyException: () -> Unit,
    appliedPolicy: Policy,
    onClickShowHelpDialog: () -> Unit,
) {
    var showDropDown by remember { mutableStateOf(false) }
    val changePolicyAllowed = !RESERVED_PACKAGE_NAMES.contains(packageMetadata.packageName)

    CenterAlignedTopAppBar(
        scrollBehavior = scrollBehavior,
        title = { Text(text = packageMetadata.packageLabel) },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = navigateBack, content = { Icon(LANShieldIcons.ArrowBack, stringResource(id = R.string.back)) })
        },
        actions = {
            if(changePolicyAllowed) {
                IconButton(onClick = onClickChangeSetPolicyException) {
                    Icon(LANShieldIcons.Settings, stringResource(id = R.string.change_lan_access_policy))
                }
            }
            IconButton(onClick = {
                showDropDown = true
            }) {
                Icon(LANShieldIcons.MoreVert, null)
            }
            DropdownMenu(
                showDropDown, { showDropDown = false }
            ) {
//                DropdownMenuItem(
//                    text = { Text(text = stringResource(id = R.string.more_info)) },
//                    onClick = onClickShowHelpDialog)
                DropdownMenuItem(
                    text = { Text(text = stringResource(id = R.string.clear_all)) },
                    modifier = Modifier.padding(top = 4.dp),
                    onClick = {
                        showDropDown = false
                        setShowClearAllDialog(true)
                    })
            }
        }
    )
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardLANFlow(modifier: Modifier = Modifier, lanFlow: LANFlow) {

    val context = LocalContext.current
    val bytesIncoming =
        remember(lanFlow.dataEgress) { Formatter.formatShortFileSize(context, lanFlow.dataIngress) }
    val bytesOutgoing =
        remember(lanFlow.dataEgress) { Formatter.formatShortFileSize(context, lanFlow.dataEgress) }



    Surface(modifier = modifier) {
        Column(modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${lanFlow.transportLayerProtocol} ${
                        lanFlow.remoteEndpoint.toString().drop(1)
                    }",
                    fontSize = LANShieldTypography.titleMedium.fontSize,
                    fontStyle = LANShieldTypography.titleMedium.fontStyle,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                if (lanFlow.appliedPolicy == Policy.ALLOW) {
                    Text(
                        text = stringResource(id = R.string.allowed),
                        fontSize = LANShieldTypography.titleMedium.fontSize,
                        fontStyle = LANShieldTypography.titleMedium.fontStyle,
                        color = Color.Green
                    )
                } else if (lanFlow.appliedPolicy == Policy.BLOCK) {
                    Text(
                        text = stringResource(id = R.string.blocked),
                        fontSize = LANShieldTypography.titleMedium.fontSize,
                        fontStyle = LANShieldTypography.titleMedium.fontStyle,
                        color = Color.Red
                    )
                }
                // Policy.DEFAULT is a bug!
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(
                    R.string.start_with_timestamp,
                    formatTimestamp(lanFlow.timeEnd)
                ))
                Text(text = stringResource(
                    R.string.end_with_timestamp,
                    formatTimestamp(lanFlow.timeStart)
                ))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.in_with_amount_bytes, bytesIncoming))
                Text(text = stringResource(R.string.out_with_amount_bytes, bytesOutgoing))
            }
            val protocol = lanFlow.dpiProtocol ?: "Unknown" // nDPI will report 'Unknown' if no protocol is detected, don't change this
            if (!protocol.contentEquals("Unknown")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = stringResource(
                        R.string.detected_protocol,
                        lanFlow.dpiProtocol ?: "",
                    ))
                }
            }
        }
    }
}

@Composable
fun ClearAllDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    packageLabel: String,
) {
    AlertDialog(
        icon = {
            Icon(LANShieldIcons.Warning, contentDescription = null)
        },
        title = { Text(text = stringResource(R.string.clear_lan_traffic)) },
        text = {
            Text(text = stringResource(R.string.check_clear_flows, packageLabel))
        },
        onDismissRequest = onDismiss,
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                }
            ) {
                Text(text = stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                }
            ) {
                Text(text = stringResource(R.string.confirm))
            }
        },
    )
}

@Preview
@Composable
internal fun ChangePolicyBottomSheetPreview() {
    ChangePolicyBottomSheet(
        onDismiss = {},
        onChangeLanAccessPolicy = {},
        accessPolicy = Policy.DEFAULT,
        defaultPolicy = Policy.ALLOW,
        packageLabel = "Google Chrome"
    )
}

@Composable
internal fun RadioButtonWithText(
    isSelected: Boolean,
    onClick: () -> Unit,
    text: String
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp)
    ) {
        RadioButton(
            modifier = Modifier.align(Alignment.CenterVertically),
            selected = isSelected,
            onClick = onClick,
        )
        Text(
            text = text,
            modifier = Modifier
                .padding(start = 16.dp)
                .align(Alignment.CenterVertically),
            fontWeight = if(isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChangePolicyBottomSheet(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onChangeLanAccessPolicy: (Policy) -> Unit,
    accessPolicy: Policy,
    defaultPolicy: Policy,
    packageLabel: String
) {
    val sheetState = rememberModalBottomSheetState()

    val defaultPolicyString = if (defaultPolicy == Policy.ALLOW) stringResource(id = R.string.allow) else stringResource(
        id = R.string.block
    )
    val titleText = buildAnnotatedString {
        append(stringResource(R.string.configure_lan_access_for))
        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
            append(packageLabel)
        }
    }
    ModalBottomSheet(modifier = modifier, onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(text = titleText,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 4.dp, vertical = 8.dp))
        HorizontalDivider(modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp))

        Column(Modifier.selectableGroup()) {
            RadioButtonWithText(isSelected = accessPolicy == Policy.DEFAULT,
                onClick = { onChangeLanAccessPolicy(Policy.DEFAULT) },
                text = "${stringResource(R.string.default_x)} ($defaultPolicyString)"
            )
            RadioButtonWithText(isSelected = accessPolicy == Policy.BLOCK,
                onClick = { onChangeLanAccessPolicy(Policy.BLOCK) },
                text = stringResource(R.string.block)
            )
            RadioButtonWithText(isSelected = accessPolicy == Policy.ALLOW,
                onClick = { onChangeLanAccessPolicy(Policy.ALLOW) },
                text = stringResource(R.string.allow)
            )
            Spacer(modifier = Modifier.padding(10.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LANTrafficPerAppScreen(
    modifier: Modifier = Modifier,
    lanFlows: List<LANFlow>,
    packageMetadata: PackageMetadata,
    clearAll: (() -> Unit),
    navigateBack: () -> Unit,
    accessPolicy: Policy,
    defaultPolicy: Policy,
    onChangeLanAccessPolicy: (Policy) -> Unit
) {

    val (showClearAllDialog, setShowClearAllDialog) = remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    var showChangePolicyBottomSheet by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    var policyFilter by remember { mutableStateOf(Policy.DEFAULT) }
    val lanFlowsFiltered = if (policyFilter == Policy.DEFAULT) lanFlows else lanFlows.filter { it.appliedPolicy == policyFilter }.toList()

    val appliedPolicy = if(accessPolicy != Policy.DEFAULT) accessPolicy else defaultPolicy

    Scaffold(
        modifier = modifier,
        topBar = {
            PerAppTopBar(
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                scrollBehavior = scrollBehavior,
                packageMetadata = packageMetadata,
                setShowClearAllDialog = setShowClearAllDialog,
                navigateBack = navigateBack,
                appliedPolicy = appliedPolicy,
                onClickChangeSetPolicyException = { showChangePolicyBottomSheet = true },
                onClickShowHelpDialog = { showHelpDialog = true }
            )
        }) { innerPadding ->

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(8.dp)
                .fillMaxWidth()
                .fillMaxHeight()
        ) {

            if(showHelpDialog) {
                LanShieldInfoDialog(
                    onDismiss = { showHelpDialog = false },
                    title = { Text(text = stringResource(id = R.string.individual_lan_traffic)) },
                    text = { Text(text = stringResource(id = R.string.individual_lan_traffic_explanation)) }
                )

            }

        if (showClearAllDialog) {
            ClearAllDialog(onDismiss = { setShowClearAllDialog(false) }, onConfirm = {
                setShowClearAllDialog(false)
                clearAll()
            }, packageLabel = packageMetadata.packageLabel)
        }
        if (showChangePolicyBottomSheet) {
            ChangePolicyBottomSheet(
                onDismiss = { showChangePolicyBottomSheet = false },
                onChangeLanAccessPolicy = onChangeLanAccessPolicy,
                accessPolicy = accessPolicy,
                defaultPolicy = defaultPolicy,
                packageLabel = packageMetadata.packageLabel
            )
        }

        Box(  modifier = Modifier
            .fillMaxSize()
            .semantics { isTraversalGroup = true }
        ) {
            PolicyFilterSegmentedButtonRow(modifier = Modifier
                .padding(bottom = 4.dp)
                .align(
                    Alignment.TopCenter
                )
                .zIndex(1f)
                .semantics { traversalIndex = 0f },
                selectedPolicy = policyFilter,
                onSelectedPolicyChanged = { policyFilter = it}
            )
            LazyColumn(
                modifier = Modifier
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .zIndex(0f)
                    .fillMaxSize()
                    .semantics { traversalIndex = 1f },
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(top = 48.dp, bottom = 16.dp),
            ) {

                items(lanFlowsFiltered, key = { "${it.uuid}${it.timeEnd}" }) {
                    CardLANFlow(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .fillMaxWidth()
                            .animateItem(), lanFlow = it
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }

            }
        }
    }
    }
}