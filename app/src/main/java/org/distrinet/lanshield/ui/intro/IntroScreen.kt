package org.distrinet.lanshield.ui.intro

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.distrinet.lanshield.ABOUT_LANSHIELD_URL
import org.distrinet.lanshield.PRIVACY_POLICY_URL
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.R
import org.distrinet.lanshield.isAppUsageAccessGranted
import org.distrinet.lanshield.ui.LANShieldIcons
import org.distrinet.lanshield.ui.components.GrantAppUsagePermissionDialog
import org.distrinet.lanshield.ui.intro.IntroSlides.DEFAULT_POLICY
import org.distrinet.lanshield.ui.intro.IntroSlides.INTRO_FINISHED
import org.distrinet.lanshield.ui.intro.IntroSlides.INTRO_START
import org.distrinet.lanshield.ui.intro.IntroSlides.JOIN_USER_STUDY
import org.distrinet.lanshield.ui.intro.IntroSlides.NOTIFICATIONS
import org.distrinet.lanshield.ui.intro.IntroSlides.SHARE_APP_USAGE
import org.distrinet.lanshield.ui.settings.SettingsSwitchComp
import org.distrinet.lanshield.ui.theme.LANShieldTheme
import org.distrinet.lanshield.ui.theme.LocalTintTheme


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
    val isShareLanMetricsEnabled by viewModel.shareLanMetricsEnabled.collectAsStateWithLifecycle(
        initialValue = false
    )
    val isShareAppUsageEnabled by viewModel.shareAppUsageEnabled.collectAsStateWithLifecycle(
        initialValue = false
    )


    Scaffold { innerPadding ->
        IntroScreen(
            modifier = Modifier.padding(innerPadding), defaultPolicy = defaultPolicy,
            onChangeDefaultPolicy = { viewModel.onChangeDefaultPolicy(it) },
            isShareLanMetricsEnabled = isShareLanMetricsEnabled,
            onChangeShareLanMetrics = { viewModel.onChangeShareLanMetrics(it) },
            isShareAppUsageEnabled = isShareAppUsageEnabled,
            onChangeShareAppUsage = { viewModel.onChangeShareAppUsage(it) },
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

@Preview
@Composable
internal fun IntroStartLightPreview() {
    LANShieldTheme(darkTheme = false) {
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
    isShareAppUsageEnabled: Boolean = false,
    onChangeShareAppUsage: (Boolean) -> Unit = {},
    isShareLanMetricsEnabled: Boolean = false,
    onChangeShareLanMetrics: (Boolean) -> Unit = {},
    navigateToOverview: () -> Unit = {},
    onChangeFinishAppIntro: (Boolean) -> Unit = {},
    createNotificationChannels: () -> Unit = {}
) {
    val pageCount = IntroSlides.entries.size
    val pagerState = rememberPagerState(pageCount = {
        pageCount
    }, initialPage = initialPage)

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var showGrantAppUsageDialog by remember { mutableStateOf(false) }

    var notificationsEnabled by remember { mutableStateOf(areNotificationsEnabled(context)) }
    var notificationRequested by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = areNotificationsEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestNotificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        notificationRequested = true
        createNotificationChannels()
        notificationsEnabled = areNotificationsEnabled(context)
    }

    val activity = context.findActivity()
    val canRequestRuntime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val notificationsPermanentlyDenied = !notificationsEnabled && (
        !canRequestRuntime ||
            (notificationRequested && activity != null &&
                !activity.shouldShowRequestPermissionRationale(POST_NOTIFICATIONS_PERMISSION))
        )
    val onAllowNotifications: () -> Unit = {
        if (canRequestRuntime && !notificationsPermanentlyDenied) {
            requestNotificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
        } else {
            openNotificationSettings(context)
        }
    }

    val canSwipeForward =
        (pagerState.currentPage != NOTIFICATIONS.ordinal || notificationsEnabled) &&
                pagerState.currentPage != JOIN_USER_STUDY.ordinal &&
                (pagerState.currentPage != INTRO_FINISHED.ordinal || isShareLanMetricsEnabled)

    val canSwipeBack =
        pagerState.currentPage != INTRO_FINISHED.ordinal || isShareLanMetricsEnabled

    val canSwipeForwardState = rememberUpdatedState(canSwipeForward)
    val canSwipeBackState = rememberUpdatedState(canSwipeBack)
    val blockSwipe = remember {
        object : NestedScrollConnection {
            private fun blocked(deltaX: Float): Boolean =
                (deltaX < 0f && !canSwipeForwardState.value) ||
                    (deltaX > 0f && !canSwipeBackState.value)

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && blocked(available.x))
                    available.copy(y = 0f) else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity =
                if (blocked(available.x)) available.copy(y = 0f) else Velocity.Zero
        }
    }

    BackHandler(enabled = pagerState.currentPage != 0) {
        scrollToPreviousPage(pagerState, coroutineScope)
    }

    if (showGrantAppUsageDialog) {
        GrantAppUsagePermissionDialog(
            onDismiss = { showGrantAppUsageDialog = false },
            onConfirm = {
                showGrantAppUsageDialog = false
                startUsageAccessSettingsActivity(context)
            }
        )
    }

    val backgroundBrush = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        )
    )

    Box(modifier = modifier.fillMaxSize().background(backgroundBrush)) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState, modifier = Modifier
                    .weight(1F)
                    .fillMaxHeight()
                    .nestedScroll(blockSwipe)
            ) { page ->
                when (page) {
                    INTRO_START.ordinal -> IntroSlide(page, pagerState)
                    DEFAULT_POLICY.ordinal -> DefaultPolicySlide(
                        page = page,
                        pagerState = pagerState,
                        defaultPolicy = defaultPolicy,
                        onChangeDefaultPolicy = onChangeDefaultPolicy
                    )

                    NOTIFICATIONS.ordinal -> NotificationSlide(
                        page = page,
                        pagerState = pagerState,
                        notificationsEnabled = notificationsEnabled,
                        permanentlyDenied = notificationsPermanentlyDenied,
                        onAllowNotifications = onAllowNotifications,
                    )

                    JOIN_USER_STUDY.ordinal -> ShareLanMetricsSlide(
                        page = page,
                        pagerState = pagerState,
                        onChangeShareLanMetrics = onChangeShareLanMetrics,
                        isShareLanMetricsEnabled = isShareLanMetricsEnabled
                    )

                    SHARE_APP_USAGE.ordinal -> ShareAppUsageSlide(
                        page = page,
                        pagerState = pagerState,
                        isShareAppUsageEnabled = isShareAppUsageEnabled,
                    ) { isEnabled ->
                        onChangeShareAppUsageWithPermission(
                            isEnabled,
                            onChangeShareAppUsage, { showGrantAppUsageDialog = true },
                            context
                        )
                    }

                    INTRO_FINISHED.ordinal -> IntroFinishedSlide(page, pagerState)
                }
            }
            IntroBottomBar(
                pagerState = pagerState,
                pageCount = pageCount,
                coroutineScope = coroutineScope,
                onChangeShareLanMetrics = onChangeShareLanMetrics,
                isShareLanMetricsEnabled = isShareLanMetricsEnabled,
                navigateToOverview = navigateToOverview,
                onChangeFinishAppIntro = onChangeFinishAppIntro,
                requestNotificationPermissionLauncher = requestNotificationPermissionLauncher,
                notificationsEnabled = notificationsEnabled,
            )
        }
    }
}

