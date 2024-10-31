package org.distrinet.lanshield.ui.intro

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ManagedActivityResultLauncher
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalUriHandler
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
import org.distrinet.lanshield.STUDY_MORE_INFO_URL
import org.distrinet.lanshield.isAppUsageAccessGranted
import org.distrinet.lanshield.ui.LANShieldIcons
import org.distrinet.lanshield.ui.components.GrantAppUsagePermissionDialog
import org.distrinet.lanshield.ui.settings.SettingsSwitchComp
import org.distrinet.lanshield.ui.theme.LANShieldTheme
import org.distrinet.lanshield.ui.theme.LocalTintTheme
import org.distrinet.lanshield.ui.intro.IntroSlides.*


enum class IntroSlides {
    INTRO_START,
    DEFAULT_POLICY,
    NOTIFICATIONS,
    JOIN_USER_STUDY,
    SHARE_APP_USAGE,
    INTRO_FINISHED
}

@Composable
internal fun IntroRoute(viewModel: IntroViewModel, navigateToOverview: () -> Unit) {

    val defaultPolicy by viewModel.defaultPolicy.collectAsStateWithLifecycle(initialValue = Policy.BLOCK)
    val isShareLanMetricsEnabled by viewModel.shareLanMetricsEnabled.collectAsStateWithLifecycle(initialValue = false)
    val isShareAppUsageEnabled by viewModel.shareAppUsageEnabled.collectAsStateWithLifecycle(initialValue = false)


    Scaffold(topBar = { Spacer(modifier = Modifier.size(50.dp))}) { innerPadding ->
        IntroScreen(modifier = Modifier.padding(innerPadding), defaultPolicy = defaultPolicy,
            onChangeDefaultPolicy = { viewModel.onChangeDefaultPolicy(it) },
            isShareLanMetricsEnabled = isShareLanMetricsEnabled,
            onChangeShareLanMetrics = { viewModel.onChangeShareLanMetrics(it)},
            isShareAppUsageEnabled = isShareAppUsageEnabled,
            onChangeShareAppUsage = { viewModel.onChangeShareAppUsage(it)},
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
internal fun IntroLeftButton(
    modifier: Modifier = Modifier,
    pagerState: PagerState,
    coroutineScope: CoroutineScope,
    onChangeShareLanMetrics: (Boolean) -> Unit,
    isShareLanMetricsEnabled: Boolean) {

    if (pagerState.currentPage == JOIN_USER_STUDY.ordinal) {
        TextButton(onClick = { doShareLanMetricsDecision(false, onChangeShareLanMetrics, coroutineScope, pagerState) },
            modifier = modifier) {
            Text(text = stringResource(R.string.disagree), style = MaterialTheme.typography.bodyLarge)
        }
    }
    else if (pagerState.currentPage == INTRO_FINISHED.ordinal && !isShareLanMetricsEnabled) {
        IconButton(
            onClick = { scrollToPage(pagerState, JOIN_USER_STUDY.ordinal, coroutineScope) },
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
internal fun IntroRightButton(
    modifier: Modifier = Modifier,
    pagerState: PagerState,
    coroutineScope: CoroutineScope,
    onChangeShareLanMetrics: (Boolean) -> Unit,
    onChangeFinishAppIntro: (Boolean) -> Unit,
    navigateToOverview: () -> Unit,
    requestNotificationPermissionLauncher: ManagedActivityResultLauncher<String, Boolean>
) {
    if (pagerState.currentPage == JOIN_USER_STUDY.ordinal) {
        TextButton(
            onClick = { doShareLanMetricsDecision(true, onChangeShareLanMetrics, coroutineScope, pagerState) },
            modifier = modifier) {
            Text(text = stringResource(R.string.agree), style = MaterialTheme.typography.bodyLarge)
        }
    }
    else if (pagerState.currentPage == NOTIFICATIONS.ordinal) {
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

@Composable
internal fun IntroScreen(
    modifier: Modifier = Modifier,
    initialPage: Int = 0,
    defaultPolicy: Policy = Policy.BLOCK,
    onChangeDefaultPolicy: (Policy) -> Unit = {},
    isShareAppUsageEnabled: Boolean = false,
    onChangeShareAppUsage: (Boolean) -> Unit = {},
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
        (pagerState.currentPage != NOTIFICATIONS.ordinal || notificationPermissionDialogLaunched) &&
        pagerState.currentPage != JOIN_USER_STUDY.ordinal &&
                (pagerState.currentPage != INTRO_FINISHED.ordinal || isShareLanMetricsEnabled)

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
                JOIN_USER_STUDY.ordinal -> ShareLanMetricsSlide()
                SHARE_APP_USAGE.ordinal -> ShareAppUsageSlide(isShareAppUsageEnabled
                ) { isEnabled ->
                    onChangeShareAppUsageWithPermission(
                        isEnabled,
                        onChangeShareAppUsage, { showGrantAppUsageDialog = true },
                        context)
                }

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
                navigateToOverview = navigateToOverview,
                onChangeFinishAppIntro = onChangeFinishAppIntro,
                requestNotificationPermissionLauncher = requestNotificationPermissionLauncher)

        }
    }
}

fun doShareLanMetricsDecision(shareLanMetrics: Boolean, onChangeShareLanMetrics: (Boolean) -> Unit, coroutineScope: CoroutineScope, pagerState: PagerState) {
    onChangeShareLanMetrics(shareLanMetrics)
    if(shareLanMetrics) {
        scrollToPage(pagerState, SHARE_APP_USAGE.ordinal, coroutineScope)
    }
    else {
        scrollToPage(pagerState, INTRO_FINISHED.ordinal, coroutineScope)
    }
}



fun scrollToPage(pagerState: PagerState, targetPage: Int, coroutineScope: CoroutineScope) {
    coroutineScope.launch() {
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


@OptIn(ExperimentalMaterial3Api::class)
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
fun ShareLanMetricsSlidePreview() {
    LANShieldTheme(darkTheme = true) {
        IntroScreen(
            initialPage = JOIN_USER_STUDY.ordinal,
            createNotificationChannels = { })
    }
}


@Composable
internal fun ShareLanMetricsSlide() {
    var showMoreInfoDialog by remember {mutableStateOf(false)}

    if(showMoreInfoDialog) {
        AlertDialog(
            icon = {
                Icon(LANShieldIcons.Info, contentDescription = stringResource(id = R.string.info), tint = getIconTint())
            },
            title = { Text(text = stringResource(id = R.string.more_info)) },
            text = {
                Text(text = stringResource(R.string.intro_share_lan_metrics_more_info)) //TODO
            },
            onDismissRequest = { showMoreInfoDialog = false},
            dismissButton = {
                TextButton(
                    onClick = { showMoreInfoDialog = false}
                ) {
                    Text(text = stringResource(id = R.string.privacy_policy))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showMoreInfoDialog = false}
                ) {
                    Text(text = stringResource(id = R.string.dismiss))
                }
            },
        )
    }

    Column() {
        Text(text = stringResource(R.string.join_our_academic_study), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(16.dp))
        Spacer(modifier = Modifier.size(40.dp))
        Icon(imageVector = LANShieldIcons.Science, contentDescription = null, modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .size(125.dp),
            tint = getIconTint()
            )
        Spacer(modifier = Modifier.size(40.dp))
        Text(text= stringResource(R.string.intro_share_lan_metrics).trimIndent(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier
            .padding(24.dp)
            .fillMaxWidth())

//        Button(onClick = {showMoreInfoDialog = true}, modifier = Modifier.align(Alignment.CenterHorizontally)) {
//            Text(text = stringResource(id = R.string.more_info))
//        }
        val uriHandler = LocalUriHandler.current
        Button(onClick = {uriHandler.openUri(STUDY_MORE_INFO_URL)}, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(text = stringResource(id = R.string.more_info))
        }
    }
}

@Preview
@Composable
internal fun ShareAppUsageSlidePreview() {
    LANShieldTheme(darkTheme = true) {
        IntroScreen(initialPage = SHARE_APP_USAGE.ordinal)
    }
}

private fun onChangeShareAppUsageWithPermission(
    isEnabled: Boolean,
    setShareAppUsage: (Boolean) -> Unit,
    showDialog: () -> Unit,
    context: Context,
    ) {
    val accessGranted = isAppUsageAccessGranted(context)

    if (isEnabled && !accessGranted) {
        showDialog()
    } else setShareAppUsage(isEnabled)
}


@Composable
internal fun ShareAppUsageSlide(isShareAppUsageEnabled: Boolean, onChangeShareAppUsage: (Boolean) -> Unit) {
    Column() {
        Text(text = stringResource(R.string.intro_share_app_usage_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(16.dp))
        Spacer(modifier = Modifier.size(40.dp))
        Icon(imageVector = LANShieldIcons.QuestionMark, contentDescription = null, modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .size(125.dp),
            tint = getIconTint()
            )
        Spacer(modifier = Modifier.size(40.dp))
        Text(text= stringResource(R.string.intro_share_app_usage_content).trimIndent(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier
            .padding(24.dp)
            .fillMaxWidth(), textAlign = TextAlign.Center)
        SettingsSwitchComp(
            name = R.string.share_anonymous_app_usage,
            isChecked = isShareAppUsageEnabled,
            onCheckedChange = onChangeShareAppUsage,
            modifier = Modifier.padding(32.dp)
        )
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
    Column() {
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