package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PcsDarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = DeepOnAccent,
    primaryContainer = ObsidianSurfaceVariant,
    onPrimaryContainer = NeonCyan,
    secondary = NeonSky,
    onSecondary = DeepOnAccent,
    tertiary = NeonViolet,
    onTertiary = TextPrimary,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = ObsidianPanel,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = TextMuted,
    outline = NeonCyan.copy(alpha = 0.35f)
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PcsDarkColorScheme,
        typography = Typography,
        content = content
    )
}
