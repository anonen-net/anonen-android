package net.anonen.app.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import net.anonen.app.core.ThemeMode

private val AnonGreen = Color(0xFF16A34A)
private val AnonGreenDim = Color(0xFF166534)
private val Ink = Color(0xFF0A0A0A)
private val Paper = Color(0xFFFAFAF9)
private val Muted = Color(0xFF57534E)
private val SurfaceAlt = Color(0xFFF5F5F4)

private val WarmCharcoal = Color(0xFF2C2B29)
private val DarkCard = Color(0xFF383634)
private val DarkSurfaceAlt = Color(0xFF44423F)
private val DarkMuted = Color(0xFFB8B2AA)
private val DarkGreen = Color(0xFF22C55E)

private val AnonenLightColorScheme =
    lightColorScheme(
        primary = AnonGreen,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCFCE7),
        onPrimaryContainer = Color(0xFF14532D),
        secondary = Muted,
        onSecondary = Color.White,
        secondaryContainer = SurfaceAlt,
        onSecondaryContainer = Ink,
        tertiary = AnonGreenDim,
        onTertiary = Color.White,
        background = Paper,
        onBackground = Ink,
        surface = Paper,
        onSurface = Ink,
        surfaceVariant = SurfaceAlt,
        onSurfaceVariant = Muted,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color.White,
        surfaceContainer = SurfaceAlt,
        surfaceContainerHigh = Color.White,
        surfaceContainerHighest = SurfaceAlt,
        outline = Ink,
        outlineVariant = Color(0xFFD6D3D1),
        inverseSurface = Ink,
        inverseOnSurface = Paper,
        inversePrimary = Color(0xFF86EFAC),
        error = Color(0xFFDC2626),
        onError = Color.White,
        errorContainer = Color(0xFFFEE2E2),
        onErrorContainer = Color(0xFF7F1D1D),
    )

private val AnonenDarkColorScheme =
    darkColorScheme(
        primary = DarkGreen,
        onPrimary = Color(0xFF052E16),
        primaryContainer = Color(0xFF14532D),
        onPrimaryContainer = Color(0xFFBBF7D0),
        secondary = DarkMuted,
        onSecondary = Ink,
        secondaryContainer = DarkSurfaceAlt,
        onSecondaryContainer = Paper,
        tertiary = Color(0xFF4ADE80),
        onTertiary = Color(0xFF052E16),
        background = WarmCharcoal,
        onBackground = Paper,
        surface = WarmCharcoal,
        onSurface = Paper,
        surfaceVariant = DarkSurfaceAlt,
        onSurfaceVariant = DarkMuted,
        surfaceContainerLowest = DarkCard,
        surfaceContainerLow = DarkCard,
        surfaceContainer = Color(0xFF242321),
        surfaceContainerHigh = DarkCard,
        surfaceContainerHighest = DarkSurfaceAlt,
        outline = Paper,
        outlineVariant = Color(0xFF52504C),
        inverseSurface = Paper,
        inverseOnSurface = Ink,
        inversePrimary = AnonGreenDim,
        error = Color(0xFFF87171),
        onError = Color(0xFF450A0A),
        errorContainer = Color(0xFF7F1D1D),
        onErrorContainer = Color(0xFFFECACA),
    )

@Composable
fun cardBorderColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0xFF78716C)
    } else {
        Color(0xFF57534E)
    }

@Composable
fun cardShadowColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0x73000000)
    } else {
        Color(0x331C1917)
    }

@Composable
fun usageToneColor(positive: Boolean): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when {
        positive && dark -> Color(0xFF4ADE80)
        positive -> Color(0xFF15803D)
        dark -> Color(0xFFFBBF24)
        else -> Color(0xFFB45309)
    }
}

@Composable
fun AnonenTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark =
        when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    val colorScheme = if (dark) AnonenDarkColorScheme else AnonenLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 35) {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AnonenTypography,
        content = content,
    )
}
