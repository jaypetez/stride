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
import org.stride.core.navigation.ActivitiesRoute
import org.stride.core.navigation.EntryProviderInstaller
import org.stride.core.navigation.FitnessRoute
import org.stride.core.navigation.PlanRoute
import org.stride.core.navigation.SettingsRoute
import org.stride.core.navigation.TodayRoute

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
    fun today(): EntryProviderInstaller = {
        entry<TodayRoute> { ComingSoon("Today", "Your form, last run and wellness will appear here.") }
    }

    @Provides
    @IntoSet
    fun activities(): EntryProviderInstaller = {
        entry<ActivitiesRoute> { ComingSoon("Runs", "Your synced runs will appear here.") }
    }

    @Provides
    @IntoSet
    fun fitness(): EntryProviderInstaller = {
        entry<FitnessRoute> { ComingSoon("Fitness", "Fitness, fatigue and form charts will appear here.") }
    }

    @Provides
    @IntoSet
    fun settings(): EntryProviderInstaller = {
        entry<SettingsRoute> { ComingSoon("You", "Profile, data and settings will appear here.") }
    }

    @Provides
    @IntoSet
    fun plan(): EntryProviderInstaller = {
        entry<PlanRoute> { ComingSoon("Training plan", "A personal race plan is on its way.") }
    }
}

@Composable
internal fun ComingSoon(title: String, message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
        EmptyState(title = title, message = message)
    }
}
