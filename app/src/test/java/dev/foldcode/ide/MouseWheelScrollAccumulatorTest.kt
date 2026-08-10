package dev.foldcode.ide

import org.junit.Assert.assertEquals
import org.junit.Test

class MouseWheelScrollAccumulatorTest {
    @Test
    fun `fractional wheel deltas are preserved across events`() {
        val accumulator = MouseWheelScrollAccumulator()

        assertEquals(WheelScrollStep(0, 0), accumulator.consume(0f, 0.4f))
        assertEquals(WheelScrollStep(0, 0), accumulator.consume(0f, 0.4f))
        assertEquals(WheelScrollStep(0, 1), accumulator.consume(0f, 0.4f))
        assertEquals(WheelScrollStep(0, 0), accumulator.consume(0f, 0.7f))
        assertEquals(WheelScrollStep(0, 1), accumulator.consume(0f, 0.2f))
    }

    @Test
    fun `axes accumulate independently in both directions`() {
        val accumulator = MouseWheelScrollAccumulator()

        assertEquals(WheelScrollStep(1, -1), accumulator.consume(1.25f, -1.75f))
        assertEquals(WheelScrollStep(0, -1), accumulator.consume(0.5f, -0.5f))
        assertEquals(WheelScrollStep(1, 0), accumulator.consume(0.25f, 0f))
    }

    @Test
    fun `clearing a blocked axis removes stale outward momentum`() {
        val accumulator = MouseWheelScrollAccumulator()

        accumulator.consume(0f, 0.75f)
        accumulator.clearY()

        assertEquals(WheelScrollStep(0, 0), accumulator.consume(0f, -0.75f))
        assertEquals(WheelScrollStep(0, -1), accumulator.consume(0f, -0.30f))
    }
}
