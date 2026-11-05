package org.stride.core.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.checkRoboAccessibility
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset

/** How a screenshot variant differs from the default phone render (AUD-035). */
enum class ScreenshotVariant(val suffix: String, val fontScale: Float, val layoutDirection: LayoutDirection) {
    DEFAULT("", 1f, LayoutDirection.Ltr),

    /** Large text (Settings → Display → Font size, 150 %). */
    FONT_150("_font150", 1.5f, LayoutDirection.Ltr),

    /** Largest text Android offers (200 %). */
    FONT_200("_font200", 2f, LayoutDirection.Ltr),

    /** Right-to-left layout (Arabic, Hebrew, …); strings stay English so mirroring is easy to review. */
    RTL("_rtl", 1f, LayoutDirection.Rtl)
}

/**
 * Renders [content] and captures it with Roborazzi on a **paused** Compose
 * clock. Infinite animations (progress spinners, pull-to-refresh) otherwise
 * keep Compose from ever going idle and the capture hangs; pausing the clock
 * also makes animated frames deterministic (docs/testing/screenshots.md).
 *
 * [variant] applies a font scale or RTL layout (AUD-035). With
 * [accessibilityChecks] the render also runs through the Accessibility Test
 * Framework ([checkAccessibility]) and fails on any error.
 */
@OptIn(ExperimentalTestApi::class)
fun captureScreenshot(
    filePath: String,
    advanceTimeMs: Long = 1_000,
    variant: ScreenshotVariant = ScreenshotVariant.DEFAULT,
    accessibilityChecks: Boolean = false,
    content: @Composable () -> Unit
) = runComposeUiTest {
    mainClock.autoAdvance = false
    setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, variant.fontScale * density.fontScale),
            LocalLayoutDirection provides variant.layoutDirection,
            content = content
        )
    }
    mainClock.advanceTimeBy(advanceTimeMs)
    // Dialogs and popups add roots of their own; check each window.
    if (accessibilityChecks) onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { onAllNodes(isRoot())[it].checkAccessibility() }
    onRoot().captureRoboImage(filePath)
}

/** The file path for [variant]: `today_demo` → `today_demo_font150_light.png`. */
fun variantPath(base: String, variant: ScreenshotVariant, theme: String): String = "${base}${variant.suffix}_$theme.png"

/**
 * Runs Google's Accessibility Test Framework (the checks behind Compose's
 * `enableAccessibilityChecks()`: touch-target size, text contrast, missing or
 * duplicate labels, …) on the rendered tree and fails on any error (AUD-035).
 * Compose's own `enableAccessibilityChecks()` is a no-op under Robolectric, so
 * the JVM tests go through Roborazzi's ATF integration, which is.
 */
@OptIn(ExperimentalRoborazziApi::class)
fun SemanticsNodeInteraction.checkAccessibility() = checkRoboAccessibility(
    RoborazziATFAccessibilityCheckOptions(
        RoborazziATFAccessibilityChecker(AccessibilityCheckPreset.LATEST),
        RoborazziATFAccessibilityChecker.CheckLevel.Error
    )
)
