package org.stride.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.stride.app.ui.StrideApp
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.navigation.EntryProviderInstaller

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var entryProviderInstallers: Set<@JvmSuppressWildcards EntryProviderInstaller>

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 36 is always edge-to-edge (ANDF-010); Compose handles the insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            StrideTheme {
                StrideApp(installers = entryProviderInstallers)
            }
        }
    }
}
