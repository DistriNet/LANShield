@file:OptIn(ExperimentalMaterial3Api::class)

package org.distrinet.lanshield.ui.components

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.distrinet.lanshield.R
import org.distrinet.lanshield.ui.LANShieldIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LANShieldCombinedTopBar(
    searchQuery: String,
    setSearchQuery: (String) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    @StringRes titleId: Int,
    onClickShowHelpDialog: () -> Unit,
    onClickExport: () -> Unit = {},
    isExportEnabled: Boolean = false
) {
    var showSearchBar by remember { mutableStateOf(false) }

    BackHandler(enabled = showSearchBar) {
        setSearchQuery("")
        showSearchBar = false
    }


    Crossfade(targetState = showSearchBar) {
        when (it) {
            true -> LANShieldSearchBar(
                searchQuery = searchQuery,
                setSearchQuery = setSearchQuery,
                onHideSearchBar = {
                    setSearchQuery("")
                    showSearchBar = false
                })

            false -> LANShieldTopBar(
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                titleId = titleId,
                showSearchBar = { showSearchBar = true },
                onClickShowHelpDialog = onClickShowHelpDialog,
                onClickExport = onClickExport,
                scrollBehavior = scrollBehavior,
                isExportEnabled = isExportEnabled,
            )

        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LANShieldTopBar(
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior,
    @StringRes titleId: Int,
    showSearchBar: () -> Unit,
    onClickShowHelpDialog: () -> Unit,
    isExportEnabled: Boolean,
    onClickExport: () -> Unit,
) {

    CenterAlignedTopAppBar(
        modifier = modifier,
        scrollBehavior = scrollBehavior,
        title = { Text(text = stringResource(id = titleId)) },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
        navigationIcon = {
            IconButton(onClick = showSearchBar) {
                Icon(LANShieldIcons.Search, stringResource(id = R.string.search))
            }
        },
        actions = {
            if (isExportEnabled) {
                IconButton(onClick = onClickExport) {
                    Icon(LANShieldIcons.Export, stringResource(id = R.string.export))
                }
            }

            IconButton(onClick = onClickShowHelpDialog) {
                Icon(LANShieldIcons.Help, null)
            }
        }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LANShieldSearchBar(
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