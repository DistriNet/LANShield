package org.distrinet.lanshield.ui.openports

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.distrinet.lanshield.R
import org.distrinet.lanshield.VPN_SERVICE_STATUS
import org.distrinet.lanshield.database.model.OpenPorts
import org.distrinet.lanshield.ui.components.LANShieldCombinedTopBar
import org.distrinet.lanshield.ui.components.LanShieldInfoDialog
import org.distrinet.lanshield.ui.components.PackageIcon

import org.distrinet.lanshield.ui.theme.LocalTintTheme


@Composable
internal fun OpenPortsRoute(
    modifier: Modifier = Modifier,
    viewModel: OpenPortsViewModel,
) {


    val vpnServiceStatus by viewModel.vpnServiceStatus.observeAsState(initial = VPN_SERVICE_STATUS.DISABLED)
    val appsWithPorts by viewModel.appsWithPorts.collectAsState(initial = emptyList())
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val context = LocalContext.current

    OpenPortsScreen(
        modifier = modifier,
        appsWithPorts = appsWithPorts,
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refreshOpenPorts(context) },
        vpnServiceStatus = vpnServiceStatus
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenPortsScreen(
    modifier: Modifier = Modifier,
    appsWithPorts: List<OpenPorts>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    vpnServiceStatus: VPN_SERVICE_STATUS
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    var searchQuery by remember { mutableStateOf("") }
    var showHelpDialog by remember { mutableStateOf(false) }

    val filteredAppsWithPorts = remember(appsWithPorts, searchQuery) {
        if (searchQuery.isNotEmpty()) {
            appsWithPorts.filter {
                it.packageName.contains(searchQuery, ignoreCase = true) ||
                        it.packageLabel.contains(searchQuery, ignoreCase = true)
            }
        } else appsWithPorts
    }

    val expandedStates = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold(
        modifier = modifier,
        topBar = {
            LANShieldCombinedTopBar(
                scrollBehavior = scrollBehavior,
                titleId = R.string.open_ports,
                searchQuery = searchQuery,
                setSearchQuery = { searchQuery = it },
                onClickShowHelpDialog = { showHelpDialog = true }
            )
        }
    ) { innerPadding ->
        if (showHelpDialog) {
            LanShieldInfoDialog(
                onDismiss = { showHelpDialog = false },
                title = { Text(stringResource(R.string.open_ports)) },
                text = { Text(stringResource(id = R.string.open_ports_info)) }
            )
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .fillMaxSize()
        ) {
            if (vpnServiceStatus != VPN_SERVICE_STATUS.ENABLED) {
                LANShieldNotActive()
            } else if (filteredAppsWithPorts.isEmpty() and searchQuery.isEmpty()) {
                EmptyStateOpenPorts()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filteredAppsWithPorts, key = { it.packageName }) { app ->
                        AppPortsCard(
                            app = app,
                            isExpanded = expandedStates.getOrDefault(app.packageName, false),
                            onToggleExpand = {
                                expandedStates[app.packageName] =
                                    !expandedStates.getOrDefault(app.packageName, false)
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LANShieldNotActive(modifier: Modifier = Modifier) {
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
            text = "LANShield not active",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = buildAnnotatedString {
                append("First, enable LANShield in the ")
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                    append("Overview")
                }
                append(" tab.")
            },
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}


@Composable
private fun EmptyStateOpenPorts(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
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
            text = stringResource(R.string.no_open_ports_detected),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.pull_down_to_refresh),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}


@Composable
fun AppPortsCard(app: OpenPorts, isExpanded: Boolean, onToggleExpand: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .clickable { onToggleExpand() }
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PackageIcon(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .size(40.dp), packageName = app.packageName
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.packageLabel, fontWeight = FontWeight.SemiBold)
                    Text(app.packageName, fontSize = 12.sp, color = Color.Gray)
                }
                Text(
                    text = "${app.tcpPorts.size + app.udpPorts.size} port" + if (app.tcpPorts.size + app.udpPorts.size != 1) "s" else "",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                app.tcpPorts.forEach { port ->
                    PortRow(port, "TCP")
                }
                app.udpPorts.forEach { port ->
                    PortRow(port, "UDP")
                }
            }
        }
    }
}


@Composable
fun PortRow(port: Int, transportProto: String) {
    val applicationProto =
        if (transportProto == "TCP") tcpPortToService[port] else udpPortToService[port]

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$transportProto - $port",
            modifier = Modifier.weight(1f)
        )
        Text(applicationProto.orEmpty(), fontSize = 12.sp, color = Color.Gray)
    }
}

//@Preview(showBackground = true)
//@Composable
//fun OpenPortsScreenPreview() {
//
//
//    val apps = listOf(
////        AppWithPorts(
////            "Facebook", "com.facebook.katana",
////            sortedSetOf(PortInfo(8080, "TCP", "127.0.0.1"), PortInfo(443, "UDP", "0.0.0.0"))
////        ),
////        AppWithPorts(
////            "WhatsApp", "com.whatsapp",
////            sortedSetOf(PortInfo(5222, "TCP", "0.0.0.0"))
////        )
//    )
//    OpenPortsScreen(modifier = Modifier, appsWithPorts = apps, isRefreshing = false, onRefresh = {}, VPN_SERVICE_STATUS.ENABLED)
//}

@Preview(showBackground = true)
@Composable
fun OpenPortsScreenPreviewVPNDisabled() {
    OpenPortsScreen(
        modifier = Modifier,
        appsWithPorts = emptyList(),
        isRefreshing = false,
        onRefresh = {},
        VPN_SERVICE_STATUS.DISABLED
    )
}


