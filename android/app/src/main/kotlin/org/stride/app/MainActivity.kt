package org.stride.app

import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.stride.app.ui.StrideApp
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.navigation.EntryProviderInstaller
import org.stride.core.ui.LocalIs24HourClock
import org.stride.core.ui.LocalUnitSystem

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var entryProviderInstallers: Set<@JvmSuppressWildcards EntryProviderInstaller>

    private val viewModel: MainActivityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash stays up until settings load (AUD-037), so neither a blank
        // screen nor onboarding flashes for returning users.
        installSplashScreen().setKeepOnScreenCondition { viewModel.keepSplashOnScreen }
        // targetSdk 36 is always edge-to-edge (ANDF-010); Compose handles the insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LaunchedEffect(viewModel) {
                viewModel.messages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
            }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            when (val current = state) {
                // Hidden behind the splash screen; kept as a themed surface for safety.
                MainUiState.Loading -> StrideTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) } }

                is MainUiState.Ready -> {
                    val settings = current.settings
                    StrideTheme(dynamicColor = settings.dynamicColor) {
                        // Times follow the device's 12/24-hour setting (AUD-034).
                        CompositionLocalProvider(
                            LocalUnitSystem provides settings.unitSystem,
                            LocalIs24HourClock provides DateFormat.is24HourFormat(this)
                        ) {
                            StrideApp(
                                installers = entryProviderInstallers,
                                onboardingComplete = settings.onboardingComplete,
                                demoMode = settings.demoMode,
                                onExitDemo = viewModel::exitDemo
                            )
                        }
                    }
                }
            }
        }
    }
}
