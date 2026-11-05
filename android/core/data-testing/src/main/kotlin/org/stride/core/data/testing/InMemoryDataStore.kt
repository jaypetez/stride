package org.stride.core.data.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A file-free Preferences DataStore for ViewModel tests: deterministic, and free
 * of the temp-file renames that DataStore does on every write. Reads wait for
 * [ready] so a test can observe the "still loading" state.
 */
class InMemoryDataStore(startReady: Boolean = true) : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()
    val ready = CompletableDeferred<Unit>().apply { if (startReady) complete(Unit) }

    override val data: Flow<Preferences> = flow {
        ready.await()
        emitAll(state)
    }

    /** When set, every write throws it, to exercise persistence-failure paths. */
    var failWrites: Throwable? = null

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = mutex.withLock {
        failWrites?.let { throw it }
        transform(state.value).also { state.value = it }
    }
}
