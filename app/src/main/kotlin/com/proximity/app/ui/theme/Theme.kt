package com.proximity.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = OnPrimaryBlue,
    primaryContainer = PrimaryContainerBlue,
    onPrimaryContainer = OnPrimaryContainerBlue,
    secondary = SecondaryCyan,
    onSecondary = OnSecondaryCyan,
    secondaryContainer = SecondaryContainerCyan,
    onSecondaryContainer = OnSecondaryContainerCyan,
    tertiary = TertiaryIndigo,
    onTertiary = OnTertiaryIndigo,
    tertiaryContainer = TertiaryContainerIndigo,
    onTertiaryContainer = OnTertiaryContainerIndigo,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF1D9BF0),
    onPrimary = Color(0xFF001018),
    primaryContainer = Color(0xFF0B2638),
    onPrimaryContainer = Color(0xFFD7EEFA),
    secondary = Color(0xFF69C8D0),
    onSecondary = Color(0xFF002022),
    secondaryContainer = Color(0xFF16383B),
    onSecondaryContainer = Color(0xFFB9EDEF),
    tertiary = Color(0xFFB6B9FF),
    onTertiary = Color(0xFF17194A),
    tertiaryContainer = Color(0xFF30336B),
    onTertiaryContainer = Color(0xFFE0E0FF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE7E9EA),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE7E9EA),
    surfaceVariant = Color(0xFF16181C),
    onSurfaceVariant = Color(0xFFADB5BB),
    outline = Color(0xFF71767B),
    outlineVariant = Color(0xFF2F3336),
)

@Composable
fun ProximityTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val targetColorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val animatedColorScheme = targetColorScheme.copy(
        primary = animateThemeColor(targetColorScheme.primary),
        onPrimary = animateThemeColor(targetColorScheme.onPrimary),
        primaryContainer = animateThemeColor(targetColorScheme.primaryContainer),
        onPrimaryContainer = animateThemeColor(targetColorScheme.onPrimaryContainer),
        secondary = animateThemeColor(targetColorScheme.secondary),
        onSecondary = animateThemeColor(targetColorScheme.onSecondary),
        secondaryContainer = animateThemeColor(targetColorScheme.secondaryContainer),
        onSecondaryContainer = animateThemeColor(targetColorScheme.onSecondaryContainer),
        tertiary = animateThemeColor(targetColorScheme.tertiary),
        onTertiary = animateThemeColor(targetColorScheme.onTertiary),
        tertiaryContainer = animateThemeColor(targetColorScheme.tertiaryContainer),
        onTertiaryContainer = animateThemeColor(targetColorScheme.onTertiaryContainer),
        background = animateThemeColor(targetColorScheme.background),
        onBackground = animateThemeColor(targetColorScheme.onBackground),
        surface = animateThemeColor(targetColorScheme.surface),
        onSurface = animateThemeColor(targetColorScheme.onSurface),
        surfaceVariant = animateThemeColor(targetColorScheme.surfaceVariant),
        onSurfaceVariant = animateThemeColor(targetColorScheme.onSurfaceVariant),
        outline = animateThemeColor(targetColorScheme.outline),
        outlineVariant = animateThemeColor(targetColorScheme.outlineVariant),
        error = animateThemeColor(targetColorScheme.error),
        onError = animateThemeColor(targetColorScheme.onError),
    )
    MaterialTheme(
        colorScheme = animatedColorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
private fun animateThemeColor(target: Color): Color {
    val color by animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "theme-color",
    )
    return color
}
