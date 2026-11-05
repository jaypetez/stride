package org.stride.core.database

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the active database: the real one or the demo one. Repositories observe
 * [current] so every Flow switches over when demo mode is entered or exited.
 *
 * The database being replaced is closed only after [retireDelayMs] (AUD-030): a query
 * that read [db] just before the switch can still finish on it instead of crashing on a
 * closed database. With no [closeScope] (tests) it is closed at once. Callers that delete a
 * database file call [awaitRetired] first, so a file is never deleted under an open connection.
 */
class DatabaseHolder(
    private val factory: (demo: Boolean) -> StrideDatabase,
    initialDemo: Boolean = false,
    private val closeScope: CoroutineScope? = null,
    private val retireDelayMs: Long = RETIRE_DELAY_MS
) {
    private val state = MutableStateFlow(factory(initialDemo))
    private var demo = initialDemo
    private val retiring = mutableListOf<Job>()

    val current: StateFlow<StrideDatabase> = state.asStateFlow()

    val isDemo: Boolean get() = demo

    val db: StrideDatabase get() = state.value

    @Synchronized
    fun switchTo(demoMode: Boolean) {
        if (demoMode == demo) return
        val old = state.value
        demo = demoMode
        state.value = factory(demoMode)
        retire(old)
    }

    private fun retire(old: StrideDatabase) {
        val scope = closeScope ?: return old.close()
        val job = scope.launch {
            delay(retireDelayMs)
            old.close()
        }
        retiring += job
        job.invokeOnCompletion { synchronized(this) { retiring -= job } }
    }

    /** Suspends until every replaced database is closed (call before deleting a database file). */
    suspend fun awaitRetired() {
        val jobs = synchronized(this) { retiring.toList() }
        jobs.forEach { it.join() }
    }

    companion object {
        /** Long enough for an in-flight query or recompute step to finish on the old database. */
        const val RETIRE_DELAY_MS = 2_000L

        fun forContext(context: Context, initialDemo: Boolean, closeScope: CoroutineScope? = null): DatabaseHolder = DatabaseHolder(
            factory = { demo ->
                Room.databaseBuilder(context, StrideDatabase::class.java, if (demo) StrideDatabase.DEMO_NAME else StrideDatabase.NAME)
                    .build()
            },
            initialDemo = initialDemo,
            closeScope = closeScope
        )
    }
}
