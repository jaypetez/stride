package org.stride.core.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.stride.core.data.demo.DemoController
import org.stride.core.data.demo.DemoMode
import org.stride.core.data.local.DataEraser
import org.stride.core.data.local.RoomDataEraser
import org.stride.core.data.metrics.MetricsRecomputer
import org.stride.core.data.metrics.ScienceMetricsRecomputer
import org.stride.core.data.sync.SyncController
import org.stride.core.data.sync.SyncManager
import org.stride.core.data.sync.SyncScheduler

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun recomputer(impl: ScienceMetricsRecomputer): MetricsRecomputer

    @Binds
    abstract fun syncController(impl: SyncManager): SyncController

    @Binds
    abstract fun syncScheduler(impl: SyncManager): SyncScheduler

    @Binds
    abstract fun demoController(impl: DemoMode): DemoController

    @Binds
    abstract fun dataEraser(impl: RoomDataEraser): DataEraser
}
