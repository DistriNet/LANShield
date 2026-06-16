package org.distrinet.lanshield.ui.intro

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shared spacing tokens for the onboarding carousel so every slide breathes the same way
 * instead of carrying its own ad-hoc padding values.
 */
internal object OnboardingSpacing {
    val Screen = 28.dp
    val HeroBadge = 132.dp
    val HeroIcon = 60.dp
    val AfterHero = 36.dp
    val AfterTitle = 16.dp
    val BeforeAction = 28.dp
}

@Composable
internal fun OnboardingSlide(
    page: Int,
    pagerState: PagerState,
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    body: String? = null,
    caption: String? = null,
    titleFontWeight: FontWeight? = null,
    hero: (@Composable () -> Unit)? = null,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val settled = pagerState.settledPage == page

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnboardingSpacing.Screen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        StaggeredItem(settled, order = 0) {
            when {
                hero != null -> hero()
                icon != null -> HeroBadge(icon)
            }
        }

        Spacer(Modifier.height(OnboardingSpacing.AfterHero))
        StaggeredItem(settled, order = 1) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = titleFontWeight,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
        }

        if (body != null) {
            Spacer(Modifier.height(OnboardingSpacing.AfterTitle))
            StaggeredItem(settled, order = 2) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (caption != null) {
            Spacer(Modifier.height(OnboardingSpacing.AfterTitle))
            StaggeredItem(settled, order = 3) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (action != null) {
            Spacer(Modifier.height(OnboardingSpacing.BeforeAction))
            StaggeredItem(settled, order = 3) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = action,
                )
            }
        }

        Spacer(Modifier.weight(1.4f))
    }
}

@Composable
internal fun HeroBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(OnboardingSpacing.HeroBadge)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(OnboardingSpacing.HeroIcon),
        )
    }
}

@Composable
internal fun StaggeredItem(
    visible: Boolean,
    order: Int,
    content: @Composable () -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    val delay = order * 90
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(durationMillis = 380, delayMillis = delay)) +
            slideInVertically(tween(durationMillis = 380, delayMillis = delay)) { it / 5 },
        exit = fadeOut(tween(durationMillis = 120)),
    ) {
        content()
    }
}

@Composable
internal fun OnboardingPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun OnboardingTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}
