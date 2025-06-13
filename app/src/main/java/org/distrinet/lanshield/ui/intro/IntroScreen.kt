package org.distrinet.lanshield.ui.intro

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.R
import org.distrinet.lanshield.ui.LANShieldIcons
import org.distrinet.lanshield.ui.components.GrantAppUsagePermissionDialog
import org.distrinet.lanshield.ui.intro.IntroSlides.DEFAULT_POLICY
import org.distrinet.lanshield.ui.intro.IntroSlides.INTRO_FINISHED
import org.distrinet.lanshield.ui.intro.IntroSlides.INTRO_START
import org.distrinet.lanshield.ui.intro.IntroSlides.NOTIFICATIONS
import org.distrinet.lanshield.ui.theme.LANShieldTheme
import org.distrinet.lanshield.ui.theme.LocalTintTheme


enum class IntroSlides {
    INTRO_START,
    DEFAULT_POLICY,
    NOTIFICATIONS,
    INTRO_FINISHED
}

@Composable
internal fun IntroRoute(viewModel: IntroViewModel, navigateToOverview: () -> Unit) {

    val defaultPolicy by viewModel.defaultPolicy.collectAsStateWithLifecycle(initialValue = Policy.BLOCK)

    Scaffold(topBar = { Spacer(modifier = Modifier.size(50.dp))}) { innerPadding ->
        IntroScreen(modifier = Modifier.padding(innerPadding), defaultPolicy = defaultPolicy,
            onChangeDefaultPolicy = { viewModel.onChangeDefaultPolicy(it) },
            navigateToOverview = navigateToOverview,
            onChangeFinishAppIntro = { viewModel.onChangeAppIntro(it) },
            createNotificationChannels = { viewModel.createNotificationChannels() }
            )
    }

}

@Preview
@Composable
internal fun IntroStartPreview() {
    LANShieldTheme(darkTheme = true) {
        IntroScreen(
            initialPage = INTRO_START.ordinal,
            createNotificationChannels = { })
    }
}

@Composable
internal fun IntroScreen(
    modifier: Modifier = Modifier,
    initialPage: Int = 0,
    defaultPolicy: Policy = Policy.BLOCK,
    onChangeDefaultPolicy: (Policy) -> Unit = {},
    isShareLanMetricsEnabled: Boolean = false,
    onChangeShareLanMetrics: (Boolean) -> Unit = {},
    navigateToOverview: () -> Unit = {},
    onChangeFinishAppIntro: (Boolean) -> Unit = {},
    createNotificationChannels: () -> Unit = {}
) {
    val pageCount = IntroSlides.entries.size //if(isShareLanMetricsEnabled) IntroSlides.entries.size else IntroSlides.entries.size - 1
    val pagerState = rememberPagerState(pageCount = {
        pageCount
    }, initialPage = initialPage)

    val coroutineScope = rememberCoroutineScope()
    var notificationPermissionDialogLaunched by remember { mutableStateOf(false) }
    var showGrantAppUsageDialog by remember { mutableStateOf(false) }


    val requestNotificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notificationPermissionDialogLaunched = true
        createNotificationChannels()
        scrollToNextPage(pagerState, coroutineScope)
    }
    
    val scrollEnabled =
        (pagerState.currentPage != NOTIFICATIONS.ordinal || notificationPermissionDialogLaunched)

    BackHandler(enabled = pagerState.currentPage != 0) {
        scrollToPreviousPage(pagerState, coroutineScope)
    }
    val context = LocalContext.current

    if (showGrantAppUsageDialog) {
        GrantAppUsagePermissionDialog(
            onDismiss = { showGrantAppUsageDialog = false },
            onConfirm = { showGrantAppUsageDialog = false
                startUsageAccessSettingsActivity(context) }
        )
    }

    Column(modifier = modifier) {
        HorizontalPager(state = pagerState, userScrollEnabled = scrollEnabled, modifier = Modifier
            .weight(1F)
            .fillMaxHeight()) { page ->
            when (page) {
                INTRO_START.ordinal -> IntroSlide()
                DEFAULT_POLICY.ordinal -> DefaultPolicySlide(defaultPolicy = defaultPolicy, onChangeDefaultPolicy = onChangeDefaultPolicy)
                NOTIFICATIONS.ordinal -> NotificationSlide(doRequestNotificationPermission = {
                    requestNotificationPermissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
                }
                )
                INTRO_FINISHED.ordinal -> IntroFinishedSlide()
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
        ) {
            IntroLeftButton(modifier = Modifier
                .padding(8.dp)
                .align(Alignment.CenterStart),
                coroutineScope = coroutineScope,
                pagerState = pagerState,
                onChangeShareLanMetrics = onChangeShareLanMetrics,
                isShareLanMetricsEnabled = isShareLanMetricsEnabled)
            Row(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 8.dp, vertical = 12.dp)
            ) {
                repeat(pageCount) { iteration ->
                    val color =
                        if (pagerState.currentPage == iteration) Color.DarkGray else Color.LightGray
                        Box(
                            modifier = Modifier
                                .padding(8.dp)
                                .background(color, CircleShape)
                                .size(10.dp)
                        )
                }
            }
            IntroRightButton(modifier = Modifier
                .padding(8.dp)
                .align(Alignment.CenterEnd),
                coroutineScope = coroutineScope,
                pagerState = pagerState,
                onChangeShareLanMetrics = onChangeShareLanMetrics,
                isShareLanMetricsEnabled = isShareLanMetricsEnabled,
                navigateToOverview = navigateToOverview,
                onChangeFinishAppIntro = onChangeFinishAppIntro,
                requestNotificationPermissionLauncher = requestNotificationPermissionLauncher)

        }
    }
}


