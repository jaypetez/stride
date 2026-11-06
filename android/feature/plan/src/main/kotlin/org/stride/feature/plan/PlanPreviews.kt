package org.stride.feature.plan

import androidx.compose.runtime.Composable
import java.time.LocalDate
import org.stride.core.designsystem.component.ThemePreviews
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.science.model.AthleteProfile
import org.stride.core.science.model.PlanKind
import org.stride.core.science.model.RaceDistance
import org.stride.core.science.model.RaceGoal
import org.stride.core.science.plan.FitnessSeed
import org.stride.core.science.plan.PlanSkeletonParams
import org.stride.core.science.plan.buildPlanSkeleton

private val previewPlan = buildPlanSkeleton(
    PlanSkeletonParams(
        profile = AthleteProfile(),
        goal = RaceGoal(distance = RaceDistance.HALF, date = "2027-01-31", goalTimeSec = 6_300.0),
        weeks = 12,
        startDate = "2026-11-09",
        planId = "preview",
        createdAt = "2026-11-05T07:00:00Z"
    )
)

@ThemePreviews
@Composable
private fun PlanPreview() {
    StrideTheme {
        PlanScreen(
            state = PlanUiState(
                isLoading = false,
                today = "2026-11-11",
                plan = previewPlan,
                currentWeekIndex = 0,
                form = PlanForm(raceDate = LocalDate.parse("2027-01-31")),
                earliestRaceDate = LocalDate.parse("2026-11-15")
            ),
            actions = PlanActions()
        )
    }
}

@ThemePreviews
@Composable
private fun PlanFormPreview() {
    StrideTheme {
        PlanScreen(
            state = PlanUiState(
                isLoading = false,
                today = "2026-11-05",
                form = PlanForm(raceDate = LocalDate.parse("2027-01-31"), goalTime = "1:45:00"),
                earliestRaceDate = LocalDate.parse("2026-11-15")
            ),
            actions = PlanActions()
        )
    }
}

@ThemePreviews
@Composable
private fun PlanBuildFormPreview() {
    StrideTheme {
        PlanScreen(
            state = PlanUiState(
                isLoading = false,
                today = "2026-11-05",
                form = PlanForm(raceDate = LocalDate.parse("2027-01-31"), kind = PlanKind.BUILD, buildWeeks = 8),
                earliestRaceDate = LocalDate.parse("2026-11-15"),
                seed = FitnessSeed(weeklyMinutes = 210.0, longestRunMin = 75.0, vdot = 45.0, runCount = 14)
            ),
            actions = PlanActions()
        )
    }
}
