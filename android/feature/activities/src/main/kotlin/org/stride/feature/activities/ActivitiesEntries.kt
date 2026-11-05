package org.stride.feature.activities

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.multibindings.IntoSet
import org.stride.core.navigation.ActivitiesRoute
import org.stride.core.navigation.ActivityDetailRoute
import org.stride.core.navigation.EntryProviderInstaller

@Module
@InstallIn(ActivityRetainedComponent::class)
object ActivitiesEntries {
    @Provides
    @IntoSet
    fun activities(): EntryProviderInstaller = {
        entry<ActivitiesRoute> { ActivitiesRoute() }
        entry<ActivityDetailRoute> { key -> ActivityDetailRoute(activityId = key.activityId) }
    }
}
