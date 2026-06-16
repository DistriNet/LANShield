package org.distrinet.lanshield.ui.intro

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.distrinet.lanshield.R
import org.distrinet.lanshield.ui.LANShieldIcons
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

private data class App(val allowed: Boolean, val rowY: Float)

private val Apps = listOf(
    App(allowed = true, rowY = 0.2f),
    App(allowed = false, rowY = 0.5f),
    App(allowed = true, rowY = 0.8f),
)

private val DeviceIcons = listOf(
    LANShieldIcons.Printer,
    LANShieldIcons.Camera,
    LANShieldIcons.Speaker,
)
private val DeviceRows = listOf(0.2f, 0.5f, 0.8f)

private val HeroHeight = 248.dp
private val HubSize = 184.dp // the LANShield logo
private val AppChip = 40.dp
private val DeviceChip = 44.dp

private const val APP_X = 0.12f
private const val HUB_X = 0.5f
private const val DEVICE_X = 0.88f

private const val CYCLE_MS = 6800f
private const val ARRIVE = 0.135f
private const val FWD_END = 0.21f
private const val GLOW_RISE = 0.035f
private const val GLOW_FALL = 0.10f

private val AllowGreen = Color(0xFF34C759)
private val BlockRed = Color(0xFFFF453A)
private val AppColors = listOf(
    Color(0xFF4F9DFF), // blue
    Color(0xFFF5A623), // amber
    Color(0xFFB36BFF), // violet
)
private fun forwardTarget(app: Int, journey: Int): Int {
    var h = app * 374761393 + (journey + 1) * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    return (h ushr 1) % DeviceRows.size
}

@Composable
internal fun IntroHero(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animationsEnabled = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }

    val progress = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animationsEnabled) {
        if (!animationsEnabled) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            progress.floatValue = (now - start) / 1_000_000f / CYCLE_MS
        }
    }

    val appColors = AppColors
    val spokeColor = MaterialTheme.colorScheme.outlineVariant

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(HeroHeight),
    ) {
        val w = maxWidth
        val h = maxHeight

        Canvas(modifier = Modifier.size(w, h)) {
            val hub = Offset(size.width * HUB_X, size.height * HUB_X)

            Apps.forEach { app ->
                val appPt = Offset(size.width * APP_X, size.height * app.rowY)
                drawLine(spokeColor.copy(alpha = 0.5f), appPt, hub, strokeWidth = 1.5.dp.toPx())
            }
            DeviceRows.forEach { row ->
                val devPt = Offset(size.width * DEVICE_X, size.height * row)
                drawLine(spokeColor.copy(alpha = 0.5f), hub, devPt, strokeWidth = 1.5.dp.toPx())
            }

            if (!animationsEnabled) {
                Apps.forEachIndexed { i, app ->
                    val appPt = Offset(size.width * APP_X, size.height * app.rowY)
                    drawCircle(appColors[i], 5.dp.toPx(), lerp(appPt, hub, 0.5f))
                }
            } else {
                val prog = progress.floatValue
                Apps.forEachIndexed { i, app ->
                    val appPt = Offset(size.width * APP_X, size.height * app.rowY)
                    val laneProg = prog + i.toFloat() / Apps.size
                    val p = laneProg % 1f
                    val journey = floor(laneProg).toInt()

                    if (p < ARRIVE) {
                        val seg = p / ARRIVE
                        val fadeIn = (p / 0.03f).coerceIn(0f, 1f)
                        drawCircle(
                            color = appColors[i].copy(alpha = fadeIn),
                            radius = 5.dp.toPx(),
                            center = lerp(appPt, hub, seg),
                        )
                    }

                    if (app.allowed && p >= ARRIVE && p <= FWD_END) {
                        val targetRow = DeviceRows[forwardTarget(i, journey)]
                        val devPt = Offset(size.width * DEVICE_X, size.height * targetRow)
                        val seg = ((p - ARRIVE) / (FWD_END - ARRIVE)).coerceIn(0f, 1f)
                        drawCircle(
                            color = appColors[i],
                            radius = 5.dp.toPx(),
                            center = lerp(hub, devPt, seg),
                        )
                    }
                }
            }
        }

        if (animationsEnabled) {
            val glowSize = HubSize * 1.08f
            Apps.forEachIndexed { i, app ->
                val verdictColor = if (app.allowed) AllowGreen else BlockRed
                Image(
                    painter = painterResource(id = R.mipmap.logo_foreground),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(verdictColor),
                    modifier = Modifier
                        .offset(x = w * HUB_X - glowSize / 2, y = h * HUB_X - glowSize / 2)
                        .size(glowSize)
                        .blur(28.dp)
                        .graphicsLayer {
                            val p = (progress.floatValue + i.toFloat() / Apps.size) % 1f
                            // Grow monotonically across the whole glow so it only ever radiates out.
                            val grow = ((p - (ARRIVE - GLOW_RISE)) / (GLOW_RISE + GLOW_FALL))
                                .coerceIn(0f, 1f)
                            // Gentle peak — a soft, calm glow rather than a hard flash.
                            alpha = radiance(p) * 0.45f
                            val s = 1f + 0.28f * grow
                            scaleX = s
                            scaleY = s
                        },
                )
            }
        }

        Image(
            painter = painterResource(id = R.mipmap.logo_foreground),
            contentDescription = stringResource(R.string.lanshield_logo),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .offset(x = w * HUB_X - HubSize / 2, y = h * HUB_X - HubSize / 2)
                .size(HubSize),
        )

        Apps.forEachIndexed { i, app ->
            Chip(
                centerX = w * APP_X,
                centerY = h * app.rowY,
                chipSize = AppChip,
                icon = LANShieldIcons.Android,
                iconSize = 24.dp,
                tint = appColors[i],
                horns = !app.allowed,
            )
        }

        DeviceRows.forEachIndexed { j, row ->
            Chip(
                centerX = w * DEVICE_X,
                centerY = h * row,
                chipSize = DeviceChip,
                icon = DeviceIcons[j],
                iconSize = 24.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                scaleProvider = if (animationsEnabled) {
                    {
                        val prog = progress.floatValue
                        var pulse = 0f
                        Apps.forEachIndexed { i, app ->
                            if (app.allowed) {
                                val laneProg = prog + i.toFloat() / Apps.size
                                val journey = floor(laneProg).toInt()
                                if (forwardTarget(i, journey) == j) {
                                    val p = laneProg % 1f
                                    pulse = max(
                                        pulse,
                                        (1f - abs(p - FWD_END) / 0.06f).coerceIn(0f, 1f),
                                    )
                                }
                            }
                        }
                        1f + 0.12f * pulse
                    }
                } else null,
            )
        }
    }
}

