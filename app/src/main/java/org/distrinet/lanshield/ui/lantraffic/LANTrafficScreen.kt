package org.distrinet.lanshield.ui.lantraffic

import android.content.Context
import android.text.format.Formatter.formatShortFileSize
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.distrinet.lanshield.PackageMetadata
import org.distrinet.lanshield.R
import org.distrinet.lanshield.database.model.FlowAverage
import org.distrinet.lanshield.database.model.LANFlow
import org.distrinet.lanshield.getPackageMetadata
import org.distrinet.lanshield.ui.LANShieldIcons
import org.distrinet.lanshield.ui.components.ExportFile
import org.distrinet.lanshield.ui.components.LanShieldInfoDialog
import org.distrinet.lanshield.ui.components.PackageIcon
import org.distrinet.lanshield.ui.theme.LANShieldTypography
import org.distrinet.lanshield.ui.theme.LocalTintTheme
import java.text.DateFormat
import java.util.Date


@Composable
internal fun LANTrafficRoute(
    modifier: Modifier = Modifier,
    viewModel: LANTrafficViewModel,
    onNavigateToLANTrafficPerApp: (packageName: String) -> Unit,
) {
    val flowAverages by viewModel.liveFlowAverages.collectAsState(initial = listOf())
    val allFlows by viewModel.allFlows.collectAsState(initial = listOf())

    LANTrafficScreen(
        modifier = modifier,
        flowAverages = flowAverages,
        allFlows = allFlows,
        onNavigateToLANTrafficPerApp = onNavigateToLANTrafficPerApp,
    )

}

@Preview
@Composable
fun LANTrafficScreenPreview() {
    val flowAverage1 = FlowAverage(10, 10, 10, 10, "Unknown", 10)
    val flowAverage2 = FlowAverage(10, 10, 10, 10, "Unknown", 10)
    val flowAverage3 = FlowAverage(10, 10, 10, 10, "Unknown", 10)

    LANTrafficScreen(
        flowAverages = listOf(flowAverage1, flowAverage2, flowAverage3),
        allFlows = listOf(),
        onNavigateToLANTrafficPerApp = {})
}

@Preview
@Composable
fun LANTrafficScreenPreviewEmpty() {
    LANTrafficScreen(
        flowAverages = listOf(),
        allFlows = listOf(),
        onNavigateToLANTrafficPerApp = {})
}

@Preview
@Composable
fun PerAppCardPreview() {
    val packageMetadata = PackageMetadata("com.google.chrome", "Google Chrome", isSystem = false)
    val flowAverage = FlowAverage(10, 10, 10, 10, "Unknown", 10)

    PerAppCard(
        modifier = Modifier.fillMaxWidth(),
        processedFlowAverage = ProcessedFlowAverage(packageMetadata, flowAverage),
        onNavigateToLANTrafficPerApp = {})
}

fun formatTimestamp(timestamp: Long): String {
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
    return dateFormat.format(Date(timestamp))
}

