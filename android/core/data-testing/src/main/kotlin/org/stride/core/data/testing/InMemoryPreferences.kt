package org.stride.core.data.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A preferences DataStore held in memory. File-backed stores can fail on Windows
 * when a write's atomic rename races a concurrent read of the same file; tests
 * that write the same store from several coroutines use this instead.
 */
class InMemoryPreferences(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    private val lock = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = lock.withLock {
        transform(state.value).also { state.value = it }
    }
}
