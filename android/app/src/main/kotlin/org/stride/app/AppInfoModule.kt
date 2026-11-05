package org.stride.app

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.stride.core.common.AppInfo

@Module
@InstallIn(SingletonComponent::class)
object AppInfoModule {
    @Provides
    fun appInfo(): AppInfo = object : AppInfo {
        override val versionName: String = BuildConfig.VERSION_NAME
    }
}
