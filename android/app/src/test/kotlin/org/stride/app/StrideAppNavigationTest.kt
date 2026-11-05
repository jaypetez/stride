package org.stride.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.stride.app.navigation.rememberStrideNavigationState
import org.stride.app.ui.StrideApp
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.navigation.ActivitiesRoute
import org.stride.core.navigation.ActivityDetailRoute
import org.stride.core.navigation.EntryProviderInstaller
import org.stride.core.navigation.FitnessRoute
import org.stride.core.navigation.LocalNavigator
import org.stride.core.navigation.PlanRoute
import org.stride.core.navigation.SettingsRoute
import org.stride.core.navigation.TodayRoute
import org.stride.core.testing.ScreenshotDefaults

/** The shell switches tabs, keeps each tab's stack and state, and restores after recreation (ANDF-009). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class StrideAppNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val installers: Set<EntryProviderInstaller> = setOf(
        {
            entry<TodayRoute> {
                var taps by rememberSaveable { mutableIntStateOf(0) }
                Column {
                    Text("Today screen")
                    Button(onClick = { taps++ }) { Text("Tapped $taps") }
                }
            }
        },
        {
            entry<ActivitiesRoute> {
                val navigator = LocalNavigator.current
                Column {
                    Text("Runs screen")
                    Button(onClick = { navigator.navigate(ActivityDetailRoute(42)) }) { Text("Open run") }
                }
            }
            entry<ActivityDetailRoute> { key -> Text("Run ${key.activityId}") }
        },
        { entry<FitnessRoute> { Text("Fitness screen") } },
        { entry<PlanRoute> { Text("Plan screen") } },
        { entry<SettingsRoute> { Text("You screen") } }
    )

    @Test
    fun startsOnTodayAndSwitchesTabs() {
        compose.setContent { StrideTheme { StrideApp(installers = installers) } }
        compose.onNodeWithText("Today screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fitness tab").performClick()
        compose.onNodeWithText("Fitness screen").assertIsDisplayed()
    }

    @Test
    fun tabsKeepTheirOwnBackStackAndState() {
        compose.setContent { StrideTheme { StrideApp(installers = installers) } }
        compose.onNodeWithText("Tapped 0").performClick()
        compose.onNodeWithText("Tapped 1").assertIsDisplayed()

        compose.onNodeWithContentDescription("Runs tab").performClick()
        compose.onNodeWithText("Open run").performClick()
        compose.onNodeWithText("Run 42").assertIsDisplayed()

        compose.onNodeWithContentDescription("Today tab").performClick()
        compose.onNodeWithText("Tapped 1").assertIsDisplayed()

        compose.onNodeWithContentDescription("Runs tab").performClick()
        compose.onNodeWithText("Run 42").assertIsDisplayed()
    }

    @Test
    fun backStackSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            StrideTheme { StrideApp(installers = installers, navigationState = rememberStrideNavigationState()) }
        }
        compose.onNodeWithContentDescription("Runs tab").performClick()
        compose.onNodeWithText("Open run").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Run 42").assertIsDisplayed()
    }

    @Test
    fun backPopsDetailThenReturnsToToday() {
        compose.setContent { StrideTheme { StrideApp(installers = installers) } }
        compose.onNodeWithContentDescription("Runs tab").performClick()
        compose.onNodeWithText("Open run").performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Runs screen").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Today screen").assertIsDisplayed()
    }
}
