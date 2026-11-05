package org.stride.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.stride.app.ui.StrideApp
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.navigation.ActivitiesRoute
import org.stride.core.navigation.ConnectGarminRoute
import org.stride.core.navigation.EntryProviderInstaller
import org.stride.core.navigation.FitnessRoute
import org.stride.core.navigation.LocalNavigator
import org.stride.core.navigation.OnboardingRoute
import org.stride.core.navigation.PlanRoute
import org.stride.core.navigation.SettingsRoute
import org.stride.core.navigation.TodayRoute
import org.stride.core.testing.ScreenshotDefaults

/** Onboarding is shown full screen until complete, then hands over to the tabbed shell (MVP-013). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ScreenshotDefaults.SDK], qualifiers = ScreenshotDefaults.PHONE)
class OnboardingGateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var onboarded by mutableStateOf(false)

    private val installers: Set<EntryProviderInstaller> = setOf(
        {
            entry<OnboardingRoute> {
                val navigator = LocalNavigator.current
                Button(onClick = {
                    navigator.resetTo(ConnectGarminRoute)
                    onboarded = true
                }) { Text("Finish onboarding") }
            }
            entry<ConnectGarminRoute> { Text("Connect screen") }
            entry<TodayRoute> { Text("Today screen") }
            entry<ActivitiesRoute> { Text("Runs screen") }
            entry<FitnessRoute> { Text("Fitness screen") }
            entry<PlanRoute> { Text("Plan screen") }
            entry<SettingsRoute> { Text("You screen") }
        }
    )

    @Test
    fun onboardingIsFullScreenThenHandsOverToTheShell() {
        compose.setContent { StrideTheme { StrideApp(installers = installers, onboardingComplete = onboarded) } }
        compose.onNodeWithText("Finish onboarding").assertIsDisplayed()
        compose.onNodeWithContentDescription("Today tab").assertDoesNotExist()

        compose.onNodeWithText("Finish onboarding").performClick()

        compose.onNodeWithText("Connect screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Today tab").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Today screen").assertIsDisplayed()
    }

    @Test
    fun wipingDataReturnsToOnboarding() {
        onboarded = true
        compose.setContent { StrideTheme { StrideApp(installers = installers, onboardingComplete = onboarded) } }
        compose.onNodeWithContentDescription("You tab").performClick()
        compose.onNodeWithText("You screen").assertIsDisplayed()
        onboarded = false
        compose.onNodeWithText("Finish onboarding").assertIsDisplayed()
        compose.onNodeWithContentDescription("You tab").assertDoesNotExist()
    }
}
