package org.ghost.design

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostContrastTest {
    @Test
    fun lightAndDarkTextPairsMeetNormalTextContrast() {
        for (scheme in listOf(GhostLightColors, GhostDarkColors)) {
            val pairs = listOf(
                scheme.onPrimary to scheme.primary, scheme.onSecondary to scheme.secondary,
                scheme.onTertiary to scheme.tertiary, scheme.onError to scheme.error,
                scheme.onBackground to scheme.background, scheme.onSurface to scheme.surface,
                scheme.onSurfaceVariant to scheme.surface, scheme.onSurfaceVariant to scheme.surfaceVariant,
                scheme.onPrimaryContainer to scheme.primaryContainer, scheme.onSecondaryContainer to scheme.secondaryContainer,
                scheme.onTertiaryContainer to scheme.tertiaryContainer, scheme.onErrorContainer to scheme.errorContainer,
            )
            for ((foreground, background) in pairs) {
                val a = luminance(foreground)
                val b = luminance(background)
                val ratio = (max(a, b) + 0.05) / (min(a, b) + 0.05)
                assertTrue("Contrast $ratio for $foreground on $background", ratio >= 4.5)
            }
        }
    }

    private fun luminance(color: Color): Double {
        fun linear(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
