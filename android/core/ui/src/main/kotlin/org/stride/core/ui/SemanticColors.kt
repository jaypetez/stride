package org.stride.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.model.FormBand

/** The semantic colour of a form (TSB) band. */
@Composable
fun FormBand.color(): Color = when (this) {
    FormBand.FRESH -> StrideTheme.colors.fresh
    FormBand.NEUTRAL -> StrideTheme.colors.neutral
    FormBand.PRODUCTIVE -> StrideTheme.colors.productive
    FormBand.OVERREACHING -> StrideTheme.colors.overreaching
}
