package org.ghost.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val GhostLightColors = lightColorScheme(
    primary = Color(0xFF087F8C), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF3F4), onPrimaryContainer = Color(0xFF064B53),
    secondary = Color(0xFF14273D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EDF4), onSecondaryContainer = Color(0xFF14273D),
    tertiary = Color(0xFFB76C09), onTertiary = Color(0xFF160D00),
    tertiaryContainer = Color(0xFFFFE9C5), onTertiaryContainer = Color(0xFF653A00),
    error = Color(0xFFC52A36), onError = Color.White,
    errorContainer = Color(0xFFFFE8E9), onErrorContainer = Color(0xFF8F1420),
    background = Color(0xFFFAFBFC), onBackground = Color(0xFF14273D),
    surface = Color(0xFFFAFBFC), onSurface = Color(0xFF14273D),
    surfaceVariant = Color(0xFFECF1F5), onSurfaceVariant = Color(0xFF465568),
    surfaceContainer = Color(0xFFECF1F5), surfaceContainerLow = Color.White,
    outline = Color(0xFF69788A), outlineVariant = Color(0xFFC6D0DB),
)

internal val GhostDarkColors = darkColorScheme(
    primary = Color(0xFF79D6DF), onPrimary = Color(0xFF00363C),
    primaryContainer = Color(0xFF124E57), onPrimaryContainer = Color(0xFFCBF6F8),
    secondary = Color(0xFFB8CDE2), onSecondary = Color(0xFF14273D),
    secondaryContainer = Color(0xFF253D55), onSecondaryContainer = Color(0xFFE1ECF8),
    tertiary = Color(0xFFF7BE6C), onTertiary = Color(0xFF382100),
    tertiaryContainer = Color(0xFF503A1C), onTertiaryContainer = Color(0xFFFFDDAA),
    error = Color(0xFFFFB3B8), onError = Color(0xFF680815),
    errorContainer = Color(0xFF681C27), onErrorContainer = Color(0xFFFFDADD),
    background = Color(0xFF0D1825), onBackground = Color(0xFFEAF1F7),
    surface = Color(0xFF0D1825), onSurface = Color(0xFFEAF1F7),
    surfaceVariant = Color(0xFF253548), onSurfaceVariant = Color(0xFFC0CCD9),
    surfaceContainer = Color(0xFF14273D), surfaceContainerLow = Color(0xFF14273D),
    outline = Color(0xFF93A5B7), outlineVariant = Color(0xFF46576B),
)

@Composable
fun GhostTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) GhostDarkColors else GhostLightColors, content = content)
}
