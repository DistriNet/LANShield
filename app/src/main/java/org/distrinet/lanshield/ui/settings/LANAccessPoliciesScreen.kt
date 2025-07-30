package org.distrinet.lanshield.ui.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.NameNotFoundException
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.distrinet.lanshield.PACKAGE_NAME_UNKNOWN
import org.distrinet.lanshield.R
import org.distrinet.lanshield.database.model.LanAccessPolicy
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.applicationInfoIsSystem
import org.distrinet.lanshield.getPackageMetadata
import org.distrinet.lanshield.ui.LANShieldIcons
import org.distrinet.lanshield.ui.components.LanShieldInfoDialog
import org.distrinet.lanshield.ui.components.PackageIcon
import org.distrinet.lanshield.ui.components.PolicyFilterSegmentedButtonRow
import org.distrinet.lanshield.ui.theme.LANShieldTheme
import java.util.SortedMap
import java.util.TreeMap


@Composable
internal fun LANAccessPoliciesRoute(
    modifier: Modifier = Modifier,
    viewModel: LANAccessPoliciesViewModel,
    navigateBack: () -> Unit,
) {

    var showSystem by remember { mutableStateOf(false) }
    var policyFilter by remember { mutableStateOf(Policy.DEFAULT) }
    var searchQuery by remember { mutableStateOf("") }

    val context = LocalContext.current
    val packageManager = context.packageManager

    var installedAppsWithDefaultPolicy: SortedMap<String, LanAccessPolicy> by remember(showSystem) { mutableStateOf(TreeMap()) }
    val nonDefaultLanAccessPolicies: SortedMap<String, LanAccessPolicy> by viewModel.getLanAccessPolicies(true, context).observeAsState(initial = TreeMap())

    val lanAccessPolicies = lookupAndFilterLanAccessPolicies(allInstalledAppsWithDefaultPolicy = installedAppsWithDefaultPolicy,
        nonDefaultLanAccessPolicies = nonDefaultLanAccessPolicies, policyFilter = policyFilter, searchQuery = searchQuery)
    LaunchedEffect(showSystem) {
        withContext(Dispatchers.IO) {
            installedAppsWithDefaultPolicy = packageManager.getInstalledApplications(0).filter {
                (!context.packageName!!.contentEquals(it.packageName)) and (showSystem or !applicationInfoIsSystem(
                    it
                ))
            }.associateBy({ getPackageMetadata(it.packageName, packageManager).packageLabel },
                { packageInfoToLanAccessPolicy(it) }).toSortedMap()
        }
    }

    LANAccessPoliciesScreen(
        modifier = modifier,
        showSystemApps = showSystem,
        setShowSystemApps = { showSystem = it },
        lanAccessPolicies = lanAccessPolicies,
        policyFilter = policyFilter,
        setPolicyFilter = { policyFilter = it },
        navigateBack = navigateBack,
        updateLanAccessPolicy = { viewModel.updateLanAccessPolicy(it) },
        searchQuery = searchQuery,
        setSearchQuery = { searchQuery = it}
    )
}

fun lookupAndFilterLanAccessPolicies(allInstalledAppsWithDefaultPolicy: SortedMap<String, LanAccessPolicy>,
                                     nonDefaultLanAccessPolicies: SortedMap<String, LanAccessPolicy>,
                                     policyFilter: Policy,
                                     searchQuery: String) : SortedMap<String, LanAccessPolicy> {
    var lanAccessPolicies: SortedMap<String, LanAccessPolicy>? = null

    if (policyFilter == Policy.DEFAULT) {
        lanAccessPolicies = allInstalledAppsWithDefaultPolicy.toSortedMap()
        lanAccessPolicies.putAll(nonDefaultLanAccessPolicies)
    } else {
        lanAccessPolicies = nonDefaultLanAccessPolicies.filter { it.value.accessPolicy == policyFilter }.toSortedMap()
    }
    if(searchQuery.isBlank()) {
        return lanAccessPolicies
    }
    return lanAccessPolicies.filter { it.key.contains(searchQuery, ignoreCase = true) or it.value.packageName.contains(searchQuery, ignoreCase = true) }.toSortedMap()
}


