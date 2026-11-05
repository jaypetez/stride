package org.stride.feature.fitness

import androidx.compose.runtime.Composable
import java.time.LocalDate
import kotlin.math.sin
import org.stride.core.data.insights.FitnessInsights
import org.stride.core.data.insights.RacePrediction
import org.stride.core.data.insights.VdotPoint
import org.stride.core.data.insights.WeeklyZones
import org.stride.core.designsystem.component.ThemePreviews
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.model.DailyLoadPoint
import org.stride.core.science.model.HrZone
import org.stride.core.science.model.ZoneDistribution

/** Hand-written sample state for IDE previews (screenshot tests use the real demo pipeline). */
internal object FitnessSamples {
    private val start = LocalDate.parse("2026-08-03")
    private val pmc = (0 until 91).map { d ->
        val ctl = 35 + d * 0.15
        val atl = ctl + 8 * sin(d / 3.5)
        val acwr = 1 + 0.25 * sin(d / 6.0)
        DailyLoadPoint(start.plusDays(d.toLong()).toString(), load = 50.0, ctl = ctl, atl = atl, tsb = ctl - atl, acwr = acwr)
    }
    val state = FitnessUiState(
        isLoading = false,
        window = pmc,
        insights = FitnessInsights(
            pmc = pmc,
            weeklyZones = (0 until 8).map {
                WeeklyZones(start.plusWeeks(it.toLong()).toString(), ZoneDistribution(0.0, 0.0, 0.0, 78.0, 12.0, 10.0))
            },
            vdotTrend = (0 until 8).map { VdotPoint(start.plusWeeks(it.toLong()).toString(), 44.0 + it * 0.3) },
            currentVdot = 46.1,
            predictions = listOf(RacePrediction("5K", 5_000.0, 1_290.0), RacePrediction("10K", 10_000.0, 2_680.0)),
            hrZones = listOf(HrZone(1, "Recovery", 0.0, 135.0), HrZone(2, "Aerobic", 135.0, 150.0)),
            paceZones = emptyList(),
            rampRatePerWeek = 1.1
        )
    )
}

@ThemePreviews
@Composable
private fun FitnessPreview() {
    StrideTheme { FitnessScreen(state = FitnessSamples.state, onSelectRange = {}) }
}
