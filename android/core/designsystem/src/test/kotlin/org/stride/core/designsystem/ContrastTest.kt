package org.stride.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.assertTrue
import org.junit.Test
import org.stride.core.designsystem.theme.DarkScheme
import org.stride.core.designsystem.theme.LightScheme

/** Brand fallback colours must meet WCAG AA (4.5:1) for text on their containers (ANDF-002). */
class ContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance() + 0.05
        val l2 = b.luminance() + 0.05
        return maxOf(l1, l2).toDouble() / minOf(l1, l2).toDouble()
    }

    @Test
    fun textPairsMeetAa() {
        for ((name, s) in listOf("light" to LightScheme, "dark" to DarkScheme)) {
            val pairs = mapOf(
                "onPrimary/primary" to (s.onPrimary to s.primary),
                "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
                "onSecondary/secondary" to (s.onSecondary to s.secondary),
                "onTertiary/tertiary" to (s.onTertiary to s.tertiary),
                "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
                "onError/error" to (s.onError to s.error),
                "onSurface/surface" to (s.onSurface to s.surface),
                "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
                "onBackground/background" to (s.onBackground to s.background)
            )
            for ((pair, colors) in pairs) {
                val ratio = contrast(colors.first, colors.second)
                assertTrue(ratio >= 4.5, "$name $pair contrast $ratio < 4.5")
            }
        }
    }
}
