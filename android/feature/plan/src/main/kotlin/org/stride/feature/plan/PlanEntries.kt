package org.stride.feature.plan

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.multibindings.IntoSet
import org.stride.core.navigation.EntryProviderInstaller
import org.stride.core.navigation.PlanRoute

@Module
@InstallIn(ActivityRetainedComponent::class)
object PlanEntries {
    @Provides
    @IntoSet
    fun plan(): EntryProviderInstaller = {
        entry<PlanRoute> { PlanRoute() }
    }
}
