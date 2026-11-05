package org.stride.core.common

import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** AUD-030: a failure in the application scope is logged, not fatal. */
@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationScopeTest {
    @Test
    fun failureIsLoggedAndScopeSurvives() = runTest {
        val logged = CopyOnWriteArrayList<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                logged += record.message.orEmpty()
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        val logger = Logger.getLogger("Stride")
        logger.addHandler(handler)
        try {
            val scope = applicationScope(UnconfinedTestDispatcher(testScheduler))
            // Without the handler this would reach the thread's uncaught-exception handler and kill the app.
            scope.launch { error("recompute exploded") }.join()
            assertTrue(scope.isActive, "a failed child must not cancel the app scope")
            var ranAfter = false
            scope.launch { ranAfter = true }.join()
            assertTrue(ranAfter)
            assertTrue(logged.any { "background work failed" in it && "IllegalStateException" in it }, "logged: $logged")
        } finally {
            logger.removeHandler(handler)
        }
    }
}
