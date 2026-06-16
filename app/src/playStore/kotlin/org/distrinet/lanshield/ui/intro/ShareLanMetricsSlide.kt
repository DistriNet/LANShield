package org.distrinet.lanshield.ui.intro

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import org.distrinet.lanshield.R
import org.distrinet.lanshield.PRIVACY_POLICY_URL
import org.distrinet.lanshield.ui.LANShieldIcons

@Composable
fun ShareLanMetricsSlide(
    page: Int,
    pagerState: PagerState,
    onChangeShareLanMetrics: (Boolean) -> Unit,
    isShareLanMetricsEnabled: Boolean
) {
    val uriHandler = LocalUriHandler.current
    OnboardingSlide(
        page = page,
        pagerState = pagerState,
        title = stringResource(R.string.join_our_academic_study),
        icon = LANShieldIcons.Science,
        body = stringResource(R.string.intro_share_lan_metrics).trimIndent(),
    ) {
        OnboardingTextButton(
            text = stringResource(id = R.string.more_info),
            onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
        )
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
    when {
        pagerState.currentPage == IntroSlides.JOIN_USER_STUDY.ordinal -> {
            OnboardingTextButton(
                text = stringResource(R.string.disagree),
                onClick = {
                    doShareLanMetricsDecision(false, onChangeShareLanMetrics, coroutineScope, pagerState)
                },
                modifier = modifier,
            )
        }

        pagerState.currentPage == IntroSlides.INTRO_FINISHED.ordinal && !isShareLanMetricsEnabled -> {
            OnboardingTextButton(
                text = stringResource(R.string.back),
                onClick = {
                    scrollToPage(pagerState, IntroSlides.JOIN_USER_STUDY.ordinal, coroutineScope)
                },
                modifier = modifier,
            )
        }

        pagerState.currentPage != 0 -> {
            OnboardingTextButton(
                text = stringResource(R.string.back),
                onClick = { scrollToPreviousPage(pagerState, coroutineScope) },
                modifier = modifier,
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
    requestNotificationPermissionLauncher: ManagedActivityResultLauncher<String, Boolean>,
    notificationsEnabled: Boolean,
) {
    when (pagerState.currentPage) {
        IntroSlides.JOIN_USER_STUDY.ordinal -> {
            OnboardingPrimaryButton(
                text = stringResource(R.string.agree),
                onClick = {
                    doShareLanMetricsDecision(true, onChangeShareLanMetrics, coroutineScope, pagerState)
                },
                modifier = modifier,
            )
        }

        IntroSlides.NOTIFICATIONS.ordinal -> {
            OnboardingPrimaryButton(
                text = stringResource(R.string.continue_label),
                onClick = { scrollToNextPage(pagerState, coroutineScope) },
                enabled = notificationsEnabled,
                modifier = modifier,
            )
        }

        IntroSlides.INTRO_FINISHED.ordinal -> {
            OnboardingPrimaryButton(
                text = stringResource(R.string.finish),
                onClick = {
                    onChangeFinishAppIntro(true)
                    navigateToOverview()
                },
                modifier = modifier,
            )
        }

        else -> {
            OnboardingPrimaryButton(
                text = stringResource(R.string.continue_label),
                onClick = { scrollToNextPage(pagerState, coroutineScope) },
                modifier = modifier,
            )
        }
    }
}
