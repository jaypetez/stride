package org.stride.app.navigation

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.multibindings.Multibinds
import org.stride.core.navigation.EntryProviderInstaller

/** Declares the (possibly empty) set of screens contributed by feature modules. */
@Module
@InstallIn(ActivityRetainedComponent::class)
abstract class NavigationModule {
    @Multibinds
    abstract fun entryProviderInstallers(): Set<@JvmSuppressWildcards EntryProviderInstaller>
}
