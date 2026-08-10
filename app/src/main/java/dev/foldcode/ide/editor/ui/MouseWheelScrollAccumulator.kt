package dev.foldcode.ide

/**
 * Preserves sub-pixel wheel movement until it is large enough to scroll a full pixel.
 * High-resolution and free-spin mouse wheels frequently report fractional axis values.
 */
internal class MouseWheelScrollAccumulator {
    private var remainderX = 0f
    private var remainderY = 0f

    fun consume(deltaX: Float, deltaY: Float): WheelScrollStep {
        remainderX += deltaX
        remainderY += deltaY

        val pixelsX = remainderX.toInt()
        val pixelsY = remainderY.toInt()
        remainderX -= pixelsX
        remainderY -= pixelsY
        return WheelScrollStep(pixelsX, pixelsY)
    }

    fun clearX() {
        remainderX = 0f
    }

    fun clearY() {
        remainderY = 0f
    }
}

internal data class WheelScrollStep(val x: Int, val y: Int)
