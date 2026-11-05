package org.stride.core.common

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Injected dispatchers: repositories never hard-code `Dispatchers.IO` (ANDF-006). */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: StrideDispatchers)

enum class StrideDispatchers {
    Default,
    IO
}

/** The application-wide scope for work that must outlive a screen. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides
    @Dispatcher(StrideDispatchers.IO)
    fun providesIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(StrideDispatchers.Default)
    fun providesDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}

@Module
@InstallIn(SingletonComponent::class)
object CoroutineScopesModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun providesApplicationScope(
        @Dispatcher(StrideDispatchers.Default) dispatcher: CoroutineDispatcher
    ): CoroutineScope = applicationScope(dispatcher)
}

/**
 * Logs (instead of crashing the process) any failure that escapes a coroutine in the
 * application scope, e.g. a recompute started from Settings or startup work (AUD-030).
 */
val AppScopeExceptionHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, error ->
    StrideLog.w("AppScope", "background work failed", error)
}

/** The application-wide scope: a supervisor (one failure doesn't cancel siblings) plus [AppScopeExceptionHandler]. */
fun applicationScope(dispatcher: CoroutineDispatcher): CoroutineScope =
    CoroutineScope(SupervisorJob() + dispatcher + AppScopeExceptionHandler)
