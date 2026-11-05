package org.stride.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colours beyond the Material scheme: training zones, form (TSB) bands
 * and chart series. Kept stable across dynamic colour so charts stay readable.
 */
@Immutable
data class StrideColors(
    val zone1: Color,
    val zone2: Color,
    val zone3: Color,
    val zone4: Color,
    val zone5: Color,
    val fitness: Color,
    val fatigue: Color,
    val form: Color,
    val fresh: Color,
    val neutral: Color,
    val productive: Color,
    val overreaching: Color,
    val positive: Color,
    val warning: Color
) {
    val zones: List<Color> get() = listOf(zone1, zone2, zone3, zone4, zone5)
}

internal val LightStrideColors = StrideColors(
    zone1 = Color(0xFF8E9BB0),
    zone2 = Color(0xFF2F9E6E),
    zone3 = Color(0xFFE0A100),
    zone4 = Color(0xFFE8642C),
    zone5 = Color(0xFFD02B4A),
    fitness = Color(0xFF1F5AE0),
    fatigue = Color(0xFFD9468C),
    form = Color(0xFFE0A100),
    fresh = Color(0xFF0E8A73),
    neutral = Color(0xFF5B6475),
    productive = Color(0xFF1F5AE0),
    overreaching = Color(0xFFC62828),
    positive = Color(0xFF1B7F4B),
    warning = Color(0xFF9A5B00)
)

internal val DarkStrideColors = StrideColors(
    zone1 = Color(0xFFA9B4C6),
    zone2 = Color(0xFF5CD39C),
    zone3 = Color(0xFFFFC94A),
    zone4 = Color(0xFFFF8A57),
    zone5 = Color(0xFFFF6B85),
    fitness = Color(0xFF8FAEFF),
    fatigue = Color(0xFFFF8AC0),
    form = Color(0xFFFFC94A),
    fresh = Color(0xFF5FE0C4),
    neutral = Color(0xFFB3BAC7),
    productive = Color(0xFF8FAEFF),
    overreaching = Color(0xFFFF8A80),
    positive = Color(0xFF7BE0A6),
    warning = Color(0xFFFFC46B)
)

val LocalStrideColors = staticCompositionLocalOf { LightStrideColors }
