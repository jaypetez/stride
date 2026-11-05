package org.stride.core.science

import kotlin.test.Test
import kotlin.test.assertTrue

class ScienceTest {
    @Test
    fun engineVersionIsPositive() {
        assertTrue(Science.ENGINE_VERSION > 0)
    }
}
