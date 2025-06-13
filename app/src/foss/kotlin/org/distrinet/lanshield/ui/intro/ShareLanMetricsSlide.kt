package org.distrinet.lanshield.ui.intro

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import org.distrinet.lanshield.R
import org.distrinet.lanshield.ui.LANShieldIcons
import org.distrinet.lanshield.ui.intro.IntroSlides.INTRO_FINISHED
import org.distrinet.lanshield.ui.intro.IntroSlides.NOTIFICATIONS

@Composable
fun IntroLeftButton(
    modifier: Modifier = Modifier,
    pagerState: PagerState,
    coroutineScope: CoroutineScope,
    onChangeShareLanMetrics: (Boolean) -> Unit,
    isShareLanMetricsEnabled: Boolean) {

    if (pagerState.currentPage == INTRO_FINISHED.ordinal && !isShareLanMetricsEnabled) {
        IconButton(
            onClick = { scrollToPage(pagerState, NOTIFICATIONS.ordinal, coroutineScope) },
            modifier = modifier) {
            Icon(imageVector = LANShieldIcons.ChevronLeft, contentDescription = stringResource(R.string.previous))
        }
    }
    else if (pagerState.currentPage != 0) {
        IconButton(
            onClick = { scrollToPreviousPage(pagerState, coroutineScope) },
            modifier = modifier) {
            Icon(imageVector = LANShieldIcons.ChevronLeft, contentDescription = stringResource(R.string.previous))
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

    if (pagerState.currentPage == NOTIFICATIONS.ordinal) {
        IconButton(
            onClick = { requestNotificationPermissionLauncher.launch("android.permission.POST_NOTIFICATIONS") },
            modifier = modifier) {
            Icon(imageVector = LANShieldIcons.ChevronRight, contentDescription = stringResource(
                R.string.next
            )
            )
        }
    }
    else if (pagerState.currentPage == INTRO_FINISHED.ordinal) {
        TextButton(onClick = {
            onChangeFinishAppIntro(true)
            navigateToOverview()
        }, modifier = modifier) {
            Text(text = stringResource(R.string.finish), style = MaterialTheme.typography.bodyLarge)
        }
    }
    else if (pagerState.currentPage != INTRO_FINISHED.ordinal) {
        IconButton(
            onClick = { scrollToNextPage(pagerState, coroutineScope) },
            modifier = modifier
        ) {
            Icon(imageVector = LANShieldIcons.ChevronRight, contentDescription = stringResource(
                R.string.next
            ))
        }
    }
}