fun scrollToPage(pagerState: PagerState, targetPage: Int, coroutineScope: CoroutineScope) {
    coroutineScope.launch {
        pagerState.animateScrollToPage(targetPage)
    }
}
fun scrollToNextPage(pagerState: PagerState, coroutineScope: CoroutineScope) {
    if(pagerState.currentPage >= IntroSlides.entries.size - 1) return
    scrollToPage(pagerState, pagerState.currentPage + 1 , coroutineScope)

}

fun scrollToPreviousPage(pagerState: PagerState, coroutineScope: CoroutineScope) {
    if(pagerState.currentPage <= 0) return
    scrollToPage(pagerState, pagerState.currentPage -1 , coroutineScope)

}

@Composable
internal fun IntroSlide(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = "LANShield", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(16.dp))
        Image(
            painter = painterResource(id = R.mipmap.logo_foreground),
            contentDescription = stringResource(R.string.lanshield_logo),
            modifier = Modifier
                .size(200.dp)
                .align(Alignment.CenterHorizontally),
            contentScale = ContentScale.Crop
        )
        Text(text= stringResource(R.string.intro_welcome).trimIndent(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp), textAlign = TextAlign.Center)
    }
}

@Preview
@Composable
internal fun DefaultPolicySlidePreview() {
    LANShieldTheme(darkTheme = true) {
        IntroScreen(
            initialPage = DEFAULT_POLICY.ordinal,
            createNotificationChannels = { })
    }
}

@Composable
internal fun DefaultPolicySlide(defaultPolicy: Policy, onChangeDefaultPolicy: (Policy) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.set_a_default_policy), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(16.dp))
        Spacer(modifier = Modifier.size(40.dp))
        Icon(imageVector = LANShieldIcons.Block, contentDescription = null, modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .size(125.dp),
            tint = getIconTint()
        )
        Spacer(modifier = Modifier.size(40.dp))
        Text(text= stringResource(R.string.intro_default_policy).trimIndent(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp))

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .scale(0.9F)
                .padding(end = 0.dp)
                .align(Alignment.CenterHorizontally)
        ) {
            SegmentedButton(
                selected = defaultPolicy == Policy.BLOCK,
                onClick = { onChangeDefaultPolicy(Policy.BLOCK) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = 0,
                    count = 2
                )
            ) {
                Text(text = stringResource(R.string.block))
            }
            SegmentedButton(
                selected = defaultPolicy == Policy.ALLOW,
                onClick = { onChangeDefaultPolicy(Policy.ALLOW) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = 1,
                    count = 2
                )
            ) {
                Text(text = stringResource(R.string.allow))
            }
        }


    }
}

@Preview
@Composable
internal fun NotificationSlidePreview() {
    LANShieldTheme(darkTheme = true) {
        IntroScreen(
            initialPage = NOTIFICATIONS.ordinal,
            createNotificationChannels = { })
    }
}


@Composable
internal fun NotificationSlide(doRequestNotificationPermission: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.get_notified), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(16.dp))
        Spacer(modifier = Modifier.size(40.dp))
        Card(modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Image(painterResource(id = R.drawable.lanshield_notification), contentDescription = null, modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(350.dp)
                .padding(8.dp))
        }
//        Icon(imageVector = LANShieldIcons.NotificationsActive, contentDescription = null, modifier = Modifier
//            .align(Alignment.CenterHorizontally)
//            .size(125.dp),
//            tint = getIconTint()
//        )
        Spacer(modifier = Modifier.size(40.dp))
        Text(
            text = stringResource(R.string.intro_notification).trimIndent(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(24.dp),
        )
        Spacer(modifier = Modifier.size(40.dp))
        Button(onClick = doRequestNotificationPermission, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(text = "Allow notifications")
        }
    }
}


@Preview
@Composable
internal fun IntroFinishedSlidePreview() {
    LANShieldTheme(darkTheme = true) {
        IntroScreen(
            initialPage = INTRO_FINISHED.ordinal,
            createNotificationChannels = { })
    }
}

@Composable
internal fun getIconTint() : Color {
    return if ( LocalTintTheme.current.iconTint != Color.Unspecified) LocalTintTheme.current.iconTint else Color.Unspecified
}

@Composable
internal fun IntroFinishedSlide() {
    Column {
        Text(
            text = stringResource(R.string.you_re_all_set),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(16.dp)
        )
        Spacer(modifier = Modifier.size(40.dp))
        Icon(
            imageVector = LANShieldIcons.RocketLaunch,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(125.dp),
            tint = getIconTint()
        )
        Spacer(modifier = Modifier.size(40.dp))
        Text(
            text = stringResource(R.string.enjoy_using_lanshield).trimIndent(), style = MaterialTheme.typography.titleMedium, modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(), textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.intro_finished_feedback).trimIndent(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(), textAlign = TextAlign.Center
        )
    }
}

private fun startUsageAccessSettingsActivity(context: Context) {
    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
}