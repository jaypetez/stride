package org.stride.core.data

import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.stride.core.data.insights.InsightsRepository
import org.stride.core.data.insights.settledRecompute

/**
 * AUD-036: a sync writes rows in bursts and Room re-emits on every write. The
 * fake DAO flow below stands in for those emissions; insights must recompute once
 * for the first emission and then at most once per settled burst.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettledRecomputeTest {
    @Test
    fun recomputesOncePerSettledBurst() = runTest {
        val fakeDaoEmissions = MutableSharedFlow<Unit>(replay = 1)
        var computes = 0
        val results = mutableListOf<Int>()
        val job = launch {
            settledRecompute(fakeDaoEmissions, InsightsRepository.SETTLE_MS) { ++computes }.toList(results)
        }
        fakeDaoEmissions.emit(Unit)
        runCurrent()
        assertEquals(1, computes, "the first emission recomputes immediately")

        // A sync burst: 40 writes, 20 ms apart.
        repeat(40) {
            fakeDaoEmissions.emit(Unit)
            delay(20)
        }
        assertEquals(1, computes, "nothing recomputes while the burst is still going")
        advanceTimeBy(InsightsRepository.SETTLE_MS + 1)
        runCurrent()
        assertEquals(2, computes, "one recompute once the burst settles")

        // A second, separate burst.
        repeat(5) { fakeDaoEmissions.emit(Unit) }
        advanceTimeBy(InsightsRepository.SETTLE_MS + 1)
        runCurrent()
        assertEquals(3, computes)
        assertEquals(listOf(1, 2, 3), results)
        job.cancel()
    }
}
