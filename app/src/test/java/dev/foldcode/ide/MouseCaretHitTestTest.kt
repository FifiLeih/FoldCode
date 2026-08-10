package dev.foldcode.ide

import org.junit.Assert.assertEquals
import org.junit.Test

class MouseCaretHitTestTest {
    @Test
    fun `mouse hit test adds half of the rendered character cell`() {
        assertEquals(5f, nearestCaretBoundaryBias(cellStart = 30f, cellEnd = 40f))
    }

    @Test
    fun `invalid or reversed cell never moves pointer backwards`() {
        assertEquals(0f, nearestCaretBoundaryBias(cellStart = 40f, cellEnd = 30f))
    }
}
