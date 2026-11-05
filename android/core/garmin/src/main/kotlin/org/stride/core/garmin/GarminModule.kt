package org.stride.core.garmin

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import org.stride.core.common.Dispatcher
import org.stride.core.common.StrideClock
import org.stride.core.common.StrideDispatchers

/**
 * Provides the Garmin client. [GarminKeyProvider] and [TokenCache] are bound by
 * core:datastore (encrypted with Tink); there is deliberately no HTTP logging
 * interceptor, so request headers can never reach logcat.
 */
@Module
@InstallIn(SingletonComponent::class)
object GarminModule {
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun garminClient(
        http: OkHttpClient,
        keyProvider: GarminKeyProvider,
        tokenCache: TokenCache,
        clock: StrideClock,
        @Dispatcher(StrideDispatchers.IO) io: CoroutineDispatcher
    ): GarminClient = OkHttpGarminClient(http, keyProvider, tokenCache, clock, io)
}
