package org.stride.core.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.stride.core.data.metrics.MetricsRecomputer
import org.stride.core.data.metrics.ScienceMetricsRecomputer

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun recomputer(impl: ScienceMetricsRecomputer): MetricsRecomputer
}