@Preview
@Composable
fun LANAccessPoliciesScreenPreview() {
    val policy1 = LanAccessPolicy("Unknown", Policy.ALLOW, false, true)
    val policy2 = LanAccessPolicy("Unknown", Policy.BLOCK, false, true)
    val policy3 = LanAccessPolicy("Unknown", Policy.DEFAULT, false, true)

    val policies = TreeMap<String, LanAccessPolicy>()
    policies["App 1"] = policy1
    policies["App 2"] = policy2
    policies["App 3"] = policy3

    LANShieldTheme(darkTheme = true) {
        LANAccessPoliciesScreen(
            showSystemApps = true,
            setShowSystemApps = {},
            lanAccessPolicies = policies,
            navigateBack = {},
            updateLanAccessPolicy = {},
            policyFilter = Policy.DEFAULT,
            setPolicyFilter = {},
            searchQuery = "",
            setSearchQuery = {})
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanAccessPoliciesSearchBar(
    searchQuery: String,
    setSearchQuery: (String) -> Unit,
    onHideSearchBar: () -> Unit
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
            onSearch = {  keyboardController?.hide() },
            expanded = false,
            onExpandedChange = {},
            leadingIcon = {
                IconButton(onClick = onHideSearchBar) {
                    Icon(imageVector = LANShieldIcons.ArrowBack, contentDescription = null)
                }
            },
            trailingIcon = {
                if(searchQuery.isNotEmpty()) {
                    IconButton(onClick = {setSearchQuery("")}) {
                        Icon(
                            imageVector = LANShieldIcons.Close,
                            contentDescription = stringResource(R.string.cancel_search)
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
internal fun LanAccessPoliciesTopBar(    scrollBehavior: TopAppBarScrollBehavior,     navigateBack: () -> Unit,
setShowSearchBar: (Boolean) -> Unit,     setShowSystemApps: (Boolean) -> Unit,
                                         onShowInfoClicked: () -> Unit,     showSystemApps: Boolean,
                                         ) {

    var showDropDown by remember { mutableStateOf(false) }

    TopAppBar(
        scrollBehavior = scrollBehavior,
        title = { Text(text = stringResource(id = R.string.per_app_exceptions)) },
        colors = TopAppBarDefaults.topAppBarColors(),
        navigationIcon = {
            IconButton(onClick = navigateBack, content = { Icon(LANShieldIcons.ArrowBack,
                stringResource(
                    R.string.back
                )
            ) })
        },
        actions = {
            IconButton(onClick = { setShowSearchBar(true) } ) {
                Icon(imageVector = LANShieldIcons.Search, contentDescription = stringResource(id = R.string.search))
            }
            IconButton(onClick = {
                showDropDown = true
            }) {
                Icon(LANShieldIcons.MoreVert, null)
            }
            DropdownMenu(
                showDropDown, { showDropDown = false }
            ) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(id = R.string.show_system_apps)) },
                    onClick = {
                        showDropDown = false
                        setShowSystemApps(!showSystemApps)
                    },
                    trailingIcon = {
                        Checkbox(
                            checked = showSystemApps,
                            onCheckedChange = setShowSystemApps
                        )
                    })
                DropdownMenuItem(
                    text = { Text(text = stringResource(id = R.string.more_info)) },
                    onClick = {
                        showDropDown = false
                        onShowInfoClicked()
                    })
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CombinedTopBar(
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior,
    navigateBack: () -> Unit,
    showSystemApps: Boolean,
    setShowSystemApps: (Boolean) -> Unit,
    onShowInfoClicked: () -> Unit,
    searchQuery: String,
    setSearchQuery: (String) -> Unit,
) {
    var showSearchBar by remember { mutableStateOf(false) }

    BackHandler(enabled = showSearchBar) {
        setSearchQuery("")
        showSearchBar = false
    }


    Crossfade(targetState = showSearchBar, label = "LanAccessPoliciesTopBar") {
        when (it) {
            true -> LanAccessPoliciesSearchBar(
                searchQuery = searchQuery,
                setSearchQuery = setSearchQuery,
                onHideSearchBar = {
                    setSearchQuery("")
                    showSearchBar = false
                })

            false -> LanAccessPoliciesTopBar(
                scrollBehavior = scrollBehavior,
                navigateBack = navigateBack,
                setShowSearchBar = { show -> showSearchBar = show },
                setShowSystemApps = setShowSystemApps,
                onShowInfoClicked = onShowInfoClicked,
                showSystemApps = showSystemApps
            )

        }
    }

}

@Preview
@Composable
internal fun LanAccessPolicyCardPreview() {
    val lanAccessPolicy =
        LanAccessPolicy(PACKAGE_NAME_UNKNOWN, Policy.DEFAULT, isSystem = false, shouldSync = false)

    LanAccessPolicyCard(
        lanAccessPolicy = lanAccessPolicy,
        modifier = Modifier.fillMaxWidth(),
        packageLabel = "Preview",
        updateLanAccessPolicy = {})
}


@Composable
internal fun LanAccessPolicyCard(
    modifier: Modifier = Modifier,
    lanAccessPolicy: LanAccessPolicy,
    packageLabel: String,
    updateLanAccessPolicy: (LanAccessPolicy) -> Unit
) {
    Row(modifier = modifier) {
        PackageIcon( modifier = Modifier
            .align(Alignment.CenterVertically)
            .size(64.dp), packageName = lanAccessPolicy.packageName)
        Column(
            Modifier
                .padding(start = 16.dp)
                .fillMaxWidth()
        ) {
            Text(text = packageLabel, style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .align(Alignment.Start)
            ) {
                SegmentedButton(
                    selected = lanAccessPolicy.accessPolicy == Policy.DEFAULT,
                    onClick = { updateLanAccessPolicy(lanAccessPolicy.copy(accessPolicy = Policy.DEFAULT, shouldSync = true)) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = 0,
                        count = 3
                    )
                ) {
                    Text(text = stringResource(id = R.string.default_x))
                }
                SegmentedButton(
                    selected = lanAccessPolicy.accessPolicy == Policy.BLOCK,
                    onClick = { updateLanAccessPolicy(lanAccessPolicy.copy(accessPolicy = Policy.BLOCK, shouldSync = true)) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = 1,
                        count = 3
                    )
                ) {
                    Text(text = stringResource(id = R.string.block))
                }
                SegmentedButton(
                    selected = lanAccessPolicy.accessPolicy == Policy.ALLOW,
                    onClick = { updateLanAccessPolicy(lanAccessPolicy.copy(accessPolicy = Policy.ALLOW, shouldSync = true)) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = 2,
                        count = 3
                    )
                ) {
                    Text(text = stringResource(id = R.string.allow))
                }
            }
        }
    }

}

fun packageInfoToLanAccessPolicy(applicationInfo: ApplicationInfo): LanAccessPolicy {
    return LanAccessPolicy(
        applicationInfo.packageName,
        Policy.DEFAULT,
        applicationInfoIsSystem(applicationInfo)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LANAccessPoliciesScreen(
    modifier: Modifier = Modifier,
    navigateBack: () -> Unit,
    lanAccessPolicies: SortedMap<String, LanAccessPolicy>,
    showSystemApps: Boolean,
    setShowSystemApps: (Boolean) -> Unit,
    policyFilter: Policy,
    setPolicyFilter: (Policy) -> Unit,
    updateLanAccessPolicy: (LanAccessPolicy) -> Unit,
    searchQuery: String,
    setSearchQuery: (String) -> Unit
) {

    var showInfoDialog by remember { mutableStateOf(false) }


    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val policiesList = ArrayList(lanAccessPolicies.toList())

    Scaffold(
        modifier = modifier,
        topBar = {
            CombinedTopBar(
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                scrollBehavior = scrollBehavior,
                navigateBack = navigateBack,
                showSystemApps = showSystemApps,
                setShowSystemApps = setShowSystemApps,
                onShowInfoClicked = { showInfoDialog = true },
                searchQuery = searchQuery,
                setSearchQuery = setSearchQuery)
        }) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(8.dp)
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            if (showInfoDialog) {
                LanShieldInfoDialog(
                    onDismiss = { showInfoDialog = false } ,
                    title = { Text(text = stringResource(id = R.string.per_app_exceptions)) },
                    text = {
                        Text(
                            text = stringResource(R.string.per_app_policies_info).trimIndent()
                        )
                    })            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.CenterHorizontally)
                    .semantics { isTraversalGroup = true },
            ) {
                PolicyFilterSegmentedButtonRow(
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .align(Alignment.TopCenter)
                        .zIndex(1f)
                        .semantics { traversalIndex = 0f },
                    selectedPolicy = policyFilter,
                    onSelectedPolicyChanged = setPolicyFilter
                )
                LazyColumn(
                    modifier = Modifier
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .zIndex(0f)
                        .fillMaxSize()
                        .semantics { traversalIndex = 1f },
                    contentPadding = PaddingValues(top = 56.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(policiesList.size, key = { policiesList[it].first }) {
                        LanAccessPolicyCard(
                            lanAccessPolicy = policiesList[it].second,
                            updateLanAccessPolicy = updateLanAccessPolicy,
                            packageLabel = policiesList[it].first,
                            modifier = Modifier
                                .padding(vertical = 12.dp, horizontal = 8.dp)
                                .animateItem()
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }

                }
            }
        }
    }
}