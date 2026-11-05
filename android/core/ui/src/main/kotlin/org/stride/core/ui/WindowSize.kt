package org.stride.core.ui

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass

/** Coarse width buckets used to switch layouts (phone / foldable / tablet). */
enum class WidthClass {
    Compact,
    Medium,
    Expanded
    ;

    companion object {
        /** Material breakpoints: < 600dp compact, < 840dp medium, otherwise expanded. */
        fun fromWidthDp(widthDp: Float): WidthClass = when {
            widthDp < 600f -> Compact
            widthDp < 840f -> Medium
            else -> Expanded
        }
    }
}

/** The current window's width class, recomputed on resize / fold / rotation. */
@Composable
fun currentWidthClass(): WidthClass {
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    return when {
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> WidthClass.Expanded
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> WidthClass.Medium
        else -> WidthClass.Compact
    }
}
