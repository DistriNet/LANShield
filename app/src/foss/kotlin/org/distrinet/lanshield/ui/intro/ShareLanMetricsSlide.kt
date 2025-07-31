package org.distrinet.lanshield.ui.intro

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import org.distrinet.lanshield.R
import org.distrinet.lanshield.STUDY_MORE_INFO_URL
import org.distrinet.lanshield.ui.LANShieldIcons
import org.distrinet.lanshield.ui.intro.IntroSlides.INTRO_FINISHED
import org.distrinet.lanshield.ui.intro.IntroSlides.JOIN_USER_STUDY
import org.distrinet.lanshield.ui.intro.IntroSlides.NOTIFICATIONS
import org.distrinet.lanshield.ui.theme.LANShieldTheme

@Composable
fun ShareLanMetricsSlide(
    onChangeShareLanMetrics: (Boolean) -> Unit,
    isShareLanMetricsEnabled: Boolean
) {
    var showMoreInfoDialog by remember { mutableStateOf(false) }

    if (showMoreInfoDialog) {
        AlertDialog(
            icon = {
                Icon(
                    LANShieldIcons.Info,
                    contentDescription = stringResource(id = R.string.info),
                    tint = getIconTint()
                )
            },
            title = { Text(text = stringResource(id = R.string.more_info)) },
            text = {
                Text(text = stringResource(R.string.intro_share_lan_metrics_more_info)) //TODO
            },
            onDismissRequest = { showMoreInfoDialog = false },
            dismissButton = {
                TextButton(
                    onClick = { showMoreInfoDialog = false }
                ) {
                    Text(text = stringResource(id = R.string.privacy_policy))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showMoreInfoDialog = false }
                ) {
                    Text(text = stringResource(id = R.string.dismiss))
                }
            },
        )
    }

    Column() {
        Text(
            text = stringResource(R.string.join_our_academic_study),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(16.dp)
        )
        Spacer(modifier = Modifier.size(40.dp))
        Icon(
            imageVector = LANShieldIcons.Science, contentDescription = null, modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(125.dp),
            tint = getIconTint()
        )
        Spacer(modifier = Modifier.size(40.dp))
        Text(
            text = stringResource(R.string.intro_share_lan_metrics).trimIndent(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
        )

        val uriHandler = LocalUriHandler.current

        Card(
            modifier = Modifier
                .padding(4.dp)
                .align(Alignment.CenterHorizontally),
            onClick = { onChangeShareLanMetrics(!isShareLanMetricsEnabled) },
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Join user study",
                            modifier = Modifier.padding(32.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Start,
                        )
                    }
                    Spacer(modifier = Modifier.padding(8.dp))
                    Switch(
                        checked = isShareLanMetricsEnabled,
                        onCheckedChange = onChangeShareLanMetrics,
                        modifier = Modifier.padding(end = 16.dp),
                        thumbContent = {
//                            if (icon != null) {
//                                Icon(imageVector = icon, contentDescription = null)
//                            }
                        })
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = { uriHandler.openUri(STUDY_MORE_INFO_URL) }, modifier = Modifier.align(
                Alignment.CenterHorizontally
            )
        ) {
            Text(text = stringResource(id = R.string.more_info))
        }

    }
}

@Composable
fun IntroLeftButton(
    modifier: Modifier = Modifier,
    pagerState: PagerState,
    coroutineScope: CoroutineScope,
    onChangeShareLanMetrics: (Boolean) -> Unit,
    isShareLanMetricsEnabled: Boolean
) {

    if (pagerState.currentPage == INTRO_FINISHED.ordinal && !isShareLanMetricsEnabled) {
        IconButton(
            onClick = { scrollToPage(pagerState, JOIN_USER_STUDY.ordinal, coroutineScope) },
            modifier = modifier
        ) {
            Icon(
                imageVector = LANShieldIcons.ChevronLeft,
                contentDescription = stringResource(R.string.previous)
            )
        }
    } else if (pagerState.currentPage != 0) {
        IconButton(
            onClick = { scrollToPreviousPage(pagerState, coroutineScope) },
            modifier = modifier
        ) {
            Icon(
                imageVector = LANShieldIcons.ChevronLeft,
                contentDescription = stringResource(R.string.previous)
            )
        }
    }
}

@Composable
fun IntroRightButton(
    modifier: Modifier = Modifier,
    pagerState: PagerState,
    coroutineScope: CoroutineScope,
    onChangeShareLanMetrics: (Boolean) -> Unit,
    isShareLanMetricsEnabled: Boolean,
    onChangeFinishAppIntro: (Boolean) -> Unit,
    navigateToOverview: () -> Unit,
    requestNotificationPermissionLauncher: ManagedActivityResultLauncher<String, Boolean>
) {

    if (pagerState.currentPage == IntroSlides.JOIN_USER_STUDY.ordinal) {
        IconButton(
            onClick = {
                doShareLanMetricsDecision(
                    isShareLanMetricsEnabled,
                    onChangeShareLanMetrics,
                    coroutineScope,
                    pagerState
                )
            },
            modifier = modifier
        ) {
            Icon(
                imageVector = LANShieldIcons.ChevronRight, contentDescription = stringResource(
                    R.string.next
                )
            )
        }
    } else if (pagerState.currentPage == NOTIFICATIONS.ordinal) {
        IconButton(
            onClick = { requestNotificationPermissionLauncher.launch("android.permission.POST_NOTIFICATIONS") },
            modifier = modifier
        ) {
            Icon(
                imageVector = LANShieldIcons.ChevronRight, contentDescription = stringResource(
                    R.string.next
                )
            )
        }
    } else if (pagerState.currentPage == INTRO_FINISHED.ordinal) {
        TextButton(onClick = {
            onChangeFinishAppIntro(true)
            navigateToOverview()
        }, modifier = modifier) {
            Text(text = stringResource(R.string.finish), style = MaterialTheme.typography.bodyLarge)
        }
    } else if (pagerState.currentPage != INTRO_FINISHED.ordinal) {
        IconButton(
            onClick = { scrollToNextPage(pagerState, coroutineScope) },
            modifier = modifier
        ) {
            Icon(
                imageVector = LANShieldIcons.ChevronRight, contentDescription = stringResource(
                    R.string.next
                )
            )
        }
    }
}

@Preview
@Composable
fun ShareLanMetricsSlideFossPreview() {
    LANShieldTheme(darkTheme = false) {
        IntroScreen(
            initialPage = JOIN_USER_STUDY.ordinal,
            createNotificationChannels = { })
    }
}