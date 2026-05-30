package com.yage.opencode_client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Quiet Tech color schemes. Dynamic color (Material You) is deliberately NOT
// used: the brand is a fixed electric blue across every device, matching the
// iOS client which also pins its own primary blue rather than following the
// system accent.

private val DarkColorScheme = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = OnSurfaceDark,
    secondary = BrandPrimary,
    tertiary = BrandGold,
    background = BgDark,
    onBackground = OnSurfaceDark,
    surface = BgDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    // Material maps several "container" surfaces; point the ones the UI reads
    // (cards, composer) at our neutral surface so nothing falls back to purple.
    surfaceContainer = SurfaceDark,
    surfaceContainerLow = ComposerDark,
    surfaceContainerLowest = BgDark,
    surfaceContainerHigh = SurfaceDark,
    surfaceContainerHighest = SurfaceDark,
    primaryContainer = BrandPrimary,
    onPrimaryContainer = OnSurfaceDark,
    outline = OutlineDark,
    outlineVariant = OutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = BrandPrimaryLight,
    onPrimary = BgLight,
    secondary = BrandPrimaryLight,
    tertiary = BrandGold,
    background = BgLight,
    onBackground = OnSurfaceLight,
    surface = BgLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainer = SurfaceLight,
    surfaceContainerLow = ComposerLight,
    surfaceContainerLowest = BgLight,
    surfaceContainerHigh = SurfaceLight,
    surfaceContainerHighest = SurfaceLight,
    primaryContainer = BrandPrimaryLight,
    onPrimaryContainer = BgLight,
    outline = OutlineLight,
    outlineVariant = OutlineLight
)

@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colorScheme.background
        ) {
            content()
        }
    }
}
