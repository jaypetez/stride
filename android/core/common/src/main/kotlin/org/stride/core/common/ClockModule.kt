package org.stride.core.common

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ClockModule {
    @Provides
    @Singleton
    fun clock(): StrideClock = SystemStrideClock()
}
