package org.stride.core.data.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One lock for everything that writes synced data (AUD-026). The daily job and
 * "sync now" run as separate WorkManager jobs, so without this they could race
 * on the backfill cursor; the data eraser takes it so a running sync can never
 * write rows back after a wipe.
 */
@Singleton
class SyncLock @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
