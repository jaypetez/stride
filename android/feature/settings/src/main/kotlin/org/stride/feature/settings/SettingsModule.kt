package org.stride.feature.settings

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.multibindings.IntoSet
import org.stride.core.navigation.EntryProviderInstaller
import org.stride.core.navigation.LicencesRoute
import org.stride.core.navigation.ProfileEditRoute
import org.stride.core.navigation.SettingsRoute

@Module
@InstallIn(ActivityRetainedComponent::class)
object SettingsEntries {
    @Provides
    @IntoSet
    fun settings(): EntryProviderInstaller = {
        entry<SettingsRoute> { SettingsFlow() }
        entry<ProfileEditRoute> { ProfileEditFlow() }
        entry<LicencesRoute> { LicencesFlow() }
    }
}
