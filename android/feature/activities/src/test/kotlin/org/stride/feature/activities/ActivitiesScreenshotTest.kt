package org.stride.feature.activities

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.stride.core.data.insights.zoneShares
import org.stride.core.data.testing.DemoDataFixture
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.model.DataSource
import org.stride.core.testing.ScreenshotDefaults
import org.stride.core.testing.ScreenshotVariant
import org.stride.core.testing.captureScreenshot
import org.stride.core.testing.variantPath

/** Runs list and detail in demo mode (MVP-015), built from the real demo pipeline. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class ActivitiesScreenshotTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun demoList(): ActivitiesUiState {
        lateinit var state: ActivitiesUiState
        runTest {
            val fx = DemoDataFixture(this, tmp.root)
            fx.loadDemo()
            state = ActivitiesUiState(isLoading = false, months = groupByMonth(fx.activities.observeActivities().first()))
        }
        return state
    }

    private fun demoDetail(): ActivityDetailUiState {
        lateinit var state: ActivityDetailUiState
        runTest {
            val fx = DemoDataFixture(this, tmp.root)
            fx.loadDemo()
            val id = fx.activities.observeActivities().first().first { it.activity.hasStreams && it.activity.sport.isRun }.activity.id
            val detail = fx.activities.observeDetail(id).first()!!
            state = ActivityDetailUiState(
                isLoading = false,
                detail = detail,
                charts = detail.streams?.let(::streamCharts),
                zoneShares = detail.metrics?.hrZoneSeconds?.let(::zoneShares).orEmpty()
            )
        }
        return state
    }

    private fun snap(
        name: String,
        variant: ScreenshotVariant = ScreenshotVariant.DEFAULT,
        content: @androidx.compose.runtime.Composable () -> Unit
    ) {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            captureScreenshot(
                variantPath("src/test/screenshots/activities_$name", variant, theme),
                variant = variant,
                accessibilityChecks = !dark
            ) {
                StrideTheme(darkTheme = dark) { content() }
            }
        }
    }

    @Test
    fun list() {
        val state = demoList()
        snap("list") { ActivitiesScreen(state = state, onOpenActivity = {}) }
    }

    @Test
    fun detail() {
        val state = demoDetail()
        snap("detail") { ActivityDetailScreen(state = state, onBack = {}, onAskCoach = {}) }
    }

    /** Full scroll height of a Garmin-sourced run, so splits, efficiency and the attribution are covered. */
    @Test
    @Config(qualifiers = "en-rGB-w411dp-h2600dp-xxhdpi")
    fun detailFullGarmin() {
        val demo = demoDetail()
        val detail = demo.detail!!
        val state = demo.copy(detail = detail.copy(activity = detail.activity.copy(source = DataSource.GARMIN)))
        snap("detail_full") { ActivityDetailScreen(state = state, onBack = {}, onAskCoach = {}) }
    }

    /** AUD-035: large text and right-to-left. */
    @Test
    fun listFont200() {
        val state = demoList()
        snap("list", ScreenshotVariant.FONT_200) { ActivitiesScreen(state = state, onOpenActivity = {}) }
    }

    @Test
    fun detailFont150() {
        val state = demoDetail()
        snap("detail", ScreenshotVariant.FONT_150) { ActivityDetailScreen(state = state, onBack = {}, onAskCoach = {}) }
    }

    @Test
    fun detailRtl() {
        val state = demoDetail()
        snap("detail", ScreenshotVariant.RTL) { ActivityDetailScreen(state = state, onBack = {}, onAskCoach = {}) }
    }

    /** AUD-032: the runs could not be read. */
    @Test
    fun listError() = snap("list_error") {
        ActivitiesScreen(state = ActivitiesUiState(isLoading = false, loadFailed = true), onOpenActivity = {})
    }

    @Test
    fun detailError() = snap("detail_error") {
        ActivityDetailScreen(state = ActivityDetailUiState(isLoading = false, loadFailed = true), onBack = {}, onAskCoach = {})
    }

    @Test
    fun empty() = snap("empty") { ActivitiesScreen(state = ActivitiesUiState(isLoading = false), onOpenActivity = {}) }
}
