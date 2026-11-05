package org.stride.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.stride.app.ui.StrideApp
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.navigation.EntryProviderInstaller
import org.stride.core.ui.LocalUnitSystem

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var entryProviderInstallers: Set<@JvmSuppressWildcards EntryProviderInstaller>

    private val viewModel: MainActivityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 36 is always edge-to-edge (ANDF-010); Compose handles the insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            when (val current = state) {
                // A blank themed surface until settings load, so onboarding never flashes for returning users.
                MainUiState.Loading -> StrideTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) } }

                is MainUiState.Ready -> {
                    val settings = current.settings
                    StrideTheme(dynamicColor = settings.dynamicColor) {
                        CompositionLocalProvider(LocalUnitSystem provides settings.unitSystem) {
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
