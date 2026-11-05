package org.stride.feature.onboarding

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.multibindings.IntoSet
import org.stride.core.navigation.EntryProviderInstaller
import org.stride.core.navigation.OnboardingRoute

@Module
@InstallIn(ActivityRetainedComponent::class)
object OnboardingEntries {
    @Provides
    @IntoSet
    fun onboarding(): EntryProviderInstaller = {
        entry<OnboardingRoute> { OnboardingFlow() }
    }
}
