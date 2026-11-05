package org.stride.core.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.stride.core.data.metrics.MetricsRecomputer
import org.stride.core.data.metrics.ScienceMetricsRecomputer
import org.stride.core.data.sync.SyncController
import org.stride.core.data.sync.SyncManager

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun recomputer(impl: ScienceMetricsRecomputer): MetricsRecomputer

    @Binds
    abstract fun syncController(impl: SyncManager): SyncController
}
