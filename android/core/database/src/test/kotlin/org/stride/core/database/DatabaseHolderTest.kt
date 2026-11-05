package org.stride.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AUD-030: switching demo mode never closes a database under a running query. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseHolderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<StrideDatabase>()

    private fun factory(): (Boolean) -> StrideDatabase = {
        Room.inMemoryDatabaseBuilder(context, StrideDatabase::class.java).allowMainThreadQueries().build().also { opened += it }
    }

    @Test
    fun replacedDatabaseStaysUsableUntilTheSwitchSettles() = runTest {
        // The close timer runs on its own virtual clock, so it only moves when the test says so
        // (runTest would otherwise skip ahead while a Room query waits on a real thread).
        val closer = TestScope()
        val holder = DatabaseHolder(factory(), closeScope = closer, retireDelayMs = 1_000)
        val real = holder.db
        assertEquals(0, real.activityDao().count()) // Room opens lazily: open it like a running screen would

        holder.switchTo(true)
        assertNotSame(real, holder.db)
        assertTrue(holder.isDemo)
        // A query that grabbed the old database just before the switch still runs.
        assertTrue(real.isOpen)
        assertEquals(0, real.activityDao().count())

        closer.testScheduler.advanceTimeBy(999)
        closer.testScheduler.runCurrent()
        assertTrue(real.isOpen)

        closer.testScheduler.advanceTimeBy(2)
        closer.testScheduler.runCurrent()
        holder.awaitRetired()
        assertFalse(real.isOpen, "closed once the switch has settled")
        assertEquals(0, holder.db.activityDao().count(), "the new database works")
        opened.forEach { it.close() }
    }

    @Test
    fun withoutCloseScopeTheOldDatabaseClosesAtOnce() = runTest {
        val holder = DatabaseHolder(factory())
        val real = holder.db
        holder.switchTo(true)
        assertFalse(real.isOpen)
        holder.awaitRetired()
        holder.db.close()
    }
}
