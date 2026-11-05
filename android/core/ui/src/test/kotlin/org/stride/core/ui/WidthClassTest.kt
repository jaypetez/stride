package org.stride.core.ui

import kotlin.test.assertEquals
import org.junit.Test

class WidthClassTest {
    @Test
    fun breakpointsFollowMaterialGuidance() {
        assertEquals(WidthClass.Compact, WidthClass.fromWidthDp(411f))
        assertEquals(WidthClass.Medium, WidthClass.fromWidthDp(600f))
        assertEquals(WidthClass.Medium, WidthClass.fromWidthDp(839f))
        assertEquals(WidthClass.Expanded, WidthClass.fromWidthDp(840f))
    }
}
