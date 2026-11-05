package org.stride.core.testing

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import com.github.takahirom.roborazzi.captureRoboImage

/**
 * Renders [content] and captures it with Roborazzi on a **paused** Compose
 * clock. Infinite animations (progress spinners, pull-to-refresh) otherwise
 * keep Compose from ever going idle and the capture hangs; pausing the clock
 * also makes animated frames deterministic (docs/testing/screenshots.md).
 */
@OptIn(ExperimentalTestApi::class)
fun captureScreenshot(filePath: String, advanceTimeMs: Long = 1_000, content: @Composable () -> Unit) = runComposeUiTest {
    mainClock.autoAdvance = false
    setContent(content)
    mainClock.advanceTimeBy(advanceTimeMs)
    onRoot().captureRoboImage(filePath)
}
