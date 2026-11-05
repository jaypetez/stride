package org.stride.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.multibindings.IntoSet
import org.stride.core.designsystem.component.EmptyState
import org.stride.core.navigation.EntryProviderInstaller
import org.stride.core.navigation.SettingsRoute

/**
 * Temporary entries for top-level destinations whose feature module has not
 * landed yet. Each feature PR deletes its line here when it contributes the real
 * screen.
 */
@Module
@InstallIn(ActivityRetainedComponent::class)
object PlaceholderEntries {
    @Provides
    @IntoSet
    fun settings(): EntryProviderInstaller = {
        entry<SettingsRoute> { ComingSoon("You", "Profile, data and settings will appear here.") }
    }
}

@Composable
internal fun ComingSoon(title: String, message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
        EmptyState(title = title, message = message)
    }
}
