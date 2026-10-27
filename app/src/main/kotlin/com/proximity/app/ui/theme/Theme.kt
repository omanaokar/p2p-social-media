package com.proximity.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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

@Composable
fun ProximityTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
