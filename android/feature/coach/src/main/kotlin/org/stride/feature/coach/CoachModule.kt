package org.stride.feature.coach

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import org.stride.core.common.Dispatcher
import org.stride.core.common.StrideDispatchers
import org.stride.core.navigation.CoachRoute
import org.stride.core.navigation.EntryProviderInstaller

@Module
@InstallIn(ActivityRetainedComponent::class)
object CoachEntries {
    @Provides
    @IntoSet
    fun coach(): EntryProviderInstaller = {
        entry<CoachRoute> { key -> CoachFlow(activityId = key.activityId) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object CoachModule {
    @Provides
    @Singleton
    fun coachLlm(http: OkHttpClient, @Dispatcher(StrideDispatchers.IO) io: CoroutineDispatcher): CoachLlm = OkHttpAnthropicClient(http, io)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CoachBindings {
    @Binds
    abstract fun factsSource(impl: RepositoryCoachFactsSource): CoachFactsSource
}
