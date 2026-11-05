package org.stride.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * Shared edge-to-edge helpers (ANDF-010). targetSdk 36 always draws behind the
 * system bars, so screens apply exactly one of these instead of hand-rolling insets.
 */

/** For forms: keeps the focused field above the keyboard. */
fun Modifier.formInsets(): Modifier = imePadding()

/** For full-screen flows without a scaffold (onboarding): avoids bars, cutouts and the IME. */
fun Modifier.fullScreenInsets(): Modifier = safeDrawingPadding()

/** Content padding for a scrolling list that should scroll under the status bar but end above the IME. */
@Composable
fun listContentPadding(extraBottom: Int = 24): PaddingValues {
    val ime = WindowInsets.ime.asPaddingValues()
    return PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = 8.dp,
        bottom = ime.calculateBottomPadding() + extraBottom.dp
    )
}
