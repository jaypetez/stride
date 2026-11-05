package org.stride.core.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update

/**
 * A screen's data stream that survives errors (AUD-032): an exception from the
 * repositories becomes a state built by `onError` instead of crashing the
 * collector, and [retry] subscribes to the upstream again.
 */
class Retryable {
    private val attempts = MutableStateFlow(0)

    /** Re-subscribes every flow built with [flow]. */
    fun retry() = attempts.update { it + 1 }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> flow(onError: (Throwable) -> T, upstream: () -> Flow<T>): Flow<T> =
        attempts.flatMapLatest { upstream().catch { error -> emit(onError(error)) } }
}
