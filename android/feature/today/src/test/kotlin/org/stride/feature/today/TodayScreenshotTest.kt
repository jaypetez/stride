package org.stride.feature.today

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.stride.core.data.sync.SyncFailure
import org.stride.core.data.sync.SyncProgress
import org.stride.core.data.testing.DemoDataFixture
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.testing.ScreenshotDefaults

/** Today in demo mode (MVP-014 / MVP-017), built from the real demo pipeline. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class TodayScreenshotTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun demoState(): TodayUiState {
        lateinit var state: TodayUiState
        runTest {
            val fx = DemoDataFixture(this, tmp.root)
            fx.loadDemo()
            state = TodayUiState(
                isLoading = false,
                content = todayContent(fx.insights.today(), fx.activities.observeActivities().first(), fx.wellness.observeLatest().first()),
                demoMode = true
            )
        }
        return state
    }

    private fun snap(name: String, state: TodayUiState) {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            captureRoboImage("src/test/screenshots/today_${name}_$theme.png") {
                StrideTheme(darkTheme = dark) { TodayScreen(state = state, onRefresh = {}, onOpenActivity = {}) }
            }
        }
    }

    @Test
    fun demo() = snap("demo", demoState())

    /** The whole scroll height, so the week and wellness sections are covered too. */
    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun demoFull() = snap("demo_full", demoState())

    @Test
    fun syncing() = snap("syncing", demoState().copy(sync = SyncProgress.Running("activities", 4, 12)))

    @Test
    fun empty() = snap("empty", TodayUiState(isLoading = false, sync = SyncProgress.Failed(SyncFailure.KEY_MISSING)))
}
