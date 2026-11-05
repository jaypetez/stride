package org.stride.feature.today

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.multibindings.IntoSet
import org.stride.core.navigation.EntryProviderInstaller
import org.stride.core.navigation.TodayRoute

@Module
@InstallIn(ActivityRetainedComponent::class)
object TodayEntries {
    @Provides
    @IntoSet
    fun today(): EntryProviderInstaller = {
        entry<TodayRoute> { TodayRoute() }
    }
}
