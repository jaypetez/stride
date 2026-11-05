package org.stride.core.data.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.stride.core.datastore.UserPreferences

/**
 * A preferences store whose reads fail the first [failures] times with an
 * [IOException] (a corrupt or unreadable file), then behave like [delegate].
 * Every screen reads settings, so this drives each ViewModel's error state (AUD-032).
 */
class FlakyDataStore(private val delegate: DataStore<Preferences>, var failures: Int = 1) : DataStore<Preferences> {
    var reads = 0
        private set

    override val data: Flow<Preferences> = flow {
        reads++
        if (failures > 0) {
            failures--
            throw IOException("preferences unreadable")
        }
        emitAll(delegate.data)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = delegate.updateData(transform)
}

/** [UserPreferences] over a [FlakyDataStore] wrapping [delegate]. */
fun flakyPreferences(delegate: DataStore<Preferences>, failures: Int = 1): Pair<UserPreferences, FlakyDataStore> {
    val store = FlakyDataStore(delegate, failures)
    return UserPreferences(store) to store
}