@Composable
private fun IntroBottomBar(
    pagerState: PagerState,
    pageCount: Int,
    coroutineScope: CoroutineScope,
    onChangeShareLanMetrics: (Boolean) -> Unit,
    isShareLanMetricsEnabled: Boolean,
    navigateToOverview: () -> Unit,
    onChangeFinishAppIntro: (Boolean) -> Unit,
    requestNotificationPermissionLauncher: androidx.activity.compose.ManagedActivityResultLauncher<String, Boolean>,
    notificationsEnabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PageIndicator(pagerState = pagerState, pageCount = pageCount)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IntroLeftButton(
                    coroutineScope = coroutineScope,
                    pagerState = pagerState,
                    onChangeShareLanMetrics = onChangeShareLanMetrics,
                    isShareLanMetricsEnabled = isShareLanMetricsEnabled
                )
            }
            Box {
                IntroRightButton(
                    coroutineScope = coroutineScope,
                    pagerState = pagerState,
                    onChangeShareLanMetrics = onChangeShareLanMetrics,
                    isShareLanMetricsEnabled = isShareLanMetricsEnabled,
                    navigateToOverview = navigateToOverview,
                    onChangeFinishAppIntro = onChangeFinishAppIntro,
                    requestNotificationPermissionLauncher = requestNotificationPermissionLauncher,
                    notificationsEnabled = notificationsEnabled,
                )
            }
        }
    }
}

/** Theme-aware page indicator; the active page grows into a brand-colored pill. */
@Composable
private fun PageIndicator(pagerState: PagerState, pageCount: Int) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { iteration ->
            val selected = pagerState.currentPage == iteration
            val dotWidth by animateDpAsState(
                targetValue = if (selected) 26.dp else 8.dp,
                label = "dotWidth"
            )
            val color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(8.dp)
                    .width(dotWidth)
                    .background(color, CircleShape)
            )
        }
    }
}


fun doShareLanMetricsDecision(
    shareLanMetrics: Boolean,
    onChangeShareLanMetrics: (Boolean) -> Unit,
    coroutineScope: CoroutineScope,
    pagerState: PagerState
) {
    onChangeShareLanMetrics(shareLanMetrics)
    if (shareLanMetrics) {
        scrollToPage(pagerState, SHARE_APP_USAGE.ordinal, coroutineScope)
    } else {
        scrollToPage(pagerState, INTRO_FINISHED.ordinal, coroutineScope)
    }
}


