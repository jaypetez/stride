package org.stride.core.common

import app.cash.turbine.test
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

class RetryableTest {
    @Test
    fun errorsBecomeStateAndRetryResubscribes() = runTest {
        var calls = 0
        val retryable = Retryable()
        val states = retryable.flow(onError = { "error: ${it.message}" }) {
            flow {
                calls++
                if (calls == 1) throw IOException("disk")
                emit("loaded")
            }
        }
        states.test {
            assertEquals("error: disk", awaitItem())
            retryable.retry()
            assertEquals("loaded", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, calls)
    }
}