@Composable
private fun PerAppCard(
    modifier: Modifier = Modifier,
    processedFlowAverage: ProcessedFlowAverage,
    onNavigateToLANTrafficPerApp: (packageName: String) -> Unit
) {
    val flowAverage = processedFlowAverage.flowAverage

    val context = LocalContext.current
    val bytes24Hours = remember(flowAverage) {
        formatShortFileSize(
            context,
            flowAverage.totalBytesIngressLast24h + flowAverage.totalBytesEgressLast24h
        )
    }
    val bytes24Total = remember(flowAverage) {
        formatShortFileSize(
            context,
            flowAverage.totalBytesEgress + flowAverage.totalBytesIngress
        )
    }


    val packageName = processedFlowAverage.packageMetadata.packageName
    val packageLabel = processedFlowAverage.packageMetadata.packageLabel

    Card(onClick = { onNavigateToLANTrafficPerApp(flowAverage.appId) }, modifier = modifier) {
        Row(modifier = Modifier.padding(8.dp)) {
            PackageIcon(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .size(72.dp)
                    .padding(8.dp), packageName = packageName
            )


            Column(
                modifier = Modifier.padding(
                    top = 8.dp,
                    bottom = 8.dp,
                    end = 8.dp,
                    start = 16.dp
                )
            ) {
                Text(
                    text = packageLabel,
                    modifier = Modifier.padding(bottom = 4.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = LANShieldTypography.titleMedium.fontSize,
                    fontStyle = LANShieldTypography.titleMedium.fontStyle
                )
                Text(
                    text = stringResource(
                        R.string.last_seen_with_timestamp,
                        formatTimestamp(flowAverage.latestTimeEnd)
                    ),
                    fontSize = LANShieldTypography.bodyMedium.fontSize,
                    fontStyle = LANShieldTypography.bodyMedium.fontStyle
                )
                Row {
                    Text(
                        modifier = Modifier.padding(end = 8.dp),
                        text = stringResource(R.string.total_with_bytes, bytes24Total),
                        fontSize = LANShieldTypography.bodyMedium.fontSize,
                        fontStyle = LANShieldTypography.bodyMedium.fontStyle
                    )
                    Text(
                        text = stringResource(R.string.last_24h_with_bytes, bytes24Hours),
                        fontSize = LANShieldTypography.bodyMedium.fontSize,
                        fontStyle = LANShieldTypography.bodyMedium.fontStyle
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanTrafficTopBar(
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior,
    showSearchBar: () -> Unit,
    isExportEnabled:() -> Boolean,
    onClickShowHelpDialog: () -> Unit,
    onClickExportLANTraffic: () -> Unit,
) {

    CenterAlignedTopAppBar(
        modifier = modifier,
        scrollBehavior = scrollBehavior,
        title = { Text(text = stringResource(id = R.string.lan_traffic)) },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
        navigationIcon = {
            IconButton(onClick = showSearchBar) {
                Icon(LANShieldIcons.Search, stringResource(id = R.string.search))
            }
        },
        actions = {
            IconButton(onClick = onClickExportLANTraffic, enabled = isExportEnabled.invoke()) {
                Icon(LANShieldIcons.Export, stringResource(id = R.string.export))
            }
            IconButton(onClick = onClickShowHelpDialog) {
                Icon(LANShieldIcons.Help, null)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanTrafficSearchBar(
    onHideSearchBar: () -> Unit,
    searchQuery: String,
    setSearchQuery: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current



    SearchBar(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp), expanded = false, onExpandedChange = {}, inputField = {
        SearchBarDefaults.InputField(
            modifier = Modifier
                .focusRequester(focusRequester)
                .onKeyEvent {
                    if (it.key == Key.Enter) {
                        keyboardController?.hide()
                        true
                    } else {
                        false
                    }
                },
            query = searchQuery,
            onQueryChange = { setSearchQuery(it) },
            onSearch = { keyboardController?.hide() },
            expanded = false,
            onExpandedChange = {},
            leadingIcon = {
                IconButton(onClick = onHideSearchBar) {
                    Icon(imageVector = LANShieldIcons.ArrowBack, contentDescription = null)
                }
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { setSearchQuery("") }) {
                        Icon(
                            imageVector = LANShieldIcons.Close,
                            contentDescription = stringResource(id = R.string.cancel_search)
                        )
                    }
                }
            }
        )
    }) {}

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanTrafficCombinedTopBar(
    searchQuery: String,
    setSearchQuery: (String) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    onClickShowHelpDialog: () -> Unit,
    onClickExportLANTraffic: () -> Unit,
    isExportEnabled: () -> Boolean
) {
    var showSearchBar by remember { mutableStateOf(false) }

    BackHandler(enabled = showSearchBar) {
        setSearchQuery("")
        showSearchBar = false
    }


    Crossfade(targetState = showSearchBar, label = "LanTrafficTopBar") {
        when (it) {
            true -> LanTrafficSearchBar(
                searchQuery = searchQuery,
                setSearchQuery = setSearchQuery,
                onHideSearchBar = {
                    setSearchQuery("")
                    showSearchBar = false
                })

            false -> LanTrafficTopBar(
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                showSearchBar = { showSearchBar = true },
                onClickShowHelpDialog = onClickShowHelpDialog,
                onClickExportLANTraffic = onClickExportLANTraffic,
                scrollBehavior = scrollBehavior,
                isExportEnabled = isExportEnabled,
            )

        }
    }
}


@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxSize()
            .testTag("bookmarks:empty"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val iconTint = LocalTintTheme.current.iconTint
        Image(
            modifier = Modifier.fillMaxWidth(),
            painter = painterResource(id = R.mipmap.logo_foreground),
            colorFilter = if (iconTint != Color.Unspecified) ColorFilter.tint(iconTint) else null,
            contentDescription = null,
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = stringResource(R.string.no_lan_traffic_detected),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.any_detected_lan_traffic_will_be_shown_here),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

internal fun processFlowAverages(
    flowAverages: List<FlowAverage>,
    searchQuery: String,
    context: Context
):
        List<ProcessedFlowAverage> {
    if (searchQuery.isNotEmpty()) {
        return flowAverages.map { ProcessedFlowAverage(getPackageMetadata(it.appId, context), it) }
            .filter {
                it.packageMetadata.packageLabel.contains(searchQuery, ignoreCase = true) or
                        it.packageMetadata.packageName.contains(searchQuery, ignoreCase = true)
            }.toList()
    }
    return flowAverages.map { ProcessedFlowAverage(getPackageMetadata(it.appId, context), it) }
        .toList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LANTrafficScreen(
    modifier: Modifier = Modifier,
    flowAverages: List<FlowAverage>,
    allFlows: List<LANFlow>,
    onNavigateToLANTrafficPerApp: (packageName: String) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    var searchQuery by remember { mutableStateOf("") }
    var exportLanTraffic by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val processedFlowAverages = remember(flowAverages, searchQuery) {
        processFlowAverages(flowAverages, searchQuery, context)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            LanTrafficCombinedTopBar(
                scrollBehavior = scrollBehavior,
                searchQuery = searchQuery,
                setSearchQuery = { searchQuery = it },
                onClickShowHelpDialog = { showHelpDialog = true },
                onClickExportLANTraffic = { exportLanTraffic = true },
                isExportEnabled = {allFlows.isNotEmpty()}
            )
        }) { innerPadding ->
        if (showHelpDialog) {
            LanShieldInfoDialog(
                onDismiss = { showHelpDialog = false },
                title = { Text(stringResource(id = R.string.lan_traffic)) },
                text = { Text(stringResource(id = R.string.lan_traffic_info)) })
        }
        if (exportLanTraffic) {
            ExportFile(
                context,
                allFlows
            )
            exportLanTraffic = false
        }

        LazyColumn(
            modifier = Modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(
                processedFlowAverages,
                { "${it.flowAverage.appId}${it.flowAverage.latestTimeEnd}" }) {
                PerAppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 8.dp)
                        .animateItem(),
                    processedFlowAverage = it,
                    onNavigateToLANTrafficPerApp = onNavigateToLANTrafficPerApp
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        if (flowAverages.isEmpty()) {
            EmptyState()
        }
    }
}


internal data class ProcessedFlowAverage(
    val packageMetadata: PackageMetadata,
    val flowAverage: FlowAverage,
)