@Composable
private fun Chip(
    centerX: Dp,
    centerY: Dp,
    chipSize: Dp,
    icon: ImageVector,
    iconSize: Dp,
    tint: Color,
    scaleProvider: (() -> Float)? = null,
    horns: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    tonalElevation: Dp = 3.dp,
) {
    Surface(
        modifier = Modifier
            .offset(x = centerX - chipSize / 2, y = centerY - chipSize / 2)
            .size(chipSize)
            .graphicsLayer {
                val s = scaleProvider?.invoke() ?: 1f
                scaleX = s
                scaleY = s
            },
        shape = CircleShape,
        color = containerColor,
        tonalElevation = tonalElevation,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
            if (horns) {
                Canvas(modifier = Modifier.size(iconSize)) {
                    val s = size.minDimension
                    val left = Path().apply {
                        moveTo(0.30f * s, 0.35f * s)
                        quadraticTo(0.13f * s, 0.27f * s, 0.18f * s, 0.06f * s)
                        quadraticTo(0.33f * s, 0.17f * s, 0.43f * s, 0.33f * s)
                        close()
                    }
                    val right = Path().apply {
                        moveTo(0.70f * s, 0.35f * s)
                        quadraticTo(0.87f * s, 0.27f * s, 0.82f * s, 0.06f * s)
                        quadraticTo(0.67f * s, 0.17f * s, 0.57f * s, 0.33f * s)
                        close()
                    }
                    // Nudge the horns down a touch so their bases cover the robot's antennae.
                    translate(top = 0.03f * s) {
                        drawPath(left, BlockRed)
                        translate(left = 0.015f * s) {
                            drawPath(right, BlockRed)
                        }
                    }
                }
            }
        }
    }
}

private fun lerp(a: Offset, b: Offset, t: Float): Offset =
    Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

private fun radiance(p: Float): Float = when {
    p < ARRIVE - GLOW_RISE -> 0f
    p < ARRIVE -> (p - (ARRIVE - GLOW_RISE)) / GLOW_RISE
    p < ARRIVE + GLOW_FALL -> 1f - (p - ARRIVE) / GLOW_FALL
    else -> 0f
}
