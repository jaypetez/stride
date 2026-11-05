package org.stride.feature.connect

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.multibindings.IntoSet
import org.stride.core.navigation.ConnectGarminRoute
import org.stride.core.navigation.EntryProviderInstaller

@Module
@InstallIn(ActivityRetainedComponent::class)
object ConnectEntries {
    @Provides
    @IntoSet
    fun connect(): EntryProviderInstaller = {
        entry<ConnectGarminRoute> { ConnectGarminFlow() }
    }
}
