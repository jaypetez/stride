package org.stride.feature.fitness

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.multibindings.IntoSet
import org.stride.core.navigation.EntryProviderInstaller
import org.stride.core.navigation.FitnessRoute

@Module
@InstallIn(ActivityRetainedComponent::class)
object FitnessEntries {
    @Provides
    @IntoSet
    fun fitness(): EntryProviderInstaller = {
        entry<FitnessRoute> { FitnessRoute() }
    }
}