fun scrollToPage(pagerState: PagerState, targetPage: Int, coroutineScope: CoroutineScope) {
    coroutineScope.launch() {
        pagerState.animateScrollToPage(targetPage)
    }
}

fun scrollToNextPage(pagerState: PagerState, coroutineScope: CoroutineScope) {
    if (pagerState.currentPage >= IntroSlides.entries.size - 1) return
    scrollToPage(pagerState, pagerState.currentPage + 1, coroutineScope)

}

fun scrollToPreviousPage(pagerState: PagerState, coroutineScope: CoroutineScope) {
    if (pagerState.currentPage <= 0) return
    scrollToPage(pagerState, pagerState.currentPage - 1, coroutineScope)

}

@Composable
internal fun IntroSlide(page: Int, pagerState: PagerState, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current

    OnboardingSlide(
        page = page,
        pagerState = pagerState,
        modifier = modifier,
        title = stringResource(R.string.app_name),
        titleFontWeight = FontWeight.Bold,
        body = stringResource(R.string.intro_welcome).trimIndent(),
        hero = { IntroHero() },
    ) {
        OnboardingTextButton(
            text = stringResource(R.string.learn_more_on_lanshield_eu),
            onClick = { uriHandler.openUri(ABOUT_LANSHIELD_URL) },
        )
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
internal fun DefaultPolicySlide(
    page: Int,
    pagerState: PagerState,
    defaultPolicy: Policy,
    onChangeDefaultPolicy: (Policy) -> Unit
) {
    OnboardingSlide(
        page = page,
        pagerState = pagerState,
        title = stringResource(R.string.set_a_default_policy),
        icon = LANShieldIcons.Policy,
        body = stringResource(R.string.intro_default_policy).trimIndent(),
    ) {
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = defaultPolicy == Policy.BLOCK,
                onClick = { onChangeDefaultPolicy(Policy.BLOCK) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text(text = stringResource(R.string.block))
            }
            SegmentedButton(
                selected = defaultPolicy == Policy.ALLOW,
                onClick = { onChangeDefaultPolicy(Policy.ALLOW) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
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
internal fun NotificationSlide(
    page: Int,
    pagerState: PagerState,
    notificationsEnabled: Boolean = false,
    permanentlyDenied: Boolean = false,
    onAllowNotifications: () -> Unit = {},
) {
    OnboardingSlide(
        page = page,
        pagerState = pagerState,
        title = stringResource(R.string.get_notified),
        body = stringResource(R.string.intro_notification).trimIndent(),
        hero = {
            Card {
                Image(
                    painterResource(id = R.drawable.lanshield_notification),
                    contentDescription = null,
                    modifier = Modifier
                        .width(340.dp)
                        .padding(8.dp)
                )
            }
        },
        action = {
            val label = when {
                notificationsEnabled -> stringResource(R.string.notifications_enabled)
                permanentlyDenied -> stringResource(R.string.open_notification_settings)
                else -> stringResource(R.string.allow_notifications)
            }
            OnboardingPrimaryButton(
                text = label,
                onClick = onAllowNotifications,
                enabled = !notificationsEnabled,
            )
        },
    )
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


@Preview
@Composable
internal fun ShareAppUsageSlidePreview() {
    LANShieldTheme(darkTheme = true) {
        IntroScreen(initialPage = SHARE_APP_USAGE.ordinal)
    }
}

private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

internal fun areNotificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    context.startActivity(intent)
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
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
internal fun ShareAppUsageSlide(
    page: Int,
    pagerState: PagerState,
    isShareAppUsageEnabled: Boolean,
    onChangeShareAppUsage: (Boolean) -> Unit
) {
    val uriHandler = LocalUriHandler.current
    OnboardingSlide(
        page = page,
        pagerState = pagerState,
        title = stringResource(R.string.intro_share_app_usage_title),
        icon = LANShieldIcons.QuestionMark,
        body = stringResource(R.string.intro_share_app_usage_content).trimIndent(),
    ) {
        SettingsSwitchComp(
            name = R.string.share_anonymous_app_usage,
            isChecked = isShareAppUsageEnabled,
            onCheckedChange = onChangeShareAppUsage,
        )
        OnboardingTextButton(
            text = stringResource(id = R.string.more_info),
            onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
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
internal fun getIconTint(): Color {
    return if (LocalTintTheme.current.iconTint != Color.Unspecified) LocalTintTheme.current.iconTint else Color.Unspecified
}

@Composable
internal fun IntroFinishedSlide(page: Int, pagerState: PagerState) {
    OnboardingSlide(
        page = page,
        pagerState = pagerState,
        title = stringResource(R.string.you_re_all_set),
        icon = LANShieldIcons.RocketLaunch,
        body = stringResource(R.string.enjoy_using_lanshield).trimIndent(),
        caption = stringResource(R.string.intro_finished_feedback).trimIndent(),
    )
}

private fun startUsageAccessSettingsActivity(context: Context) {
    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
}
