package org.stride.feature.plan

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.stride.core.data.plan.PlanRequest
import org.stride.core.data.plan.PlanResult
import org.stride.core.data.testing.DemoDataFixture
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.science.model.GuardrailViolation
import org.stride.core.science.model.RaceDistance
import org.stride.core.science.plan.validatePlan
import org.stride.core.testing.ScreenshotDefaults
import org.stride.core.testing.ScreenshotVariant
import org.stride.core.testing.captureScreenshot
import org.stride.core.testing.variantPath

/** Plan tab with a plan generated for the demo athlete (MVP-018). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class PlanScreenshotTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val form = PlanForm(distance = RaceDistance.HALF, raceDate = LocalDate.parse("2027-02-28"), goalTime = "1:50:00")

    private fun generatedState(): PlanUiState {
        lateinit var state: PlanUiState
        runTest {
            val fx = DemoDataFixture(this, tmp.root)
            fx.loadDemo()
            val result = fx.plans.create(PlanRequest(RaceDistance.HALF, LocalDate.parse("2027-02-28"), 6_600.0))
            val plan = assertIs<PlanResult.Created>(result).plan
            assertTrue(validatePlan(plan, fx.plans.guardrailContext()).valid)
            // Pretend it is Wednesday of week 3 so "today" is highlighted and scrolled into view.
            state = PlanUiState(
                isLoading = false,
                today = plan.weeks[2].days[2].date!!,
                plan = plan,
                currentWeekIndex = 2,
                form = form,
                earliestRaceDate = LocalDate.parse("2026-11-08"),
                repairs = result.repairs
            )
        }
        return state
    }

    private fun snap(name: String, state: PlanUiState, variant: ScreenshotVariant = ScreenshotVariant.DEFAULT) {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            captureScreenshot(
                variantPath("src/test/screenshots/plan_$name", variant, theme),
                variant = variant,
                accessibilityChecks = !dark
            ) {
                StrideTheme(darkTheme = dark) { PlanScreen(state = state, actions = PlanActions()) }
            }
        }
    }

    @Test
    fun generated() = snap("generated", generatedState())

    @Test
    fun form() = snap(
        "form",
        PlanUiState(isLoading = false, today = "2026-11-01", form = form, earliestRaceDate = LocalDate.parse("2026-11-08"))
    )

    /** AUD-032: the plan could not be read. */
    @Test
    fun error() = snap(
        "error",
        PlanUiState(
            isLoading = false,
            today = "2026-11-01",
            form = form,
            earliestRaceDate = LocalDate.parse("2026-11-02"),
            loadFailed = true
        )
    )

    /** AUD-035: large text and right-to-left. */
    @Test
    fun generatedFont150() = snap("generated", generatedState(), ScreenshotVariant.FONT_150)

    @Test
    fun formFont200() = snap(
        "form",
        PlanUiState(isLoading = false, today = "2026-11-01", form = form, earliestRaceDate = LocalDate.parse("2026-11-02")),
        ScreenshotVariant.FONT_200
    )

    @Test
    fun generatedRtl() = snap("generated", generatedState(), ScreenshotVariant.RTL)

    @Test
    fun rejected() = snap(
        "rejected",
        PlanUiState(
            isLoading = false,
            today = "2026-11-01",
            form = form,
            earliestRaceDate = LocalDate.parse("2026-11-08"),
            violations = listOf(GuardrailViolation("ramp", "CTL would rise 9 pts (cap 7).", "error", 3))
        )
    )
}
